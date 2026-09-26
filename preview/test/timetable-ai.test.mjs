import test from 'node:test';
import assert from 'node:assert/strict';
import { applyTimetableAction, operationContext, undoTimetableAction, boardVersion, parseDecision } from '../public/timetable-ai.js';
import { buildUpstreamRequest } from '../llm-proxy.mjs';
import { loadSource } from '../source.mjs';

const source = await loadSource();
const empty = () => ({ ...structuredClone(source.board), courses: [], sessions: [], unplaced: [] });
const context = (b, ids = []) => operationContext(b, source.periods, '2026-09-26', 1, ids);
const addition = (overrides = {}) => ({ reply: '准备添加', action: { type: 'add', name: '工科数学分析', sessions: [{ day: 6, start: 1, end: 2, weeks: [1, 2, 3], room: '礼东' }], ...overrides } });
const added = () => { const b = empty(); return applyTimetableAction(b, context(b), addition(), 'add-1'); };
const deletion = (overrides = {}) => ({ reply: '准备删除', action: { type: 'delete', target: { name: '工科数学分析' }, scope: 'course', weeks: null, ...overrides } });

test('add validates required fields and reports actual creation plus optional follow-up', () => {
  const b = empty(), before = structuredClone(b), result = applyTimetableAction(b, context(b), addition(), 'a');
  assert.deepEqual(b, before);
  assert.equal(result.board.courses.length, 1);
  assert.deepEqual(result.board.sessions[0].weeks, [1, 2, 3]);
  assert.equal(result.board.sessions[0].day, 6);
  assert.match(result.text, /已添加.*工科数学分析/);
  assert.match(result.text, /授课老师、学分、备注/);
  assert.equal(result.receipt.courseId, result.board.courses[0].id);
  for (const change of [{ name: '' }, { sessions: [] }, { sessions: [{ day: 6, start: 1, end: 2, weeks: null }] }, { sessions: [{ day: 6, start: 1, end: 99, weeks: [1] }] }, { sessions: [{ day: 6, start: 1, end: 2, weeks: [99] }] }, { teacher: 23 }]) {
    assert.throws(() => applyTimetableAction(b, context(b), addition(change), 'bad'));
    assert.deepEqual(b, before);
  }
});
test('clarification and unsupported or malformed actions cannot mutate data', () => {
  const b = empty();
  assert.equal(applyTimetableAction(b, context(b), { reply: '请说明周次', action: null }, 'q').changed, false);
  for (const value of [{ reply: 'x', action: { type: 'replace' } }, { reply: 'x', action: [] }, { reply: 'x', action: null, script: 'x' }, '{broken']) assert.throws(() => parseDecision(value));
  assert.deepEqual(parseDecision(addition().action).action, addition().action);
  assert.throws(() => applyTimetableAction(b, context(b), { ...addition().action, script: 'arbitrary code' }, 'bad'));
});
test('same request and same-name overlapping schedule do not create duplicates', () => {
  const { board: b } = added();
  assert.throws(() => applyTimetableAction(b, context(b), addition(), 'add-1'), /已经处理/);
  assert.throws(() => applyTimetableAction(b, context(b), addition(), 'other'), /未重复添加/);
  const result = applyTimetableAction(b, context(b), addition({ name: '另一门课' }), 'clash');
  assert.match(result.text, /时间冲突/);
  assert.equal(result.board.courses.length, 2);
});
test('metadata updates newly created course without recreating or changing its times', () => {
  const { board: b, receipt } = added();
  const decision = { reply: '补充', action: { type: 'metadata', courseId: receipt.courseId, teacher: '张老师', credit: 3.5, note: '带计算器' } };
  assert.throws(() => applyTimetableAction(b, context(b), decision, 'm'), /本阶段只能/);
  const result = applyTimetableAction(b, context(b, [receipt.courseId]), decision, 'm');
  assert.equal(result.board.courses.length, 1); assert.equal(result.board.courses[0].credit, 3.5);
  assert.deepEqual(result.board.sessions, b.sessions);
  assert.throws(() => applyTimetableAction(b, context(b, [receipt.courseId]), { ...decision, action: { ...decision.action, name: 'CPP' } }, 'rename'));
});
test('deletion removes whole course and all sessions, undo restores exact data', () => {
  const { board: b } = added();
  const result = applyTimetableAction(b, context(b), deletion(), 'd');
  assert.equal(result.board.courses.length, 0); assert.equal(result.board.sessions.length, 0);
  assert.equal(boardVersion(undoTimetableAction(result.board, 'd')), boardVersion(b));
});
test('occurrence deletion preserves other weeks and other sessions', () => {
  const { board: b } = added();
  b.sessions.push({ ...b.sessions[0], id: 'second', day: 2 });
  const result = applyTimetableAction(b, context(b), deletion({ scope: 'sessions', target: { name: '工科数学分析', day: 6, start: 1, end: 2 }, weeks: [1] }), 'd');
  assert.deepEqual(result.board.sessions[0].weeks, [2, 3]);
  assert.deepEqual(result.board.sessions[1], b.sessions[1]);
  assert.equal(result.board.courses.length, 1);
  assert.match(result.text, /第 1 周/);
  assert.throws(() => applyTimetableAction(b, context(b), deletion({ target: { name: '工科数学分析', day: 6 } }), 'wide'), /不能删除整门课/);
});
test('ambiguous deletion fails before mutation and can be narrowed by teacher', () => {
  const { board: b } = added(); b.courses[0].teacher = '甲';
  b.courses.push({ ...b.courses[0], id: 'other', teacher: '乙' });
  b.sessions.push({ ...b.sessions[0], id: 'other-s', courseId: 'other' });
  assert.throws(() => applyTimetableAction(b, context(b), deletion(), 'd'), /同名课程/);
  const result = applyTimetableAction(b, context(b), deletion({ target: { name: '工科数学分析', teacher: '乙' } }), 'd');
  assert.equal(result.board.courses.length, 1); assert.equal(result.board.courses[0].teacher, '甲');
  assert.throws(() => applyTimetableAction(b, context(b), deletion({ target: { name: '不存在' } }), 'x'), /没有找到/);
});
test('concurrent edits invalidate both pending actions and unsafe undo', () => {
  const { board: b } = added(), ctx = context(b); b.courses[0].note = '手动编辑';
  assert.throws(() => applyTimetableAction(b, ctx, deletion(), 'stale'), /发生变化/);
  assert.throws(() => undoTimetableAction(b, 'add-1'), /后续修改/);
});
test('operations use JSON output and limited context while normal chat never receives the board', () => {
  const b = empty(); b.secretAccount = 'private-account'; b.aiLastOperation = { secret: 'undo-backup' };
  const payload = { apiKey: 'test-key', messages: [{ role: 'user', content: '添加课' }], context: context(b), mode: 'timetable' };
  const request = buildUpstreamRequest(payload);
  assert.deepEqual(request.body.response_format, { type: 'json_object' }); assert.equal(request.body.stream, false);
  assert.match(request.body.messages[0].content, /defaultWeeks.*ask/s);
  assert.ok(!JSON.stringify(request).includes('private-account')); assert.ok(!JSON.stringify(request).includes('undo-backup'));
  const normal = buildUpstreamRequest({ ...payload, mode: 'chat' });
  assert.equal(normal.body.response_format, undefined); assert.ok(!JSON.stringify(normal).includes('firstMonday'));
  const recovery = buildUpstreamRequest({ ...payload, repairEmpty: true, config: {thinking:true} }).body;
  assert.equal(recovery.response_format,undefined);
  assert.equal(recovery.tool_choice.function.name,'submit_timetable_decision');
  assert.equal(recovery.tools.length,1);
  assert.deepEqual(recovery.thinking,{type:'disabled'});
  assert.equal(buildUpstreamRequest({...payload,config:{thinking:true}}).body.thinking.type,'enabled');
  assert.equal(buildUpstreamRequest({...payload,mode:'chat',repairEmpty:true}).body.tools,undefined);
});
