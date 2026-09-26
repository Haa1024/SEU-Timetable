import test from 'node:test';
import assert from 'node:assert/strict';
import { applyTimetableAction, operationContext, undoTimetableAction, boardVersion } from '../public/timetable-ai.js';
import { loadSource } from '../source.mjs';

const source = await loadSource();
const initial = () => ({ ...structuredClone(source.board), courses: [
  { id:'english', name:'英语', teacher:'张老师', credit:3.5, note:'带课本', colorOverride:4, code:'EN01', classNo:'02' },
  { id:'cpp', name:'CPP', teacher:'李老师', credit:4, note:'带电脑', colorOverride:6, code:'CS01', classNo:'01' },
], sessions: [
  { id:'e-thu',courseId:'english',day:4,start:1,end:2,weeks:[1,2,3],room:'礼东' },
  { id:'e-fri',courseId:'english',day:5,start:3,end:4,weeks:[1,2,3],room:'教三' },
  { id:'c-mon',courseId:'cpp',day:1,start:6,end:7,weeks:[1,2,3],room:'计算机房' },
], unplaced:[] });
const ctx = (b,ids=[]) => operationContext(b,source.periods,'2026-09-24',1,ids);
const change = (to, extra={}) => ({type:'change',target:{name:'英语',day:4},scope:'sessions',weeks:[1],to,...extra});
const apply = (b, action, id='test') => applyTimetableAction(b,ctx(b),{reply:'操作',action},id);
const sessions = (b,id) => b.sessions.filter(s=>s.courseId===id);

