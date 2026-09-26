import test from 'node:test';
import assert from 'node:assert/strict';
import { requestTimetableDecision, requestImageReview } from '../public/timetable-request.js';
import { DECISION_TOOL } from '../public/timetable-protocol.js';
const call = (args, name=DECISION_TOOL) => ({type:'function',function:{name,arguments:args}});
const toolReply = (calls, finish='tool_calls') => new Response(JSON.stringify({choices:[{finish_reason:finish,message:{content:null,tool_calls:calls}}]}));
const reply = (content,finish='stop')=>new Response(JSON.stringify({choices:[{finish_reason:finish,message:{content}}]}),{headers:{'Content-Type':'application/json'}});
test('empty JSON-mode output retries once before returning a valid decision',async()=>{
  const requests=[];
  const result=await requestTimetableDecision({messages:[],context:{}},{fetchImpl:async(_,opts)=>{
    requests.push(JSON.parse(opts.body));return reply(requests.length===1?'   ':JSON.stringify({reply:'想调到哪天？',action:null}));
  }});
  assert.equal(requests.length,2);assert.equal(requests[0].repairEmpty,false);assert.equal(requests[1].repairEmpty,true);assert.equal(result.action,null);
});
test('repeated empty output stops with a clear error, and malformed/truncated outputs never retry',async()=>{
  let calls=0;
  await assert.rejects(()=>requestTimetableDecision({}, {fetchImpl:async()=>{calls++;return reply(' ');}}),/课表未修改/);assert.equal(calls,2);
  for(const [content,finish] of [['{broken','stop'],['','length']]) {
    calls=0;await assert.rejects(()=>requestTimetableDecision({}, {fetchImpl:async()=>{calls++;return reply(content,finish);}}),/课表未修改/);assert.equal(calls,1);
  }
});
test('cancellation prevents retry even when the model returns empty output',async()=>{
  const controller=new AbortController();let calls=0;
  await assert.rejects(()=>requestTimetableDecision({}, {signal:controller.signal,fetchImpl:async()=>{calls++;controller.abort();return reply('');}}),{name:'AbortError'});
  assert.equal(calls,1);
});

test('empty content recovers via exactly one named tool with complete arguments',async()=>{
  let calls=0;
  const decision={reply:'补充老师',action:{type:'metadata',courseId:'cpp',teacher:'王鑫'}};
  const result=await requestTimetableDecision({}, {fetchImpl:async(_,opts)=>{
    calls++;assert.equal(JSON.parse(opts.body).repairEmpty,calls===2);
    return calls===1?reply(''):toolReply([call(JSON.stringify(decision))]);
  }});
  assert.deepEqual(result,decision);assert.equal(calls,2);
});

test('tool recovery rejects unknown, multiple, truncated and malformed calls before execution',async()=>{
  const args=JSON.stringify({reply:'请补充周次',action:null});
  for(const response of [toolReply([call(args,'other')]),toolReply([call(args),call(args)]),
    toolReply([call(args)],'length'),toolReply([call('{broken')]),toolReply([call('')]),toolReply([])]) {
    let calls=0;
    await assert.rejects(()=>requestTimetableDecision({}, {fetchImpl:async()=>++calls===1?reply(''):response}),/课表未修改/);
    assert.equal(calls,2);
  }
});

test('cancellation of recovery discards even valid tool arguments',async()=>{
  const controller=new AbortController();let calls=0;
  await assert.rejects(()=>requestTimetableDecision({}, {signal:controller.signal,fetchImpl:async()=>{
    if(++calls===1)return reply('');
    controller.abort();return toolReply([call(JSON.stringify({reply:'追问',action:null}))]);
  }}),{name:'AbortError'});
  assert.equal(calls,2);
});

test('image verification is independent of first guesses and retains latest photo while bounding old images',async()=>{
  const first={reply:'初步识别',action:{type:'import_preview',courses:[],issues:['待核对']}};
  const verified={reply:'复核结果',action:{type:'import_preview',courses:[],issues:['没有课程']}};
  const messages=[{role:'user',content:[{type:'text',text:'old'},{type:'image_url',image_url:{url:'old-image'}}]},
    {role:'user',content:[{type:'text',text:'new'},{type:'image_url',image_url:{url:'new-image'}}]}];
  const sent=[];
  const result=await requestImageReview({messages},{fetchImpl:async(_,opts)=>{sent.push(JSON.parse(opts.body));return reply(JSON.stringify(sent.length===1?first:verified));},prepareViews:async()=>[{role:'user',content:'局部视图'}]});
  assert.deepEqual(result,verified);assert.equal(sent.length,2);assert.equal(sent[1].importDraft,undefined);
  assert.ok(!JSON.stringify(sent[1]).includes('old-image'));assert.ok(JSON.stringify(sent[1]).includes('new-image'));
  assert.ok(!JSON.stringify(sent[1]).includes('初步识别'));
});
test('stopping image inspection prevents the second model request',async()=>{
  const controller=new AbortController();let calls=0;
  await assert.rejects(()=>requestImageReview({messages:[]},{signal:controller.signal,fetchImpl:async()=>{calls++;return reply(JSON.stringify({reply:'识别',action:{type:'import_preview',courses:[],issues:[]}}));},prepareViews:async()=>{controller.abort();return [];}}),{name:'AbortError'});
  assert.equal(calls,1);
});

test('disagreement cannot silently discard another occurrence of the same subject',async()=>{
  const slot=day=>({day,start:1,end:2,weeks:[1],room:'教三'});
  const draft=sessions=>({reply:'预览',action:{type:'import_preview',courses:[{name:'数字电路基础',teacher:'',sessions}],issues:[]}});
  const answers=[draft([slot(1),slot(5)]),draft([slot(5)]),draft([slot(1),slot(5)])];
  const sent=[];
  const result=await requestImageReview({messages:[{role:'user',content:'识别图片'}]}, {fetchImpl:async(_,opts)=>{
    sent.push(JSON.parse(opts.body));return reply(JSON.stringify(answers[sent.length-1]));
  }});
  assert.equal(sent.length,3);assert.equal(result.action.courses[0].sessions.length,2);
  assert.ok(sent[2].messages.at(-1).content.includes('候选A有2个时间段'));
  assert.equal(result.action.issues.length,0);
});
test('unresolved image slot loss is explicitly blocked for user clarification',async()=>{
  const slot=day=>({day,start:1,end:2,weeks:[1],room:''});
  const draft=sessions=>({reply:'预览',action:{type:'import_preview',courses:[{name:'数学',teacher:'',sessions}],issues:[]}});
  let calls=0;
  const result=await requestImageReview({messages:[]},{fetchImpl:async()=>reply(JSON.stringify(++calls===1?draft([slot(1),slot(3)]):draft([slot(3)])))});
  assert.equal(calls,3);assert.ok(result.action.issues.some(s=>s.includes('课次数量不一致')));
});
