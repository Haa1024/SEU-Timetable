export const dayNames = ['一', '二', '三', '四', '五', '六', '日'];
export const toDate = value => new Date(`${value}T12:00:00Z`);
export const dateString = date => date.toISOString().slice(0, 10);
export const addDays = (value, n) => dateString(new Date(toDate(value).getTime() + n * 86400000));
export const dayOf = value => toDate(value).getUTCDay() || 7;
export const weekOf = (term, value) => Math.trunc((toDate(value) - toDate(term.firstMonday)) / 86400000 / 7) + 1;
export const minuteOf = value => { const [h, m] = value.split(':').map(Number); return h * 60 + m; };
export const periodLabel = s => `第 ${s.start}–${s.end} 节`;
export const timeRange = (s, periods) => `${periods.find(p => p.index === s.start)?.begin ?? '?'} – ${periods.find(p => p.index === s.end)?.end ?? '?'}`;
export function sessionsOn(board, date) {
  const week = weekOf(board.term, date);
  return board.sessions.filter(s => s.day === dayOf(date) && s.weeks.includes(week)).sort((a, b) => a.start - b.start);
}
export function statusOf(session, periods, time) {
  const first = periods.find(p => p.index === session.start), last = periods.find(p => p.index === session.end);
  if (!first || !last) return 'upcoming';
  const now = minuteOf(time);
  return now < minuteOf(first.begin) ? 'upcoming' : now > minuteOf(last.end) ? 'finished' : 'ongoing';
}
export function assignColors(courses, size = 16) {
  const used = new Set(), result = {};
  for (const c of courses) if (c.colorOverride != null) { result[c.id] = ((c.colorOverride % size) + size) % size; used.add(result[c.id]); }
  for (const c of [...courses].filter(c => c.colorOverride == null).sort((a, b) => a.name < b.name ? -1 : a.name > b.name ? 1 : a.id < b.id ? -1 : 1)) {
    let hash = 0;
    for (let i = 0; i < c.name.length; i++) hash = (Math.imul(hash, 31) + c.name.charCodeAt(i)) | 0;
    let slot = ((hash % size) + size) % size, probe = 0;
    while (used.has(slot) && probe++ < size) slot = (slot + 1) % size;
    used.add(slot); result[c.id] = slot;
  }
  return result;
}
export function parseWeeks(input) {
  const weeks = new Set();
  for (const part of input.replaceAll('，', ',').split(',')) {
    const match = part.trim().match(/^\+?(\d+)(?:\s*[-~—]\s*\+?(\d+))?$/);
    // Course.kt ignores invalid segments and retains the valid weeks.
    if (!match) continue;
    const start = +match[1], end = +(match[2] || match[1]);
    if (start < 1 || end > 60 || end < start) continue;
    for (let i = start; i <= end; i++) weeks.add(i);
  }
  return [...weeks].sort((a, b) => a - b);
}
export function weekPresets(totalWeeks) {
  const all = Array.from({ length: totalWeeks }, (_, i) => i + 1);
  return [
    { id: 'all', label: '全学期', weeks: all },
    { id: 'odd', label: '单周', weeks: all.filter(w => w % 2 === 1) },
    { id: 'even', label: '双周', weeks: all.filter(w => w % 2 === 0) },
    { id: 'first', label: '前半学期', weeks: all.filter(w => w <= Math.max(1, Math.floor(totalWeeks / 2))) },
    { id: 'last', label: '后半学期', weeks: all.filter(w => w > Math.floor(totalWeeks / 2)) },
  ].filter(p => p.weeks.length);
}
export function sessionTimeHint(session, periods) {
  const fromText = String(session.start).trim(), toText = String(session.end).trim();
  const from = /^\+?\d+$/.test(fromText) ? Number(fromText) : null;
  const to = /^\+?\d+$/.test(toText) ? Number(toText) : null;
  if (from === null || to === null) return { valid: false, text: '填好起止节次后，这里会显示对应的时刻' };
  const first = periods.find(p => p.index === from), last = periods.find(p => p.index === to);
  if (!first || !last || from > to) return { valid: false, text: `起止节次填写有误，无法计算时刻（一天共 ${periods.length} 节）` };
  return { valid: true, text: `按当前作息：${first.begin} – ${last.end}` };
}
export function weeksLabel(weeks) {
  const values = [...new Set(weeks)].sort((a, b) => a - b), chunks = [];
  for (let i = 0; i < values.length; i++) {
    const start = values[i];
    while (values[i + 1] === values[i] + 1) i++;
    chunks.push(start === values[i] ? String(start) : `${start}-${values[i]}`);
  }
  return chunks.join(',');
}
export function visibleSessions(board, week) {
  const active = board.sessions.filter(s => s.weeks.includes(week));
  const ghosts = board.showOutOfWeek ? board.sessions.filter(s => !s.weeks.includes(week) && !active.some(a => a.day === s.day && a.start <= s.end && a.end >= s.start)) : [];
  return [...ghosts.map(s => ({ ...s, ghost: true })), ...active];
}
