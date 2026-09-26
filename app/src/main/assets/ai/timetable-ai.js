import { dayNames, weeksLabel, toDate, dateString, addDays, dayOf } from './model.js';

const fail = message => { throw new Error(message); };
const plain = value => value && typeof value === 'object' && !Array.isArray(value);
const actionTypes = ['add', 'delete', 'metadata', 'change', 'batch', 'import_preview'];
function keys(value, allowed) {
  if (!plain(value) || Object.keys(value).some(key => !allowed.includes(key))) fail('模型返回了不支持的操作字段，课表未修改');
}
function text(value, label, required = false, max = 200) {
  if (typeof value !== 'string' || value.length > max || (required && !value.trim())) fail(`${label}无效，请补充后重试`);
  return value.trim();
}
function integer(value, min, max, label) {
  if (!Number.isInteger(value) || value < min || value > max) fail(`${label}应在 ${min}–${max} 之间`);
  return value;
}
const normalized = value => String(value).normalize('NFKC').replace(/\s/g, '').toLowerCase();
const pick = (obj, names) => Object.fromEntries(names.filter(name => obj[name] !== undefined).map(name => [name, obj[name]]));
function dateFact(term, date) {
  if (typeof date !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(date) || !Number.isFinite(toDate(date).getTime()) || dateString(toDate(date)) !== date) fail('日期无效，请使用真实的年月日');
  const week = Math.floor((toDate(date) - toDate(term.firstMonday)) / 86400000 / 7) + 1;
  return { date, day: dayOf(date), week, withinTerm: week >= 1 && week <= term.totalWeeks };
}
function calendarContext(term, date) {
  const monday = addDays(date, 1-dayOf(date)), fact = d=>dateFact(term,d);
  return { today: fact(date), tomorrow: fact(addDays(date,1)), dayAfterTomorrow: fact(addDays(date,2)), yesterday: fact(addDays(date,-1)),
    thisWeek: Array.from({length:7},(_,i)=>fact(addDays(monday,i))), nextWeek: Array.from({length:7},(_,i)=>fact(addDays(monday,7+i))) };
}

// A whitelisted snapshot: never include account, credentials, app state or operation backups.
export function boardSnapshot(board) {
  if (!board) fail('请先创建或选择一张课表');
  return structuredClone({ id: board.id, name: board.name, term: pick(board.term, ['firstMonday', 'totalWeeks', 'lastTeachingWeek']),
    courses: board.courses.map(c => pick(c, ['id', 'name', 'teacher', 'credit', 'note', 'code', 'classNo', 'colorOverride'])),
    sessions: board.sessions.map(s => pick(s, ['id', 'courseId', 'day', 'start', 'end', 'weeks', 'room'])),
    unplaced: (board.unplaced || []).map(c => pick(c, ['id', 'name', 'teacher'])) });
}
export function operationContext(board, periods, date, viewedWeek, editableCourseIds = [], defaultWeeks = 'ask') {
  return { board: boardSnapshot(board), periods: periods.map(p => pick(p, ['index', 'begin', 'end'])), date,
    currentWeek: dateFact(board.term,date).week, calendar: calendarContext(board.term,date), viewedWeek, defaultWeeks,
    editableCourseIds: editableCourseIds.filter(id => board.courses.some(c => c.id === id)) };
}
export function validateContext(input) {
  if (!plain(input) || !plain(input.board) || !Array.isArray(input.board.courses) || !Array.isArray(input.board.sessions) || !Array.isArray(input.periods)) fail('课表上下文格式无效');
  if (JSON.stringify(input).length > 250000 || input.board.courses.length > 500 || input.board.sessions.length > 2000) fail('当前课表过大，请缩小课表后重试');
  integer(input.board.term?.totalWeeks, 1, 60, '总周数');
  if (!/^\d{4}-\d{2}-\d{2}$/.test(input.date) || !Array.isArray(input.editableCourseIds)) fail('日期或课程上下文无效');
  if (!['term', 'viewed', 'ask'].includes(input.defaultWeeks)) fail('默认周次设置无效');
  return operationContext(input.board, input.periods, input.date, input.viewedWeek, input.editableCourseIds, input.defaultWeeks);
}
export function parseDecision(value) {
  let decision;
  try { decision = typeof value === 'string' ? JSON.parse(value) : value; }
  catch { fail('模型未返回完整的操作格式，课表未修改。请重试'); }
  // Some compatible models return the action object without its reply wrapper.
  // Normalize only known actions; their full fields are still validated below.
  if (plain(decision) && actionTypes.includes(decision.type)) decision = { reply: '处理课表请求', action: decision };
  keys(decision, ['reply', 'action']);
  text(decision.reply, '回复', true, 6000);
  if (decision.action !== null && !plain(decision.action)) fail('模型没有返回有效操作，课表未修改');
  if (decision.action && !actionTypes.includes(decision.action.type)) fail('不支持此操作类型，课表未修改');
  return decision;
}
function validWeeks(value, max) {
  if (!Array.isArray(value) || !value.length || value.length > 60) fail('请提供有效的上课周次');
  return [...new Set(value.map(w => integer(w, 1, max, '周次')))].sort((a, b) => a - b);
}
function metadata(value) {
  const result = {};
  if ('teacher' in value) result.teacher = text(value.teacher, '老师姓名');
  if ('room' in value) result.room = text(value.room, '教室');
  if ('note' in value) result.note = text(value.note, '备注', false, 2000);
  if ('credit' in value) {
    if (value.credit !== '' && (typeof value.credit !== 'number' || !Number.isFinite(value.credit) || value.credit < 0 || value.credit > 100)) fail('学分应为 0–100 的数字');
    result.credit = value.credit;
  }
  return result;
}
const sessionDescription = s => `周${dayNames[s.day - 1]}第 ${s.start}–${s.end} 节 · 第 ${weeksLabel(s.weeks)} 周${s.room ? ` · ${s.room}` : ''}`;
function boardData(board) { return structuredClone({ courses: board.courses, sessions: board.sessions, unplaced: board.unplaced || [] }); }
export const boardVersion = board => JSON.stringify(boardSnapshot(board));

