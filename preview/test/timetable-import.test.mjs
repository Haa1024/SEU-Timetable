import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { operationContext,applyTimetableAction,undoTimetableAction,boardSnapshot } from '../public/timetable-ai.js';
import { validateImportDraft,importMissing,planTimetableImport } from '../public/timetable-import.js';
import { buildUpstreamRequest } from '../llm-proxy.mjs';
const fixture=JSON.parse(readFileSync(new URL('./change-fixture.json',import.meta.url),'utf8'));
const periods=Array.from({length:13},(_,i)=>({index:i+1,begin:'08:00',end:'08:45'}));
const context=b=>operationContext(b,periods,'2026-09-26',1,[]);
const draft=()=>({type:'import_preview',issues:[],courses:[{name:'工科数学分析',teacher:'王老师',sessions:[{day:6,start:1,end:2,weeks:[1],room:'礼东'}]}]});
test('image preview cannot execute; missing weeks or uncertain cells disable every import strategy',()=>{
  const d=draft();d.courses[0].sessions[0].weeks=null;
  assert.ok(importMissing(validateImportDraft(d,context(fixture))).length);
  for(const s of ['append','weeks','all'])assert.throws(()=>planTimetableImport(fixture,context(fixture),d,s,'i'),/补齐/);
  assert.throws(()=>applyTimetableAction(fixture,context(fixture),{reply:'已确认',action:d},'i'),/确认/);
  d.courses[0].sessions[0].weeks=[1];d.issues=['图片底部截断'];
  assert.throws(()=>planTimetableImport(fixture,context(fixture),d,'all','i'),/截断/);
});
test('append preserves old courses, skips exact duplicates, and import undo restores all data',()=>{
  const before=structuredClone(fixture),ctx=context(fixture);
  const r=planTimetableImport(fixture,ctx,draft(),'append','imp');
  assert.deepEqual(fixture,before);assert.equal(r.board.courses.length,3);assert.equal(r.receipt.createdCourseIds.length,1);
  assert.deepEqual(boardSnapshot(undoTimetableAction(r.board,'imp')),boardSnapshot(fixture));
  const duplicate=planTimetableImport(r.board,context(r.board),draft(),'append','imp2');assert.equal(duplicate.changed,false);
});
test('an image without teacher information does not duplicate or erase known original metadata',()=>{
  const d={type:'import_preview',issues:[],courses:[{name:'英语',teacher:'',sessions:[{day:4,start:1,end:2,weeks:[1],room:'礼东'}]}]};
  const r=planTimetableImport(fixture,context(fixture),d,'append','existing');
  assert.equal(r.changed,false);assert.deepEqual(r.board.courses,fixture.courses);assert.match(r.impact,/跳过 1/);
});
test('week replacement removes only selected weeks while full replacement also removes unplaced courses',()=>{
  const b=structuredClone(fixture);b.unplaced=[{id:'u',name:'待安排'}];b.courses.push({id:'u',name:'待安排'});
  const r=planTimetableImport(b,context(b),draft(),'weeks','weeks');
  for(const s of r.board.sessions.filter(s=>['english','sport'].includes(s.courseId)))assert.deepEqual(s.weeks,[2,3,4]);
  assert.deepEqual(r.board.unplaced,b.unplaced);assert.match(r.impact,/第 1 周/);
  const all=planTimetableImport(b,context(b),draft(),'all','all');assert.equal(all.board.courses.length,1);assert.deepEqual(all.board.unplaced,[]);
  assert.deepEqual(boardSnapshot(undoTimetableAction(all.board,'all')),boardSnapshot(b));
});
test('large image imports support more than 10 courses and fail atomically on any invalid course',()=>{
  const d=draft();d.courses=Array.from({length:12},(_,i)=>({name:`课程${i}`,sessions:[{day:i%7+1,start:Math.floor(i/7)*2+1,end:Math.floor(i/7)*2+2,weeks:[1]}]}));
  const r=planTimetableImport(fixture,context(fixture),d,'all','large');assert.equal(r.board.courses.length,12);
  d.courses.at(-1).sessions[0].end=99;const before=structuredClone(fixture);
  assert.throws(()=>planTimetableImport(fixture,context(fixture),d,'all','bad'),/范围/);assert.deepEqual(fixture,before);
});
test('stale image review and already applied request cannot overwrite newer edits',()=>{
  const b=structuredClone(fixture),ctx=context(b);b.courses[0].note='手工更改';
  assert.throws(()=>planTimetableImport(b,ctx,draft(),'all','x'),/改变/);
  const r=planTimetableImport(fixture,context(fixture),draft(),'append','x');
  assert.throws(()=>planTimetableImport(r.board,context(r.board),draft(),'all','x'),/已导入/);
});
test('image prompt only permits proposals even during tool recovery; no false week default',()=>{
  const r=buildUpstreamRequest({apiKey:'test',mode:'timetable',imageReview:true,repairEmpty:true,context:context(fixture),messages:[{role:'user',content:'直接导入'}]}).body;
  assert.deepEqual(r.tools[0].function.parameters.properties.action.anyOf[1].properties.type.enum,['import_preview']);
  assert.match(r.messages[0].content,/必须询问周次/);assert.match(r.messages[0].content,/最终写入由用户/);
});
