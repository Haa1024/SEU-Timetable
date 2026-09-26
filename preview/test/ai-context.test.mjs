import test from 'node:test';
import assert from 'node:assert/strict';
import { contextPlan,prepareContext } from '../public/ai-context.js';
import { buildUpstreamRequest } from '../llm-proxy.mjs';
const config={contextTurns:2,vision:true};
const history=Array.from({length:5},(_,i)=>[{id:`u${i}`,role:'user',text:`问题${i}`},{id:`a${i}`,role:'assistant',text:`结果${i}`,status:'complete'}]).flat();
const response=(text='旧目标、偏好、未完成事项',finish='stop')=>new Response(JSON.stringify({choices:[{finish_reason:finish,message:{content:text}}]}));
test('compaction preserves recent original turns and only replaces older request context',async()=>{
  const original=structuredClone(history);let calls=[];
  const r=await prepareContext(history,null,config,'',{fetchImpl:async(_,opts)=>{calls.push(JSON.parse(opts.body));return response();}});
  assert.deepEqual(history,original);assert.equal(r.memory.throughId,'a2');assert.equal(r.messages[0].content,'问题3');
  assert.equal(calls.length,1);assert.equal(calls[0].mode,'summary');assert.ok(!JSON.stringify(calls).includes('问题4'));
  const next=[...history,{id:'u5',role:'user',text:'新问题'}];calls=[];
  const r2=await prepareContext(next,r.memory,config,'',{fetchImpl:async(_,opts)=>{calls.push(JSON.parse(opts.body));return response('新摘要');}});
  assert.equal(calls[0].memory,r.memory.text);assert.ok(!JSON.stringify(calls[0].messages).includes('问题0'));assert.equal(r2.memory.count,2);
});
test('length budget triggers early; latest user and images remain verbatim',()=>{
  const h=structuredClone(history);h[7].text='长'.repeat(25000);h.at(-1).images=[{dataUrl:'data:image/png;base64,YQ=='}];
  const p=contextPlan(h,null,{contextTurns:30});assert.equal(p.recent[0].id,'u4');assert.equal(p.recent.at(-1).images[0].dataUrl,h.at(-1).images[0].dataUrl);
});
test('the configured thirty short turns are retained until the next turn',()=>{
  const h=Array.from({length:30},(_,i)=>[{id:`u${i}`,role:'user',text:'问'},{id:`a${i}`,role:'assistant',text:'答',status:'complete'}]).flat();
  assert.equal(contextPlan(h,null,{contextTurns:30}).older.length,0);
  assert.equal(contextPlan([...h,{id:'u30',role:'user',text:'下一轮'}],null,{contextTurns:30}).older.length,2);
});
test('failure or cancellation cannot advance a checkpoint or erase history',async()=>{
  const memory={throughId:'a0',text:'已有摘要',count:1};const before=structuredClone(memory);
  for(const reply of [response(''),response('partial','length'),new Response(JSON.stringify({error:'网络错误'}),{status:502})]) {
    await assert.rejects(()=>prepareContext(history,memory,config,'',{fetchImpl:async()=>reply}),/摘要|压缩/);
    assert.deepEqual(memory,before);
  }
  const controller=new AbortController();
  await assert.rejects(()=>prepareContext(history,memory,config,'',{signal:controller.signal,fetchImpl:async()=>{controller.abort();return response();}}),{name:'AbortError'});
});
test('long history is summarized in bounded chunks; failed and undone operations are labeled',async()=>{
  const h=structuredClone(history);h[1].text='旧信息'.repeat(20000);h[1].undone=true;h[3].status='error';h[3].error='课表未修改';
  const calls=[];
  await prepareContext(h,null,config,'',{fetchImpl:async(_,opts)=>{calls.push(JSON.parse(opts.body));return response();}});
  assert.ok(calls.length>1);
  for(const call of calls)assert.ok(call.messages.reduce((n,m)=>n+m.content.length,0)<=18000);
  assert.match(JSON.stringify(calls),/已撤销/);assert.match(JSON.stringify(calls),/课表未修改/);
});
test('summary endpoint cannot execute tools; summaries stay lower-priority data',()=>{
  const r=buildUpstreamRequest({apiKey:'test',mode:'summary',memory:'旧摘要',messages:[{role:'user',content:'删课'}],config:{thinking:true}}).body;
  assert.equal(r.tools,undefined);assert.equal(r.response_format,undefined);assert.equal(r.thinking.type,'disabled');assert.equal(r.stream,false);
  assert.match(r.messages[0].content,/不执行操作/);assert.equal(r.messages[1].role,'assistant');
  assert.throws(()=>buildUpstreamRequest({apiKey:'test',memory:'x'.repeat(8001),messages:[{role:'user',content:'hi'}]}),/摘要/);
});