// Dates are model-selected facts, not keyword matches against user messages.
// Resolve their arithmetic locally and reject contradictory date/week outputs.
function normalizeActionDates(raw, board) {
  const action = structuredClone(raw);
  const resolve = (value, label) => {
    const fact = dateFact(board.term,value);
    if (!fact.withinTerm) fail(`${label}不在当前学期内，课表未修改`);
    return fact;
  };
  if (['delete','change'].includes(action.type) && plain(action.target) && action.target.date !== undefined) {
    if (action.scope !== 'sessions') fail('指定日期时只能操作该次课程，不能操作整门课');
    const fact = resolve(action.target.date,'原课程日期');
    if ((action.target.day !== undefined && action.target.day !== fact.day) || (action.weeks != null && (!Array.isArray(action.weeks) || action.weeks.length !== 1 || action.weeks[0] !== fact.week))) fail('原课程日期与星期、周次不一致，课表未修改');
    action.target.day=fact.day;action.weeks=[fact.week];delete action.target.date;
  }
  if (action.type==='change' && plain(action.to) && action.to.date !== undefined) {
    const fact=resolve(action.to.date,'目标日期');
    if ((action.to.day !== undefined && action.to.day !== fact.day) || (action.to.weeks !== undefined && (!Array.isArray(action.to.weeks) || action.to.weeks.length !== 1 || action.to.weeks[0] !== fact.week))) fail('目标日期与星期、周次不一致，课表未修改');
    action.to.day=fact.day;action.to.weeks=[fact.week];delete action.to.date;
  }
  return action;
}

export function recordOperation(original, next, action, requestId, summary, courseId, createdCourseIds = []) {
  next.aiRequestIds = [...(original.aiRequestIds || []), requestId].slice(-100);
  next.aiLastOperation = { id: requestId, before: boardData(original), afterVersion: boardVersion(next), summary, type: action.type, courseId };
  return { board: next, changed: true, text: summary, receipt: { id: requestId, boardId: original.id, courseId, type: action.type, createdCourseIds } };
}