test('one occurrence moves by atomic removal and insertion, preserving all course metadata',()=>{
  const b=initial(), copy=structuredClone(b), r=apply(b,change({day:2,start:2,end:3}));
  assert.deepEqual(b,copy);
  assert.deepEqual(r.board.courses.find(c=>c.id==='english'),b.courses[0]);
  assert.deepEqual(sessions(r.board,'english').find(s=>s.day===4).weeks,[2,3]);
  assert.deepEqual(sessions(r.board,'english').find(s=>s.day===2),{id:'ai-session-test-0',courseId:'english',day:2,start:2,end:3,weeks:[1],room:'礼东'});
  assert.deepEqual(r.board.sessions.find(s=>s.id==='e-fri'),b.sessions[1]);
  assert.equal(boardVersion(undoTimetableAction(r.board,'test')),boardVersion(b));
});
test('cross-week move and omitted end preserve original duration',()=>{
  const r=apply(initial(),change({day:1,start:8,weeks:[2]}));
  const moved=sessions(r.board,'english').find(s=>s.day===1);
  assert.equal(moved.end,9);assert.deepEqual(moved.weeks,[2]);
});
test('exact dates resolve week boundaries and reject contradictory or out-of-term dates',()=>{
  const b=initial();b.sessions[0].day=7;
  const action={type:'change',target:{name:'英语',date:'2026-09-27'},scope:'sessions',weeks:null,to:{date:'2026-09-28',start:1,end:2}};
  const r=apply(b,action);assert.deepEqual(sessions(r.board,'english').find(s=>s.day===1).weeks,[2]);
  assert.deepEqual(ctx(b).calendar.tomorrow,{date:'2026-09-25',day:5,week:1,withinTerm:true});
  for(const to of [{date:'2026-09-28',weeks:[1]},{date:'2026-09-28',day:7},{date:'2026-09-20'},{date:'2026-02-30'}]) assert.throws(()=>apply(b,{...action,to}));
  assert.equal(operationContext(b,source.periods,'2026-09-20',1).calendar.today.withinTerm,false);
  assert.throws(()=>apply(b,{...action,target:{name:'英语',date:'2026-09-27',day:1}}));
});
test('replacing tomorrow only changes one occurrence and clears unrelated metadata',()=>{
  const b=initial(),r=apply(b,change({name:'线性代数'},{target:{name:'英语',day:5}}));
  const fresh=r.board.courses.find(c=>c.name==='线性代数');
  assert.equal(fresh.teacher,'');assert.equal(fresh.credit,'');assert.equal(fresh.note,'');assert.equal(fresh.code,'');assert.equal(fresh.colorOverride,null);
  assert.deepEqual(sessions(r.board,'english').find(s=>s.day===5).weeks,[2,3]);
  assert.deepEqual(sessions(r.board,fresh.id).map(s=>[s.day,s.start,s.end,s.weeks,s.room]),[[5,3,4,[1],'教三']]);
  assert.deepEqual(r.receipt.createdCourseIds,[fresh.id]);
  const updated=applyTimetableAction(r.board,ctx(r.board,r.receipt.createdCourseIds),{reply:'补充',action:{type:'metadata',courseId:fresh.id,teacher:'王老师',credit:2,note:'带笔记'}},'more');
  assert.equal(updated.board.courses.find(c=>c.id===fresh.id).teacher,'王老师');
});
test('replacement can reuse an existing course without overwriting its metadata or sessions',()=>{
  const b=initial(),r=apply(b,change({courseId:'cpp',name:'CPP'},{target:{name:'英语',day:5}}));
  assert.deepEqual(r.board.courses.find(c=>c.id==='cpp'),b.courses[1]);
  assert.equal(sessions(r.board,'cpp').length,2);
  assert.deepEqual(r.board.sessions.find(s=>s.id==='c-mon'),b.sessions[2]);
  assert.deepEqual(r.receipt.createdCourseIds,[]);
});
test('whole-course replacement retains every slot but removes original course',()=>{
  const b=initial(),r=apply(b,change({name:'线性代数',teacher:'王老师',credit:2},{target:{name:'英语'},scope:'course',weeks:null}));
  assert.ok(!r.board.courses.some(c=>c.id==='english'));
  const c=r.board.courses.find(c=>c.name==='线性代数');assert.equal(c.teacher,'王老师');assert.equal(c.credit,2);
  assert.equal(sessions(r.board,c.id).length,2);
  assert.equal(boardVersion(undoTimetableAction(r.board,'test')),boardVersion(b));
});
test('invalid destinations never delete the source course',()=>{
  for(const to of [{day:8},{start:99},{weeks:[99]},{start:4,end:1},{name:''},{courseId:'missing'},{name:'CPP'},{}, {name:'CPP',courseId:'cpp',teacher:'wrong'}]) {
    const b=initial(),copy=structuredClone(b);assert.throws(()=>apply(b,change(to)));assert.deepEqual(b,copy);
  }
});
test('ambiguous source and conflicting same-course destination are rejected',()=>{
  const b=initial();b.courses.push({...b.courses[0],id:'e2',teacher:'另一老师'});b.sessions.push({...b.sessions[0],id:'s2',courseId:'e2'});
  assert.throws(()=>apply(b,change({day:2})),/同名课程/);
  assert.throws(()=>apply(initial(),change({day:5,start:3,end:4})),/目标时段已有/);
  assert.throws(()=>apply(initial(),change({start:2,end:3},{target:{name:'英语'},scope:'course',weeks:null})),/多个不同时间段/);
});
test('batch delete/add is committed as one operation and undone together',()=>{
  const b=initial();const action={type:'batch',actions:[
    {type:'delete',target:{name:'英语',day:4},scope:'sessions',weeks:[1]},
    {type:'add',name:'线性代数',sessions:[{day:2,start:2,end:3,weeks:[1],room:'礼西'}]},
  ]};
  const r=apply(b,action,'group');assert.equal(r.receipt.type,'batch');assert.equal(r.receipt.createdCourseIds.length,1);
  assert.deepEqual(r.board.aiRequestIds,['group']);
  assert.equal(boardVersion(undoTimetableAction(r.board,'group')),boardVersion(b));
  assert.throws(()=>apply(r.board,action,'group'),/已经处理/);
});
test('batch failure after a valid delete rolls back all steps',()=>{
  const b=initial(),copy=structuredClone(b);
  assert.throws(()=>apply(b,{type:'batch',actions:[{type:'delete',target:{name:'英语'},scope:'course',weeks:null},{type:'add',name:'坏课程',sessions:[{day:9,start:1,end:2,weeks:[1]}]}]}));
  assert.deepEqual(b,copy);
  assert.throws(()=>apply(b,{type:'batch',actions:[{type:'batch',actions:[]}]}));
});
test('different courses can swap occurrences without losing future weeks',()=>{
  const b=initial(),r=apply(b,{type:'batch',actions:[change({day:1,start:6,end:7}),{...change({day:4,start:1,end:2}),target:{name:'CPP',day:1}}]});
  assert.deepEqual(sessions(r.board,'english').find(s=>s.day===1).weeks,[1]);
  assert.deepEqual(sessions(r.board,'cpp').find(s=>s.day===4).weeks,[1]);
  assert.deepEqual(sessions(r.board,'cpp').find(s=>s.day===1).weeks,[2,3]);
  assert.ok(!r.text.includes('时间冲突'));
  assert.equal(boardVersion(undoTimetableAction(r.board,'test')),boardVersion(b));
});
