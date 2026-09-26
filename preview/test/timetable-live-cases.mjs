// Explicit opt-in: node preview/test/timetable-live-cases.mjs <key-file>
// Runs isolated real model cases, never reads or changes the user's browser data.
import { readFile, writeFile } from 'node:fs/promises';
import assert from 'node:assert/strict';
import { loadSource } from '../source.mjs';
import { operationContext, parseDecision, applyTimetableAction } from '../public/timetable-ai.js';

if (!process.argv[2]) throw new Error('Pass a local test key file path');
const apiKey = (await readFile(process.argv[2], 'utf8')).replace(/^\uFEFF/, '').trim();
const source = await loadSource();
const empty = { ...source.board, courses: [], sessions: [], unplaced: [] };
const config = { model: 'deepseek-flash', maxTokens: 1024, temperature: 0 };
const context = b => operationContext(b, source.periods, '2026-09-26', 1);
const results = [];
const naturalOnly = process.argv.includes('--natural-only');
async function ask(board, messages, mode = 'timetable') {
  const response = await fetch('http://127.0.0.1:4173/api/llm/chat', { method: 'POST', headers: { Origin: 'http://127.0.0.1:4173', 'Content-Type': 'application/json' },
    body: JSON.stringify({ config, apiKey, messages, mode, stream: false, ...(mode === 'timetable' ? { context: context(board) } : {}) }), signal: AbortSignal.timeout(180000) });
  assert.ok(response.ok, `HTTP ${response.status}`);
  const data = await response.json();
  assert.equal(data.choices[0].finish_reason, 'stop');
  return mode === 'timetable' ? parseDecision(data.choices[0].message.content) : data.choices[0].message.content;
}
const user = content => ({ role: 'user', content });
for (const [name, prompt] of naturalOnly ? [] : [
  ['missing-name', '帮我添加第1到16周，周六第1、2节，在礼东上课'],
  ['missing-time', '帮我添加工科数学分析，周次是第1到16周'],
  ['no-matching-course', '删除整门不存在的哲学课'],
]) {
  const answer = await ask(empty, [user(prompt)]);
  assert.equal(answer.action, null, name);
  results.push({ name, answer }); console.log(`PASS ${name}`);
}
const ambiguous = { ...structuredClone(empty), courses: [
  { id: 'c1', name: '英语', teacher: '甲老师', note: '', credit: '' },
  { id: 'c2', name: '英语', teacher: '乙老师', note: '', credit: '' },
], sessions: [
  { id: 's1', courseId: 'c1', day: 1, start: 1, end: 2, weeks: [1,2], room: '礼东' },
  { id: 's2', courseId: 'c2', day: 2, start: 3, end: 4, weeks: [1,2], room: '礼西' },
] };
if (!naturalOnly) {
const first = await ask(ambiguous, [user('删除英语课')]);
assert.equal(first.action, null); results.push({ name: 'ambiguous-name', answer: first }); console.log('PASS ambiguous-name');
const clarified = await ask(ambiguous, [user('删除英语课'), { role: 'assistant', content: first.reply }, user('删除乙老师的整门英语课，全部周次')]);
const executed = applyTimetableAction(ambiguous, context(ambiguous), clarified, 'live-clarify');
assert.deepEqual(executed.board.courses.map(c => c.id), ['c1']); assert.deepEqual(executed.board.sessions.map(s => s.id), ['s1']);
results.push({ name: 'clarified-delete', answer: clarified, result: executed.text }); console.log('PASS clarified-delete');
const normal = await ask(empty, [user('我要添加课程，该在哪里操作？')], 'chat');
assert.ok(normal.includes('课表操作'));
results.push({ name: 'normal-mode-guidance', answer: normal }); console.log('PASS normal-mode-guidance');
}

// No 添加/删除 keywords; the model must infer intent, resolve references and
// translate natural time phrases. The same deterministic executor applies it.
const naturalHistory = [user('礼拜六头两节以后要去礼东学工科数学分析，从第一周到第十六周，你帮我记到表里吧。')];
const naturalAdd = await ask(empty, naturalHistory);
let result = applyTimetableAction(empty, context(empty), naturalAdd, 'natural-add');
assert.equal(result.board.courses.length, 1); assert.equal(result.board.courses[0].name, '工科数学分析');
assert.equal(result.board.sessions[0].day, 6); assert.equal(result.board.sessions[0].start, 1); assert.equal(result.board.sessions[0].end, 2);
assert.deepEqual(result.board.sessions[0].weeks, Array.from({length:16},(_,i)=>i+1));assert.equal(result.board.sessions[0].room,'礼东');
results.push({name:'natural-add-without-keyword',answer:naturalAdd,result:result.text});console.log('PASS natural-add-without-keyword');
naturalHistory.push({role:'assistant',content:result.text},user('刚才那门课这周不用去了，帮我把那次划掉，后面的周照旧。'));
const naturalDelete = await ask(result.board, naturalHistory);
result = applyTimetableAction(result.board, context(result.board), naturalDelete, 'natural-occurrence');
assert.equal(result.board.courses.length,1);assert.deepEqual(result.board.sessions[0].weeks,Array.from({length:15},(_,i)=>i+2));
results.push({name:'natural-reference-single-occurrence',answer:naturalDelete,result:result.text});console.log('PASS natural-reference-single-occurrence');
naturalHistory.push({role:'assistant',content:result.text},user('我决定这学期都不修它了，课表里别再留着它。'));
const naturalRemove = await ask(result.board,naturalHistory);
result=applyTimetableAction(result.board,context(result.board),naturalRemove,'natural-remove');
assert.equal(result.board.courses.length,0);assert.equal(result.board.sessions.length,0);
results.push({name:'natural-reference-whole-course',answer:naturalRemove,result:result.text});console.log('PASS natural-reference-whole-course');
const discuss = await ask(ambiguous,[user('先别动课表，我只想问问：如果以后不想上英语了，该怎么说比较清楚？')]);
assert.equal(discuss.action,null);results.push({name:'discussion-is-not-an-operation',answer:discuss});console.log('PASS discussion-is-not-an-operation');
await writeFile(new URL(naturalOnly ? '../.runtime/timetable-ai-natural-language.json' : '../.runtime/timetable-ai-live-cases.json', import.meta.url), JSON.stringify({ model: config.model, results }, null, 2));