// A change is a delete + insert on private copies. Only the completed board is
// handed to the storage bridge, so validation failures never leave a half-move.
function changeCourse(board, context, action, requestId) {
  keys(action, ['type', 'target', 'scope', 'weeks', 'to']);
  keys(action.to, ['name', 'courseId', 'teacher', 'credit', 'note', 'day', 'start', 'end', 'weeks', 'room']);
  const to = action.to;
  if (!Object.keys(to).length) fail('请说明要改成什么课程或调整到什么时间');
  const removed = applyTimetableAction(board, context, { reply: '移除原课次', action: { type: 'delete', target: action.target, scope: action.scope, weeks: action.weeks } }, `${requestId}:remove`);
  const original = board.courses.find(c => c.id === removed.receipt.courseId);
  const selected = board.sessions.filter(s => s.courseId === original.id).map(s => {
    const remaining = removed.board.sessions.find(t => t.id === s.id)?.weeks || [];
    return { ...s, weeks: s.weeks.filter(w => !remaining.includes(w)) };
  }).filter(s => s.weeks.length);
  if (!selected.length) fail('该课程没有可调整的上课时间，请先添加时间段');
  if (selected.length > 1 && (to.start !== undefined || to.end !== undefined) && new Set(selected.map(s => `${s.start}-${s.end}`)).size > 1) fail('匹配到多个不同时间段，请说明要调整哪一次，或分别说明各自的新时间');
  if (to.day !== undefined) integer(to.day, 1, 7, '目标星期');
  if (to.start !== undefined) integer(to.start, 1, context.periods.length, '目标起始节');
  if (to.end !== undefined) integer(to.end, 1, context.periods.length, '目标结束节');
  const destinationWeeks = to.weeks === undefined ? null : validWeeks(to.weeks, board.term.totalWeeks);
  const sourceWeeks = [...new Set(selected.flatMap(s => s.weeks))].sort((a,b) => a-b);
  if (destinationWeeks && selected.length > 1 && sourceWeeks.length !== destinationWeeks.length) fail('多时间段调课的目标周次数不一致，请分别说明每个时间段的目标周次');
  const fields = metadata(to);
  const name = to.name === undefined ? original.name : text(to.name, '新课程名称', true);
  let destination = original, createdCourseIds = [];
  if (to.courseId !== undefined) {
    text(to.courseId, '目标课程', true);
    destination = board.courses.find(c => c.id === to.courseId);
    if (!destination || (to.name !== undefined && normalized(destination.name) !== normalized(name))) fail('目标课程与当前课表不符，请重新明确要换成哪门课');
    if (['teacher', 'credit', 'note'].some(k => to[k] !== undefined)) fail('替换为已有课程时使用其原有老师、学分和备注，请勿同时改写整门目标课程的信息');
  } else if (normalized(name) !== normalized(original.name)) {
    if (board.courses.some(c => normalized(c.name) === normalized(name))) fail('课表中已有同名目标课程，请明确使用哪门现有课程，或使用不同名称新建');
    destination = { id: `ai-${requestId}`, name, teacher: '', credit: '', note: '', colorOverride: null, code: '', classNo: '', ...pick(fields, ['teacher', 'credit', 'note']) };
    createdCourseIds = [destination.id];
  } else if (['teacher', 'credit', 'note'].some(k => to[k] !== undefined)) {
    fail('调课会保留原课程信息；如需改成另一门课，请说明新课程名称');
  }
  const incoming = selected.map((s, i) => {
    const start = to.start ?? s.start, end = to.end ?? (to.start === undefined ? s.end : start + s.end - s.start);
    integer(end, start, context.periods.length, '目标结束节');
    const weeks = !destinationWeeks ? s.weeks : selected.length === 1 ? destinationWeeks : s.weeks.map(w => destinationWeeks[sourceWeeks.indexOf(w)]);
    return { ...s, id: `ai-session-${requestId}-${i}`, courseId: destination.id, day: to.day ?? s.day, start, end, weeks, room: fields.room ?? s.room };
  });
  if (destination.id === original.id && selected.every((s,i) => ['day','start','end','room'].every(k => s[k] === incoming[i][k]) && weeksLabel(s.weeks) === weeksLabel(incoming[i].weeks))) fail('目标与原课程安排相同，无需修改');
  const next = removed.board;
  const warnings = new Set();
  for (const s of incoming) {
    for (const t of next.sessions) {
      if (t.day !== s.day || t.start > s.end || t.end < s.start || !t.weeks.some(w => s.weeks.includes(w))) continue;
      if (t.courseId === s.courseId || normalized(board.courses.find(c => c.id === t.courseId)?.name) === normalized(destination.name)) fail('目标时段已有同名课程，未调整。请检查目标时间');
      warnings.add(board.courses.find(c => c.id === t.courseId)?.name || '其他课程');
    }
    next.sessions.push(s);
  }
  if (!next.courses.some(c => c.id === destination.id)) next.courses.push(structuredClone(destination));
  next.unplaced = next.unplaced.filter(c => c.id !== destination.id);
  // Merge disjoint weeks for identical slots, without merging teacher/course IDs.
  const merged = [];
  for (const s of next.sessions) {
    const existing = s.courseId === destination.id && merged.find(t => t.courseId === s.courseId && t.day === s.day && t.start === s.start && t.end === s.end && t.room === s.room);
    if (existing) existing.weeks = [...new Set([...existing.weeks, ...s.weeks])].sort((a,b)=>a-b);
    else merged.push(s);
  }
  next.sessions = merged;
  let summary = destination.id === original.id ? `已调整「${original.name}」的上课安排：` : `已将「${original.name}」的以下课次替换为「${destination.name}」：`;
  summary += `\n${selected.map((s,i) => `${sessionDescription(s)}\n→ ${sessionDescription(incoming[i])}`).join('\n')}`;
  if (warnings.size) summary += `\n时间冲突：与${[...warnings].map(n=>`「${n}」`).join('、')}重叠。`;
  if (createdCourseIds.length) {
    const missing = [['teacher','老师'],['credit','学分'],['note','备注']].filter(([k])=>destination[k] === '' || destination[k] === undefined).map(([,v])=>v);
    if (missing.length) summary += `\n\n新课程的${missing.join('、')}暂留空，需要补充吗？`;
  }
  return recordOperation(board, next, action, requestId, summary, destination.id, createdCourseIds);
}

