// node preview/test/timetable-change-live.mjs <local key file>
import { readFile, writeFile } from 'node:fs/promises';
import assert from 'node:assert/strict';
import { loadSource } from '../source.mjs';
import { operationContext, applyTimetableAction } from '../public/timetable-ai.js';
import { requestTimetableDecision } from '../public/timetable-request.js';
if (!process.argv[2]) throw new Error('Pass a local test key file path');
const apiKey=(await readFile(process.argv[2],'utf8')).replace(/^\uFEFF/,'').trim();
const fixture=JSON.parse(await readFile(new URL('./change-fixture.json',import.meta.url),'utf8'));
const {periods}=await loadSource();const results=[];
async function run(name,board,date,message,verify) {
  const context=operationContext(board,periods,date,5);
  const decision=await requestTimetableDecision({apiKey,config:{model:'deepseek-flash',maxTokens:2048},messages:[{role:'user',content:message}],context},{
    fetchImpl:(url,opts)=>fetch(`http://127.0.0.1:4173${url}`,{...opts,headers:{...opts.headers,Origin:'http://127.0.0.1:4173'},signal:AbortSignal.timeout(180000)})
  });
  await writeFile(new URL('../.runtime/timetable-change-last-case.json',import.meta.url),JSON.stringify({name,message,decision},null,2));
  const result=applyTimetableAction(board,context,decision,name);verify(result);
  results.push({name,input:message,date,viewedWeek:5,decision,result:result.text});console.log(`PASS ${name}`);
}
const sunday=structuredClone(fixture);sunday.sessions[0].day=7;
await run('sunday-to-monday',sunday,'2026-09-27','今天英语那次往后挪一天，明天上午头两节上，教室不变',r=>{
  const moved=r.board.sessions.find(s=>s.courseId==='english'&&s.day===1);
  assert.ok(moved);assert.equal(moved.start,1);assert.equal(moved.end,2);assert.deepEqual(moved.weeks,[2]);assert.equal(moved.room,'礼东');
  assert.deepEqual(r.board.sessions.find(s=>s.courseId==='english'&&s.day===7).weeks,[2,3,4]);
});
await run('recurring-subset',fixture,'2026-09-24','从第2周开始，周四的英语都改到每周二下午第6、7节，周五那次不动',r=>{
  assert.deepEqual(r.board.sessions.find(s=>s.courseId==='english'&&s.day===4).weeks,[1]);
  const moved=r.board.sessions.find(s=>s.courseId==='english'&&s.day===2);assert.equal(moved.start,6);assert.equal(moved.end,7);assert.deepEqual(moved.weeks,[2,3,4]);
  assert.deepEqual(r.board.sessions.find(s=>s.id==='english-fri'),fixture.sessions[1]);
});
const existing=structuredClone(fixture);existing.courses.push({id:'cpp-existing',name:'CPP',teacher:'李老师',credit:4,note:'带电脑',colorOverride:6,code:'CS01',classNo:'01'});
existing.sessions.push({id:'cpp-mon',courseId:'cpp-existing',day:1,start:6,end:7,weeks:[1,2,3,4],room:'机房'});
await run('replace-with-existing',existing,'2026-09-24','明天英语先不上，换成课表里李老师教的CPP，时间地点还按原来的，其他课不动',r=>{
  assert.equal(r.board.courses.length,existing.courses.length);assert.deepEqual(r.board.courses.find(c=>c.id==='cpp-existing'),existing.courses.at(-1));
  assert.ok(r.board.sessions.some(s=>s.courseId==='cpp-existing'&&s.day===5&&s.start===3&&s.end===4&&s.weeks.length===1&&s.weeks[0]===1&&s.room==='教三'));
  assert.deepEqual(r.board.sessions.find(s=>s.id==='cpp-mon'),existing.sessions.at(-1));
});
const duplicate=structuredClone(fixture);duplicate.courses.push({...fixture.courses[0],id:'other-english',teacher:'王老师'});duplicate.sessions.push({...fixture.sessions[0],id:'other-thu',courseId:'other-english'});
await run('ambiguous-move',duplicate,'2026-09-24','今天英语挪到本周二第2、3节',r=>{
  assert.equal(r.changed,false);assert.deepEqual(r.board,duplicate);assert.match(r.text,/张|王|两|哪/);
});
await writeFile(new URL('../.runtime/timetable-change-live-cases.json',import.meta.url),JSON.stringify({realApi:true,model:'deepseek-flash',results},null,2));
