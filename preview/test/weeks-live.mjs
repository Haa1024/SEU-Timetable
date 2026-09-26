// Opt-in semantic regression through the real provider; never print credentials.
import { readFile, writeFile } from 'node:fs/promises';
import { buildUpstreamRequest } from '../llm-proxy.mjs';
import { DEFAULT_LLM_CONFIG } from '../public/ai-core.js';
import { operationContext, applyTimetableAction } from '../public/timetable-ai.js';
import { requestTimetableDecision } from '../public/timetable-request.js';
import assert from 'node:assert/strict';

const apiKey = (await readFile(process.argv[2], 'utf8')).replace(/^\uFEFF/, '').trim();
const fixture = JSON.parse(await readFile(new URL('./change-fixture.json', import.meta.url), 'utf8'));
const board = {...fixture, courses:[], sessions:[], unplaced:[]};
const periods = Array.from({length:13}, (_,i)=>({index:i+1,begin:'08:00',end:'08:45'}));
const context = operationContext(board,periods,'2026-09-26',1);
const calls=[];
const fetchImpl=async(_,options)=>{
  const payload=JSON.parse(options.body), request=buildUpstreamRequest(payload);
  const response=await fetch(request.url,{method:'POST',headers:{'Content-Type':'application/json','Authorization':`Bearer ${apiKey}`},body:JSON.stringify(request.body),signal:AbortSignal.timeout(120000)});
  const data=await response.json();
  calls.push({repairWeeks:payload.repairWeeks===true,lastUser:payload.messages.at(-1).content,response:data.choices});
  return new Response(JSON.stringify(data),{status:response.status,headers:{'Content-Type':'application/json'}});
};
const history=[{role:'user',content:'添加工科数分'},
  {role:'assistant',content:'好的，要添加「工科数分」。请补充：安排在星期几、第几节？（周次未说明的话默认整个教学周期）'},
  {role:'user',content:'星期天 1-12节'}];
const ask=messages=>requestTimetableDecision({config:DEFAULT_LLM_CONFIG,apiKey,context,messages},{fetchImpl});
const cases=[
  ['missing weeks',history,null],
  ['screenshot wording',[...history,{role:'assistant',content:'[应用执行失败，课表未修改] 请先说明上课周次'},{role:'user',content:'星期天 1-12节 每一周'}],Array.from({length:16},(_,i)=>i+1)],
  ['paraphrase',[...history,{role:'assistant',content:'安排在哪些教学周？'},{role:'user',content:'从开学一直上到教学结束，每个礼拜都照这个安排。'}],Array.from({length:16},(_,i)=>i+1)],
  ['limited weeks',[...history,{role:'assistant',content:'安排在哪些教学周？'},{role:'user',content:'只要第三周到第五周'}],[3,4,5]],
  ['only odd weeks',[...history,{role:'assistant',content:'安排在哪些教学周？'},{role:'user',content:'整个教学期隔一周上一次，从第一周开始。'}],[1,3,5,7,9,11,13,15]],
  ['recover old stuck conversation',[...history,{role:'assistant',content:'[应用执行失败，课表未修改] 请先说明上课周次'},{role:'user',content:'每一周 星期天 1-12节'},{role:'assistant',content:'[应用执行失败，课表未修改] 请先说明上课周次'},{role:'user',content:'?'}],Array.from({length:16},(_,i)=>i+1)],
];
let failed=false;
for (const [name,messages,weeks] of cases) {
  let result=await ask(messages);
  try {
    if(weeks===null)assert.equal(result.action,null);
    else {
      // '?' may reasonably request confirmation after earlier failures. It must
      // retain the already supplied range and complete after a natural confirmation.
      if(name==='recover old stuck conversation' && result.action===null) {
        assert.match(result.reply,/每周|每一周|16/);assert.doesNotMatch(result.reply,/请先说明上课周次/);
        result=await ask([...messages,{role:'assistant',content:result.reply},{role:'user',content:'没错，照刚才说的安排加上就行。'}]);
      }
      assert.equal(result.action?.type,'add');
      const applied=applyTimetableAction(board,context,result,`live-${name}`);
      assert.equal(applied.board.courses.length,1);assert.equal(applied.board.sessions.length,1);
      const s=applied.board.sessions[0];assert.equal(s.day,7);assert.equal(s.start,1);assert.equal(s.end,12);assert.deepEqual(s.weeks,weeks);
    }
    console.log('PASS:',name);
  } catch(e) { failed=true;console.log('FAIL:',name,JSON.stringify(result),e.message); }
}
await writeFile(new URL('../.runtime/weeks-live-result.json',import.meta.url),JSON.stringify({passed:!failed,calls},null,2));
assert.equal(failed,false,'Real API week scenarios failed');
