import test from 'node:test';
import assert from 'node:assert/strict';
import { loadSource } from '../source.mjs';
import { weekOf, addDays, sessionsOn, assignColors, statusOf, parseWeeks, weeksLabel, visibleSessions, weekPresets, sessionTimeHint } from '../public/model.js';

test('repository fixture retains two separate sessions for the same course', async () => {
  const { board } = await loadSource();
  assert.equal(board.courses.length, 6);
  assert.equal(board.sessions.length, 7);
  const lab = board.courses.find(c => c.name === '通信电子线路实验');
  assert.equal(board.sessions.filter(s => s.courseId === lab.id).length, 2);
  assert.equal(sessionsOn(board, '2026-09-25').length, 2);
  assert.equal(sessionsOn(board, '2026-09-27').length, 0);
  assert.equal(sessionsOn(board, '2026-11-08').length, 2);
});
test('week boundaries, weeks input and course colors are deterministic', async () => {
  const { board } = await loadSource();
  assert.equal(weekOf(board.term, '2026-09-27'), 1);
  assert.equal(weekOf(board.term, '2026-09-28'), 2);
  assert.equal(addDays('2026-12-31', 1), '2027-01-01');
  assert.deepEqual(parseWeeks('1-3，5,7~8'), [1, 2, 3, 5, 7, 8]);
  assert.equal(weeksLabel([8, 1, 3, 2, 7, 5]), '1-3,5,7-8');
  assert.deepEqual(parseWeeks('7-2'), []);
  assert.deepEqual(parseWeeks('1-60000'), []);
  assert.deepEqual(parseWeeks('1-3,无效,5，7-2'), [1, 2, 3, 5]);
  assert.deepEqual(assignColors(board.courses), assignColors([...board.courses].reverse()));
  assert.equal(new Set(Object.values(assignColors(board.courses))).size, board.courses.length);
});

test('week presets use total semester weeks, including odd-length terms', () => {
  const presets = weekPresets(17);
  assert.deepEqual(presets.find(p => p.id === 'odd').weeks, [1, 3, 5, 7, 9, 11, 13, 15, 17]);
  assert.deepEqual(presets.find(p => p.id === 'even').weeks, [2, 4, 6, 8, 10, 12, 14, 16]);
  assert.equal(weeksLabel(presets.find(p => p.id === 'first').weeks), '1-8');
  assert.equal(weeksLabel(presets.find(p => p.id === 'last').weeks), '9-17');
  assert.equal(weeksLabel(weekPresets(18)[0].weeks), '1-18');
  assert.ok(!weekPresets(1).some(p => p.id === 'even'));
});

test('time hints follow custom periods and tolerate incomplete editing input', () => {
  const times = [{ index: 1, begin: '08:30', end: '09:15' }, { index: 2, begin: '09:30', end: '10:15' }];
  assert.deepEqual(sessionTimeHint({ start: '1', end: '2' }, times), { valid: true, text: '按当前作息：08:30 – 10:15' });
  for (const s of [{ start: '', end: '2' }, { start: '2', end: '1' }, { start: '0', end: '2' }, { start: '1.5', end: '2' }, { start: '1', end: '3' }]) assert.equal(sessionTimeHint(s, times).valid, false);
});
test('theme and period values come from Android source; status matches end boundary', async () => {
  const { theme, periods, board } = await loadSource();
  assert.equal(theme.colors.light.primary, '#4C7D2C');
  assert.equal(theme.palette.length, 16);
  assert.equal(periods.length, 13);
  assert.equal(periods[2].begin, '09:50');
  const s = sessionsOn(board, '2026-09-25')[0];
  assert.equal(statusOf(s, periods, '09:49'), 'upcoming');
  assert.equal(statusOf(s, periods, '09:50'), 'ongoing');
  assert.equal(statusOf(s, periods, '12:15'), 'ongoing');
  assert.equal(statusOf(s, periods, '12:16'), 'finished');
});
test('ghost classes never cover a current-week class', () => {
  const board = { showOutOfWeek: true, sessions: [
    { id: 'active', day: 1, start: 3, end: 5, weeks: [1] },
    { id: 'overlap', day: 1, start: 4, end: 6, weeks: [2] },
    { id: 'ghost', day: 2, start: 4, end: 6, weeks: [2] },
  ] };
  assert.deepEqual(visibleSessions(board, 1).map(s => s.id), ['ghost', 'active']);
});
