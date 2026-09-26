import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { timetablePrompt } from '../timetable-prompt.mjs';
import { requestTimetableDecision } from '../public/timetable-request.js';
import { applyTimetableAction, operationContext } from '../public/timetable-ai.js';
import { prepareContext } from '../public/ai-context.js';
import { DEFAULT_LLM_CONFIG } from '../public/ai-core.js';
import { buildUpstreamRequest } from '../llm-proxy.mjs';
const fixture=JSON.parse(await readFile(new URL('./change-fixture.json',import.meta.url),'utf8'));
const board={...fixture,courses:[],sessions:[],unplaced:[]};
const periods=Array.from({length:13},(_,i)=>({index:i+1,begin:'08:00',end:'08:45'}));
const context=operationContext(board,periods,'2026-09-26',1);
const messages=[{role:'user',content:'每个礼拜都照这个安排。'}];
const proposal=weeks=>({reply:'待执行',action:{type:'add',name:'工科数分',sessions:[{day:7,start:1,end:12,weeks}]}});
const response=d=>new Response(JSON.stringify({choices:[{finish_reason:'stop',message:{content:JSON.stringify(d)}}]}));

test('ask policy and teaching range are data-driven; example no longer conflates unspecified and every week',()=>{
  for (const lastTeachingWeek of [8,16,18]) {
    const c=operationContext({...board,term:{...board.term,lastTeachingWeek}},periods,'2026-09-26',1);
    const prompt=timetablePrompt(c);
    assert.ok(prompt.includes('当前采用 ask'));
    assert.ok(prompt.includes(JSON.stringify(Array.from({length:lastTeachingWeek},(_,i)=>i+1))));
    assert.ok(!prompt.includes('默认规则 defaultWeeks=term'));
    assert.ok(prompt.includes('不能来自助手以前的'));
  }
});
test('missing model week array is repaired before any mutation, retaining the exact user conversation',async()=>{
  const sent=[],before=structuredClone(board),weeks=Array.from({length:16},(_,i)=>i+1);
  const decision=await requestTimetableDecision({context,messages},{fetchImpl:async(_,opts)=>{
    sent.push(JSON.parse(opts.body));assert.deepEqual(board,before);
    return response(proposal(sent.length===1?null:weeks));
  }});
  assert.equal(sent.length,2);assert.equal(sent[1].repairWeeks,true);assert.deepEqual(sent[1].messages,messages);
  assert.deepEqual(board,before);
  const result=applyTimetableAction(board,context,decision,'confirmed');
  assert.equal(result.board.courses.length,1);assert.deepEqual(result.board.sessions[0].weeks,weeks);
});
test('genuinely unspecified weeks can be corrected to a clarification with no writes',async()=>{
  let count=0;
  const decision=await requestTimetableDecision({context,messages},{fetchImpl:async()=>response(++count===1?proposal(null):{reply:'安排在哪些教学周？',action:null})});
  assert.equal(decision.action,null);assert.equal(count,2);
});
test('repeated malformed week output stops after one correction and does not blame missing user input',async()=>{
  let count=0;
  await assert.rejects(()=>requestTimetableDecision({context,messages},{fetchImpl:async()=>{count++;return response(proposal(null));}}),/模型未能正确填写/);
  assert.equal(count,2);assert.equal(board.courses.length,0);
});
test('cancellation discards the corrected proposal before execution',async()=>{
  const controller=new AbortController();let count=0;
  await assert.rejects(()=>requestTimetableDecision({context,messages},{signal:controller.signal,fetchImpl:async()=>{
    if(++count===2)controller.abort();return response(proposal(count===1?null:[1]));
  }}),{name:'AbortError'});assert.equal(count,2);
});
test('delete null/all scope and valid additions are not confused with missing add weeks',async()=>{
  for(const decision of [proposal([3,4,5]),{reply:'删除',action:{type:'delete',scope:'course',target:{name:'数学'},weeks:null}},{reply:'追问',action:null}]){
    let count=0;assert.deepEqual(await requestTimetableDecision({context,messages},{fetchImpl:async()=>{count++;return response(decision);}}),decision);assert.equal(count,1);
  }
});
test('batch missing weeks is corrected as a whole before returning any action',async()=>{
  let count=0;
  const decision=await requestTimetableDecision({context,messages},{fetchImpl:async()=>response({reply:'组合',action:{type:'batch',actions:[proposal(++count===1?null:[2]).action]}})});
  assert.equal(count,2);assert.deepEqual(decision.action.actions[0].sessions[0].weeks,[2]);
});
test('operation history conveys old failures even when they have no operationStatus field',async()=>{
  const history=[{id:'u1',role:'user',text:'星期天1-12节',status:'complete'},
    {id:'a1',role:'assistant',text:'',status:'error',error:'请先说明上课周次'},
    {id:'u2',role:'user',text:'每一周',status:'complete'}];
  const result=await prepareContext(history,null,DEFAULT_LLM_CONFIG,'unused',{operationMode:true});
  assert.equal(result.messages.length,3);assert.match(result.messages[1].content,/执行失败，课表未修改.*请先说明/);
  assert.equal(history[1].status,'error');
  assert.equal((await prepareContext(history,null,DEFAULT_LLM_CONFIG,'unused')).messages.length,2);
});
test('week repair adds trusted instructions without changing messages or weakening ask policy',()=>{
  const {body}=buildUpstreamRequest({config:DEFAULT_LLM_CONFIG,apiKey:'mock',mode:'timetable',context,messages,repairWeeks:true});
  assert.match(body.messages[0].content,/本次是执行前的格式纠正/);
  assert.match(body.messages[0].content,/真正没有说明周次时，action:null/);
  assert.deepEqual(body.messages.slice(1),messages);
});