export function applyTimetableAction(board, context, rawDecision, requestId) {
  const decision = parseDecision(rawDecision);
  if (!decision.action) return { board, changed: false, text: decision.reply };
  if (decision.action.type === 'import_preview') fail('图片清单需要用户确认导入方式，课表未修改');
  if (board.aiRequestIds?.includes(requestId)) fail('这条操作已经处理过，未重复执行');
  if (boardVersion(board) !== JSON.stringify(context.board)) fail('课表已发生变化，本次未执行。请基于最新课表重新发送');
  const action = normalizeActionDates(decision.action,board);
  if (action.type === 'change') return changeCourse(board, context, action, requestId);
  if (action.type === 'batch') {
    keys(action, ['type', 'actions']);
    if (!Array.isArray(action.actions) || !action.actions.length || action.actions.length > 10) fail('组合操作需要 1–10 个步骤');
    let working = structuredClone(board), created = [], summaries = [], lastCourseId, touched = new Set();
    for (const [i, step] of action.actions.entries()) {
      if (!plain(step) || !['add','delete','metadata','change'].includes(step.type)) fail('组合中包含不支持的步骤，整组操作未执行');
      const stepContext = { ...context, board: boardSnapshot(working), editableCourseIds: [...context.editableCourseIds, ...created] };
      const result = applyTimetableAction(working, stepContext, { reply: '组合步骤', action: step }, `${requestId}:${i}`);
      working = result.board; created.push(...result.receipt.createdCourseIds);
      // Intermediate clashes can disappear after a later move (e.g. swapping
      // two classes). Report conflicts only against the completed transaction.
      summaries.push(`${i+1}. ${result.text.split('\n').filter(line=>!line.startsWith('时间冲突：')).join('\n')}`);
      lastCourseId = result.receipt.courseId; touched.add(lastCourseId);
    }
    const conflicts = new Set();
    working.sessions.forEach((s,i)=>working.sessions.slice(i+1).forEach(t=>{
      if ((!touched.has(s.courseId) && !touched.has(t.courseId)) || s.day !== t.day || s.start > t.end || s.end < t.start || !s.weeks.some(w=>t.weeks.includes(w))) return;
      const names = [s,t].map(x=>working.courses.find(c=>c.id===x.courseId)?.name || '课程').sort();
      conflicts.add(names.map(n=>`「${n}」`).join('与'));
    }));
    const summary = `已完成 ${summaries.length} 项操作（可一起撤销）：\n${summaries.join('\n\n')}${conflicts.size ? `\n时间冲突：${[...conflicts].join('、')}仍有重叠。` : ''}`;
    return recordOperation(board, working, action, requestId, summary, lastCourseId, created.filter(id=>working.courses.some(c=>c.id===id)));
  }
  const next = structuredClone(board);
  let summary, courseId, warnings = [];
  if (action.type === 'add') {
    keys(action, ['type', 'name', 'teacher', 'credit', 'note', 'sessions']);
    const name = text(action.name, '课程名称', true), fields = metadata(action);
    if (!Array.isArray(action.sessions) || !action.sessions.length || action.sessions.length > 20) fail('请提供至少一个完整的上课时间段');
    const sessions = action.sessions.map(s => {
      keys(s, ['day', 'start', 'end', 'weeks', 'room']);
      const day = integer(s.day, 1, 7, '星期'), start = integer(s.start, 1, context.periods.length, '起始节'), end = integer(s.end, start, context.periods.length, '结束节');
      let weeks = s.weeks;
      if (weeks === null) {
        if (context.defaultWeeks === 'ask') fail('请先说明上课周次');
        weeks = context.defaultWeeks === 'viewed' ? [context.viewedWeek] : Array.from({ length: board.term.lastTeachingWeek || board.term.totalWeeks }, (_, i) => i + 1);
      }
      return { day, start, end, weeks: validWeeks(weeks, board.term.totalWeeks), room: s.room === undefined ? '' : text(s.room, '教室') };
    });
    for (const [i, s] of sessions.entries()) {
      if (sessions.slice(0, i).some(t => t.day === s.day && t.start <= s.end && t.end >= s.start && t.weeks.some(w => s.weeks.includes(w)))) fail('同一门课的时间段相互重叠，请明确上课时间');
      const clashes = board.sessions.filter(t => t.day === s.day && t.start <= s.end && t.end >= s.start && t.weeks.some(w => s.weeks.includes(w)));
      if (clashes.some(t => normalized(board.courses.find(c => c.id === t.courseId)?.name) === normalized(name))) fail('已有同名课程占用这个时段，未重复添加。请先查看现有课程');
      warnings.push(...clashes.map(t => board.courses.find(c => c.id === t.courseId)?.name).filter(Boolean));
    }
    courseId = `ai-${requestId}`;
    next.courses.push({ id: courseId, name, teacher: '', credit: '', note: '', colorOverride: null, code: '', classNo: '', ...fields });
    next.sessions.push(...sessions.map((s, i) => ({ ...s, id: `${courseId}|${i}`, courseId })));
    summary = `已添加「${name}」\n${sessions.map(sessionDescription).join('\n')}`;
    if (warnings.length) summary += `\n时间冲突：与${[...new Set(warnings)].map(n => `「${n}」`).join('、')}重叠。`;
    const missing = [['teacher', '授课老师'], ['credit', '学分'], ['note', '备注']].filter(([key]) => fields[key] === undefined || fields[key] === '').map(([, label]) => label);
    if (missing.length) summary += `\n\n还可以补充${missing.join('、')}，你要补充吗？也可以暂时留空。`;
  } else if (action.type === 'delete') {
    keys(action, ['type', 'target', 'scope', 'weeks']);
    keys(action.target, ['name', 'teacher', 'day', 'start', 'end']);
    const t = action.target, name = text(t.name, '要删除的课程名称', true);
    if (!['course', 'sessions'].includes(action.scope)) fail('请明确删除整门课还是指定上课时间');
    if (t.teacher !== undefined) text(t.teacher, '老师姓名');
    if (t.day !== undefined) integer(t.day, 1, 7, '星期');
    if (t.start !== undefined) integer(t.start, 1, context.periods.length, '起始节');
    if (t.end !== undefined) integer(t.end, t.start || 1, context.periods.length, '结束节');
    if (action.scope === 'course' && (action.weeks !== null || ['day', 'start', 'end'].some(k => t[k] !== undefined))) fail('带有时间限定时只能删除相应课次，不能删除整门课');
    const weeks = action.weeks === null ? null : validWeeks(action.weeks, board.term.totalWeeks);
    const matchesSession = s => ['day', 'start', 'end'].every(k => t[k] === undefined || s[k] === t[k]) && (!weeks || s.weeks.some(w => weeks.includes(w)));
    const candidates = board.courses.filter(c => normalized(c.name) === normalized(name) && (t.teacher === undefined || normalized(c.teacher) === normalized(t.teacher)) && (action.scope === 'course' || board.sessions.some(s => s.courseId === c.id && matchesSession(s))));
    if (!candidates.length) fail(`没有找到符合条件的「${name}」，课表未修改`);
    if (candidates.length !== 1) {
      const descriptions=candidates.map(c=>`「${c.name}」· ${c.teacher || '未填写老师'} · ${board.sessions.filter(s=>s.courseId===c.id && (action.scope==='course'||matchesSession(s))).map(sessionDescription).join('；')}`);
      throw Object.assign(new Error(`找到 ${candidates.length} 门同名课程：\n${descriptions.join('\n')}\n你要操作哪一门？请补充老师或具体上课时间。`),{ clarification:true });
    }
    const course = candidates[0]; courseId = course.id;
    const affected = board.sessions.filter(s => s.courseId === courseId && (action.scope === 'course' || matchesSession(s)));
    if (action.scope === 'course') {
      next.courses = next.courses.filter(c => c.id !== courseId);
      next.sessions = next.sessions.filter(s => s.courseId !== courseId);
      next.unplaced = next.unplaced.filter(c => c.id !== courseId);
      summary = `已删除整门「${course.name}」及其 ${affected.length} 个时间段。`;
    } else {
      if (!affected.length) fail('没有找到符合条件的课次，课表未修改');
      next.sessions = next.sessions.flatMap(s => {
        if (!affected.some(a => a.id === s.id)) return [s];
        const remaining = weeks ? s.weeks.filter(w => !weeks.includes(w)) : [];
        return remaining.length ? [{ ...s, weeks: remaining }] : [];
      });
      if (!next.sessions.some(s => s.courseId === courseId)) {
        next.courses = next.courses.filter(c => c.id !== courseId);
        next.unplaced = next.unplaced.filter(c => c.id !== courseId);
      }
      summary = `已删除「${course.name}」的以下课次：\n${affected.map(s => sessionDescription({ ...s, weeks: weeks ? s.weeks.filter(w => weeks.includes(w)) : s.weeks })).join('\n')}`;
    }
  } else {
    keys(action, ['type', 'courseId', 'teacher', 'credit', 'note', 'room']);
    if (!context.editableCourseIds.includes(action.courseId)) fail('本阶段只能补充当前对话中新建课程的信息');
    const course = next.courses.find(c => c.id === action.courseId);
    if (!course) fail('该课程已经不存在，请重新添加');
    const fields = metadata(action);
    if (!Object.keys(fields).length) fail('没有提供要补充的信息');
    courseId = course.id;
    const { room, ...courseFields } = fields;
    Object.assign(course, courseFields);
    if (room !== undefined) next.sessions.filter(s => s.courseId === courseId).forEach(s => { s.room = room; });
    summary = `已补充「${course.name}」：\n${Object.entries(fields).map(([k, v]) => `${{ teacher: '老师', credit: '学分', note: '备注', room: '教室' }[k]}：${v === '' ? '留空' : v}`).join('\n')}`;
  }
  return recordOperation(board, next, action, requestId, summary, courseId, action.type === 'add' ? [courseId] : []);
}
export function undoTimetableAction(board, id) {
  const last = board.aiLastOperation;
  if (!last || last.id !== id || boardVersion(board) !== last.afterVersion) fail('课表已发生后续修改，无法撤销这条操作');
  return { ...structuredClone(board), ...structuredClone(last.before), aiLastOperation: null };
}
