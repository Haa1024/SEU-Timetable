import { dayNames, toDate, dateString, addDays, dayOf, weekOf, minuteOf, periodLabel, timeRange, sessionsOn, statusOf, assignColors, parseWeeks, weeksLabel, visibleSessions, weekPresets, sessionTimeHint } from './model.js';
import { createAiChat } from './ai-chat.js';
import { operationContext, applyTimetableAction, undoTimetableAction, boardVersion } from './timetable-ai.js';
import { planTimetableImport } from './timetable-import.js';

const $ = selector => document.querySelector(selector);
const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const clone = value => structuredClone(value);
const storageKey = 'seu-browser-preview-v1';
const defaultState = () => ({ page: 'today', tab: 'today', history: [], date: '2026-09-25', time: '10:00', week: 1, theme: 'light', device: '384,854', activeId: 'fixture', boards: {}, removed: [], empty: false, unplacedHidden: false, reminder: false, advance: 10 });
let state;
try { state = { ...defaultState(), ...JSON.parse(localStorage.getItem(storageKey) || '{}') }; } catch { state = defaultState(); }
let source, draft, modalCallback, toastTimer, lastRevision;
const pageTitles = { today: '今日课程', timetable: '周课表', profile: '我的', detail: '课程详情', edit: '编辑课程', boards: '我的课表', newBoard: '导入 / 新建', settings: '课表设置', reminder: '上课提醒', account: '校园账号', help: '使用帮助', llm: 'AI 模型设置' };
const paths = {
  today: '<circle cx="12" cy="12" r="9" stroke-width="2"/><circle cx="12" cy="12" r="2.6" fill="currentColor" stroke="none"/>',
  timetable: '<g fill="currentColor" stroke="none"><rect x="4" y="7.5" width="16" height="3.4" rx="1.7"/><rect x="4" y="14" width="16" height="3.4" rx="1.7"/></g>',
  profile: '<g fill="currentColor" stroke="none"><circle cx="12" cy="8" r="3.6"/><rect x="5.5" y="13.5" width="13" height="8" rx="4"/></g>',
  back: '<path d="m14 5-7 7 7 7"/>', next: '<path d="m9 5 7 7-7 7"/>',
  plus: '<path d="M12 5v14M5 12h14"/>', close: '<path d="m6 6 12 12M6 18 18 6"/>',
  bell: '<g fill="currentColor" stroke="none"><rect x="6.5" y="6" width="11" height="10" rx="5.5"/><rect x="4.5" y="15.5" width="15" height="2.4" rx="1.2"/><circle cx="12" cy="20.5" r="1.6"/></g>',
  edit: '<path d="m14 5 5 5M4 20l5-1L21 7a2 2 0 0 0-5-5L4 14v6Z"/>',
  check: '<path d="m5 12 4 4L19 6"/>', settings: '<path d="M4 7h16M4 17h16"/><circle cx="9" cy="7" r="3"/><circle cx="16" cy="17" r="3"/>',
};
const icon = (name, size = 18) => `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${paths[name] || paths.today}</svg>`;
const btn = (action, label, className = '', extra = '') => `<button type="button" class="${className}" data-action="${action}" ${extra}>${label}</button>`;
const iconBtn = (action, name, label, soft = false) => btn(action, icon(name), `icon-button${soft ? ' soft' : ''}`, `aria-label="${esc(label)}" title="${esc(label)}"`);
const allBoards = () => [state.boards.fixture || source.board, ...Object.values(state.boards).filter(b => b.id !== 'fixture')].filter(b => !state.removed.includes(b.id));
const board = () => allBoards().find(b => b.id === state.activeId) || allBoards()[0];
const periods = () => board()?.schedule?.length ? board().schedule : source.periods;
const courseOf = id => board()?.courses.find(c => c.id === id);
const dark = () => state.theme === 'dark' || state.theme === 'system' && matchMedia('(prefers-color-scheme: dark)').matches;
const save = () => { try { localStorage.setItem(storageKey, JSON.stringify(state)); } catch { toast('浏览器存储不可用，刷新后修改可能丢失'); } };
function mutateBoard(fn) { const b = clone(board()); fn(b); state.boards[b.id] = b; save(); }
function colorStyle(course) {
  const slot = assignColors(board().courses)[course?.id] ?? 0;
  const color = source.theme.palette[slot];
  return `--course-bar:${color};--course-fill:color-mix(in srgb,${color} ${dark() ? '18%,#0d0f12' : '10%,white'});--course-text:color-mix(in srgb,${color} ${dark() ? '42%,white' : '60%,black'})`;
}
function toast(text) { clearTimeout(toastTimer); $('#toast').textContent = text; $('#toast').classList.add('show'); toastTimer = setTimeout(() => $('#toast').classList.remove('show'), 2800); }
function navigate(page, id) {
  if (state.page === 'edit' && page !== 'edit') draft = null;
  state.history.push({ page: state.page, courseId: state.courseId });
  state.page = page;
  if (id !== undefined) state.courseId = id;
  render();
}
function goBack() {
  const previous = state.history.pop();
  state.page = previous?.page || state.tab;
  state.courseId = previous?.courseId ?? state.courseId;
  draft = null;
  if (state.page === 'edit') state.page = state.tab;
  render();
}
function selectTab(tab) { state.page = tab; state.tab = tab; state.history = []; draft = null; render(); }
function modal(title, body, { confirm = '知道了', cancel, onConfirm, input, danger = false } = {}) {
  $('#modal-root').innerHTML = `<dialog aria-labelledby="modal-title"><h3 id="modal-title">${esc(title)}</h3><p>${esc(body)}</p>${input ? `<label class="form-field">${esc(input.label)}<input id="modal-input" type="${input.type || 'text'}" value="${esc(input.value || '')}" ${input.min ? `min="${input.min}" max="${input.max}"` : ''}></label>` : ''}<div class="modal-actions">${cancel ? btn('modal-cancel', esc(cancel)) : ''}${btn('modal-confirm', esc(confirm), danger ? 'danger-text' : '')}</div></dialog>`;
  modalCallback = onConfirm;
  const dialog = $('#modal-root dialog');
  dialog.showModal();
  positionDialog();
  if (input) { $('#modal-input').focus(); $('#modal-input').select(); }
  dialog.addEventListener('click', event => { if (event.target === dialog) { const r = dialog.getBoundingClientRect(); if (event.clientX < r.left || event.clientX > r.right || event.clientY < r.top || event.clientY > r.bottom) dialog.close(); } });
}
function positionDialog() {
  const dialog = $('#modal-root dialog[open]');
  if (!dialog) return;
  const rect = $('#phone').getBoundingClientRect(), scale = rect.width / $('#phone').clientWidth;
  Object.assign(dialog.style, { left: `${rect.left + rect.width / 2}px`, top: `${rect.top + rect.height / 2}px`, width: `${rect.width - 42 * scale}px`, maxHeight: `${rect.height - 80 * scale}px` });
}
function nativeFeature(label) { modal(label, '此操作需要 Android 系统或校园账号。浏览器预览仅展示界面，不会发起学校登录、同步、下载或系统权限申请。'); }
const navBar = (title, right = '') => `<header class="nav-bar">${iconBtn('back', 'back', '返回')}<h2>${esc(title)}</h2>${right}</header>`;
const section = (title, content, className = '') => `<div class="section-title">${esc(title)}</div><section class="card ${className}">${content}</section>`;
const setting = (title, subtitle, action, extra = '') => `<${action ? 'button type="button"' : 'div'} class="setting-row" ${action ? `data-action="${action}"` : ''} ${extra}><div><p>${esc(title)}</p>${subtitle ? `<small>${esc(subtitle)}</small>` : ''}</div></${action ? 'button' : 'div'}>`;
const field = (label, name, value, type = 'text', placeholder = '', extra = '') => `<label class="form-field">${esc(label)}<input name="${name}" type="${type}" value="${esc(value)}" placeholder="${esc(placeholder)}" ${extra}></label>`;
function tabs() { return `<nav class="tab-nav" aria-label="底部导航"><div class="tab-pill" style="--tab-index:${['today', 'timetable', 'profile'].indexOf(state.tab)}">${[['today', '今日'], ['timetable', '课表'], ['profile', '我的']].map(([id, label]) => btn('tab', `${icon(id)}<span>${label}</span>`, state.tab === id ? 'selected' : '', `data-tab="${id}" aria-label="${label}" aria-current="${state.tab === id ? 'page' : 'false'}"`)).join('')}</div></nav>`; }

function todayPage() {
  const b = board(), times = periods(), all = sessionsOn(b, state.date), remaining = all.filter(s => statusOf(s, times, state.time) !== 'finished');
  const tomorrow = sessionsOn(b, addDays(state.date, 1));
  const hero = remaining.find(s => statusOf(s, times, state.time) === 'ongoing') || remaining[0] || tomorrow[0];
  const isTomorrow = hero && !remaining.includes(hero), ongoing = hero && !isTomorrow && statusOf(hero, times, state.time) === 'ongoing';
  const date = toDate(state.date);
  const mood = all.length ? 'playful_all_done' : dayOf(state.date) >= 6 ? 'playful_weekend' : 'playful_no_class';
  const [face, emptyLine] = (source.playful[mood]?.[0] || '(・ω・)\n今天没有课').split('\n');
  let heroHtml;
  if (hero) {
    const start = minuteOf(times.find(p => p.index === hero.start)?.begin || '00:00');
    const end = minuteOf(times.find(p => p.index === hero.end)?.end || '23:59');
    heroHtml = `<button class="card hero" data-action="course" data-id="${esc(hero.courseId)}"><div class="between"><span class="tag">${isTomorrow ? '明天' : ongoing ? '进行中' : '即将开始'}</span><span class="small muted">${isTomorrow ? '明天第一节' : ongoing ? '正在上课' : `还有 ${start - minuteOf(state.time)} 分钟`}</span></div><h3>${esc(courseOf(hero.courseId)?.name)}</h3><div class="hero-meta"><span>${timeRange(hero, times)}</span><span>${periodLabel(hero)}</span>${hero.room ? `<span>${esc(hero.room)}</span>` : ''}</div>${ongoing ? `<div class="progress"><i style="width:${Math.max(0, Math.min(100, (minuteOf(state.time) - start) / (end - start) * 100))}%"></i></div>` : ''}</button>`;
  } else heroHtml = `<div class="card hero empty-hero"><span class="tag neutral">暂无后续课程</span><h3>${esc(face)}</h3><p class="subtitle">${esc(emptyLine)}</p></div>`;
  return `<div class="page-scroll"><header class="page-header"><div><h2>今日</h2><p class="subtitle">${date.getUTCMonth() + 1}月${date.getUTCDate()}日 星期${dayNames[dayOf(state.date) - 1]} · 第 ${weekOf(b.term, state.date)} 周</p></div>${iconBtn('reminder', 'bell', '上课提醒', true)}</header>${heroHtml}<div class="between section-row"><h3>今天的课</h3><span class="small muted">${remaining.length ? `共 ${new Set(remaining.map(s => s.courseId)).size} 门 · ${remaining.reduce((sum, s) => sum + s.end - s.start + 1, 0)} 节` : all.length ? '已全部上完' : '无'}</span></div><div class="stack">${remaining.map(s => `<button class="course-row" data-action="course" data-id="${esc(s.courseId)}" style="${colorStyle(courseOf(s.courseId))}"><span class="color-bar"></span><div class="course-info"><h3>${esc(courseOf(s.courseId)?.name)}</h3><p>${timeRange(s, times)}${s.room ? ` · ${esc(s.room)}` : ''}</p></div><span class="period">${periodLabel(s)}</span></button>`).join('') || `<div class="empty-copy"><h3>${esc(face)}</h3><p class="subtitle">${esc(emptyLine)}</p></div>`}</div></div>`;
}

function timetablePage() {
  const b = board(), times = periods(), currentWeek = weekOf(b.term, state.date), monday = addDays(b.term.firstMonday, (state.week - 1) * 7);
  const rowIndex = p => p + (p > b.term.morning ? 1 : 0) + (p > b.term.morning + b.term.afternoon ? 1 : 0);
  const rows = times.flatMap(p => [(p.index === b.term.morning + 1 || p.index === b.term.morning + b.term.afternoon + 1) ? '8px 62px' : '62px']).join(' ');
  const headers = `<div class="day-headers"><div></div>${dayNames.map((name, i) => { const d = toDate(addDays(monday, i)); return `<div class="day-header ${addDays(monday, i) === state.date ? 'current' : ''}"><span>周${name}</span><small>${d.getUTCMonth() + 1}.${d.getUTCDate()}</small></div>`; }).join('')}</div>`;
  const cells = times.map(p => `<div class="time-cell" style="grid-column:1;grid-row:${rowIndex(p.index)}"><span>${p.index}</span><small>${p.begin}</small><small>${p.end}</small></div>${dayNames.map((_, i) => `<div class="grid-cell ${state.week === currentWeek && dayOf(state.date) === i + 1 ? 'current' : ''}" style="grid-column:${i + 2};grid-row:${rowIndex(p.index)}"></div>`).join('')}`).join('');
  const lessons = visibleSessions(b, state.week).map(s => `<button class="course-block ${s.ghost ? 'ghost' : ''}" data-action="course" data-id="${esc(s.courseId)}" style="${colorStyle(courseOf(s.courseId))};grid-column:${s.day + 1};grid-row:${rowIndex(s.start)}/${rowIndex(s.end) + 1}" aria-label="${esc(courseOf(s.courseId)?.name)}，星期${dayNames[s.day - 1]}，${periodLabel(s)}">${s.ghost ? '<span class="ghost-note">[非本周]</span>' : ''}<span class="course-name">${esc(courseOf(s.courseId)?.name)}</span>${s.room ? `<span class="room">${esc(s.room)}</span>` : ''}</button>`).join('');
  return `<div class="week-page"><div class="week-top"><div class="between"><div class="board-title"><h2>${esc(b.name)}</h2>${btn('boards', '切换', 'switch-chip')}</div>${btn('add-course', icon('plus'), 'icon-button add-course', 'aria-label="新增课程"')}</div><p class="subtitle">${toDate(monday).getUTCMonth() + 1}/${toDate(monday).getUTCDate()} – ${toDate(addDays(monday, 6)).getUTCMonth() + 1}/${toDate(addDays(monday, 6)).getUTCDate()}</p><div class="week-switcher">${btn('prev-week', icon('back', 14), '', `aria-label="上一周" ${state.week <= 1 ? 'disabled' : ''}`)}${btn('jump-week', `第 ${state.week} 周`, 'week-label')}${btn('next-week', icon('next', 14), '', `aria-label="下一周" ${state.week >= b.term.totalWeeks ? 'disabled' : ''}`)}</div></div>${headers}<div class="grid-scroll"><div class="week-grid" style="grid-template-rows:${rows}">${cells}${lessons}</div></div>${!state.unplacedHidden && b.unplaced.length ? `<div class="unplaced"><span class="micro tertiary">未排课</span><span>${b.unplaced.map(c => esc(c.name)).join('、')}</span>${iconBtn('hide-unplaced', 'close', '关闭未排课提示')}</div>` : ''}</div>`;
}

function profilePage() {
  const b = board() || { name: '暂无课表', source: 'MANUAL', courses: [], sessions: [], unplaced: [] };
  return `<div class="page-scroll"><h2>我的</h2><div style="height:16px"></div><div class="card profile-card"><div class="avatar">${icon('profile', 26)}</div><div><h3>东南大学</h3><p>未登录校园账号（仅在导入课表时需要）</p></div></div>
    ${section('AI 助手', setting('AI 模型设置', aiChat.summary(), 'llm-settings'), 'setting-card')}
    ${section('课表数据', setting('我的课表', `${allBoards().length} 张 · 当前 ${b.name} · ${b.source === 'MANUAL' ? '自建' : '教务导入'} · ${b.courses.length} 门课`, 'boards') + setting('校园账号', '未保存校园账号', 'account') + setting('从教务同步当前课表', b.source === 'MANUAL' ? '自建课表无需教务同步' : '重新拉取该学期数据。不会自动同步，仅在你点击后执行', 'native-sync') + setting('上次导入', '浏览器演示数据'), 'setting-card')}
    ${section('当前课表统计', setting('课程', `${b.courses.length} 门`) + setting('时间块', `${b.sessions.length} 个（含同课多时段）`) + setting('未排课', `${b.unplaced.length} 门`), 'setting-card')}
    ${section('外观', '<p>主题</p>' + themeSegment())}
    ${section('桌面小组件', setting('添加到桌面', '不用打开 App 就能看见今天上什么，已上完的课会自动隐藏', 'native-widget'), 'setting-card')}
    ${section('帮助', setting('新手指引', '了解主要页面与入口', 'guide') + setting('使用帮助', '课表来源、登录与同步、界面操作与常见问题', 'help'), 'setting-card')}
    ${section('关于', setting('版本', source.version) + setting('检查更新', '从发布页获取最新版本', 'native-update') + setting('数据来源', 'ehall 教务系统 · 导入后存在本机') + setting('第三方库', 'OkHttp · kotlinx.serialization · Compose'), 'setting-card')}
  </div>`;
}
function themeSegment() { return `<div class="segmented">${[['system', '跟随系统'], ['light', '浅色'], ['dark', '深色']].map(([mode, label]) => btn('set-theme', label, state.theme === mode ? 'selected' : '', `data-mode="${mode}"`)).join('')}</div>`; }
function courseNav(title, editing = false) {
  return `<header class="nav-bar course-nav ${editing ? 'edit-nav' : 'detail-nav'}">${iconBtn('back', 'back', '返回')}<h2>${esc(title)}</h2>${editing ? btn('save-course', '保存', 'text-action', 'aria-label="顶部保存课程"') : '<span></span>'}</header>`;
}
function courseSwatches(selected, action, reset = false) {
  return `<div class="swatches course-swatches" data-scroll-key="course-colors" role="group" aria-label="课程颜色">${source.theme.palette.map((color, i) => btn(action, '', `swatch${selected === i ? ' selected' : ''}`, `style="--swatch:${color}" data-slot="${i}" aria-label="颜色 ${i + 1}" aria-pressed="${selected === i}"`)).join('')}${reset ? btn('auto-color', '恢复自动', 'restore-color') : ''}</div>`;
}
function detailPage() {
  const c = courseOf(state.courseId);
  if (!c) return courseNav('课程详情') + '<div class="page-scroll"><p>课程不存在，请返回课表。</p></div>';
  const sessions = board().sessions.filter(s => s.courseId === c.id).sort((a, b) => a.day - b.day || a.start - b.start);
  const weeks = [...new Set(sessions.flatMap(s => s.weeks))];
  const tag = c.credit !== '' && c.credit != null ? `${Number(c.credit)} 学分` : c.classNo || '课程';
  const info = (label, value) => `<div class="course-info-row"><span>${esc(label)}</span><span>${esc(value || '—')}</span></div>`;
  return `${courseNav('课程详情')}<div class="page-scroll course-detail-scroll">
    <div class="detail-hero course-hero" style="${colorStyle(c)}">
      <span class="tag">${esc(tag)}</span><h2>${esc(c.name)}</h2>
      ${c.teacher ? `<div class="teacher-row"><span class="teacher-avatar">${esc([...c.teacher][0])}</span><span>${esc(c.teacher)}</span></div>` : ''}
    </div>
    <div class="detail-content">
      <section class="card detail-info-card">${info('上课时间', sessions.map(s => `周${dayNames[s.day - 1]} ${periodLabel(s)}`).join('、'))}${info('上课地点', [...new Set(sessions.map(s => s.room).filter(Boolean))].join('、'))}${info('课程编号', c.code)}${c.note ? info('备注', c.note) : ''}</section>
      <section class="card"><div class="between"><h3>教学周分布</h3><span class="small muted">第 ${weeksLabel(weeks)} 周</span></div>
        <div class="week-bars" role="img" aria-label="教学周：${weeksLabel(weeks)} 周">${Array.from({ length: board().term.totalWeeks }, (_, i) => `<span class="${weeks.includes(i + 1) ? 'active' : ''}" title="第 ${i + 1} 周${weeks.includes(i + 1) ? '有课' : '无课'}"></span>`).join('')}</div>
      </section>
      <section class="card"><div class="between"><h3>课程颜色</h3><span class="small muted">${c.colorOverride == null ? '自动分配' : '已固定'}</span></div>
        ${courseSwatches(assignColors(board().courses)[c.id], 'course-color', c.colorOverride != null)}
        <p class="course-note">点击即生效，无需保存。同一门课在整张表内始终同色。</p>
      </section>
      ${c.note ? `<div><div class="section-title">备注</div><section class="card note-content">${esc(c.note)}</section></div>` : ''}
    </div>
  </div><footer class="course-action-bar detail-actions">${btn('delete-course', '删除', 'danger-button', 'aria-label="删除课程"')}${btn('edit-course', '编辑', 'primary-button', 'aria-label="编辑课程"')}</footer>`;
}

function makeDraft() {
  const c = courseOf(state.courseId);
  draft = c ? { ...clone(c), sessions: clone(board().sessions.filter(s => s.courseId === c.id)).map(s => ({ ...s, weeksText: weeksLabel(s.weeks) })) } : { id: null, name: '', teacher: '', credit: '', note: '', colorOverride: null, sessions: [{ day: 1, start: 1, end: 2, room: '', weeksText: '1-16' }] };
}
function readDraft() {
  const form = $('#course-form'); if (!form) return;
  const data = new FormData(form);
  for (const name of ['name', 'teacher', 'credit', 'note']) draft[name] = data.get(name) ?? '';
  draft.sessions = draft.sessions.map((s, i) => ({ ...s, day: +data.get(`day-${i}`), start: data.get(`start-${i}`), end: data.get(`end-${i}`), room: data.get(`room-${i}`), weeksText: data.get(`weeks-${i}`) }));
  saveDraftSession();
}
function saveDraftSession() { try { sessionStorage.setItem('seu-preview-draft', JSON.stringify(draft)); } catch {} }
function draftColorSlot() {
  if (draft.colorOverride != null) return draft.colorOverride;
  const slots = assignColors(board().courses);
  if (draft.id) return slots[draft.id] ?? 0;
  const used = new Set(Object.values(slots));
  return source.theme.palette.findIndex((_, i) => !used.has(i)) >= 0
    ? source.theme.palette.findIndex((_, i) => !used.has(i)) : board().courses.length % 16;
}
function sessionEditor(s, i) {
  const hint = sessionTimeHint(s, periods());
  const selectedWeeks = weeksLabel(parseWeeks(s.weeksText));
  return `<section class="card session-editor" data-session="${i}">
    <div class="between"><h3>时间段 ${i + 1}</h3>${draft.sessions.length > 1 ? btn('remove-session', '删除', 'delete-session', `data-index="${i}" aria-label="删除时间段 ${i + 1}"`) : ''}</div>
    <div class="weekday-field"><span class="small muted">星期</span><input type="hidden" name="day-${i}" value="${s.day}">
      <div class="weekday-chips" data-scroll-key="weekday-${i}" role="group" aria-label="时间段 ${i + 1} 的星期">${dayNames.map((name, d) => btn('session-day', `周${name}`, s.day === d + 1 ? 'selected' : '', `data-index="${i}" data-day="${d + 1}" aria-pressed="${s.day === d + 1}"`)).join('')}</div>
    </div>
    <div class="period-fields">${field('起始节', `start-${i}`, s.start, 'text', '1', 'inputmode="numeric"')}<span class="period-separator">–</span>${field('结束节', `end-${i}`, s.end, 'text', '2', 'inputmode="numeric"')}</div>
    ${field('周次', `weeks-${i}`, s.weeksText, 'text', '1-16')}
    <div class="week-presets" data-scroll-key="weeks-${i}" role="group" aria-label="时间段 ${i + 1} 的快捷周次">${weekPresets(board().term.totalWeeks).map(p => btn('session-weeks', p.label, weeksLabel(p.weeks) === selectedWeeks ? 'selected' : '', `data-index="${i}" data-preset="${p.id}" aria-pressed="${weeksLabel(p.weeks) === selectedWeeks}"`)).join('')}</div>
    ${field('教室', `room-${i}`, s.room, 'text', '可留空，例如 教二-301')}
    <p class="session-time-hint ${hint.valid ? 'valid' : ''}" data-time-hint="${i}" aria-live="polite">${esc(hint.text)}</p>
  </section>`;
}
function editPage() {
  if (!draft) { try { draft = JSON.parse(sessionStorage.getItem('seu-preview-draft')); } catch {} if (!draft) makeDraft(); }
  return `${courseNav(draft.id ? '编辑课程' : '新增课程', true)}<form id="course-form" class="page-scroll course-edit-scroll" novalidate>
    ${board().source !== 'MANUAL' ? `<section class="card import-notice"><h3>这是从教务导入的课表</h3><p>此处修改的是本机副本，教务系统中的数据不会被改动，课表也不会自行还原（App 不会自动同步）。仅在你主动点击「从教务同步」时，课程才会以教务为准重新拉取；颜色、学分、备注等教务没有的字段会保留。</p></section>` : ''}
    <div class="section-title">课程</div><section class="card course-fields">${field('课程名', 'name', draft.name, 'text', '例如 高等数学')}${field('授课教师', 'teacher', draft.teacher, 'text', '可留空')}</section>
    <div class="section-title">上课时间（可添加多个时间段）</div><div class="session-list">${draft.sessions.map(sessionEditor).join('')}${btn('add-session', '＋ 添加时间段', 'secondary-button add-session')}</div>
    <div class="section-title">本地信息</div><section class="card local-course-info">
      <p class="muted">颜色</p>${courseSwatches(draftColorSlot(), 'draft-color')}
      <p class="course-note color-note">${draft.colorOverride != null ? '该课程颜色已固定为你所选的颜色。' : '未选择时由算法分配：同一门课在整张表内始终同色，增删课程不会导致颜色错乱。'}</p>
      ${field('学分', 'credit', draft.credit, 'text', '例如 3 或 3.5', 'inputmode="decimal"')}
      <p class="course-note credit-note">课表接口不返回学分，填写后才会显示在课程详情中</p>
      <label class="form-field">备注<textarea name="note" placeholder="例如：带计算器、期末闭卷">${esc(draft.note)}</textarea></label>
    </section>
    <p id="form-error" class="form-error" role="alert" hidden></p>
    ${draft.id ? btn('delete-course', '删除这门课', 'danger-button edit-delete') : ''}
  </form><footer class="course-action-bar">${btn('save-course', '保存', 'primary-button', 'aria-label="保存课程"')}</footer>`;
}
function updateSessionFeedback() {
  for (const [i, s] of draft.sessions.entries()) {
    const hint = sessionTimeHint(s, periods()), label = $(`[data-time-hint="${i}"]`);
    if (label) { label.textContent = hint.text; label.classList.toggle('valid', hint.valid); }
    const selectedWeeks = weeksLabel(parseWeeks(s.weeksText));
    for (const button of document.querySelectorAll(`[data-action="session-weeks"][data-index="${i}"]`)) {
      const preset = weekPresets(board().term.totalWeeks).find(p => p.id === button.dataset.preset);
      const selected = selectedWeeks === weeksLabel(preset.weeks);
      button.classList.toggle('selected', selected); button.setAttribute('aria-pressed', String(selected));
    }
  }
}

function saveCourse() {
  readDraft();
  try {
    if (!draft.name.trim()) throw new Error('课程名不能为空');
    if (!draft.sessions.length) throw new Error('至少需要一个时间段，否则该课程不会出现在课表上');
    const sessions = draft.sessions.map((block, i) => {
      if (!/^\+?\d+$/.test(String(block.start).trim()) || !/^\+?\d+$/.test(String(block.end).trim())) throw new Error(`第 ${i + 1} 个时间段的节次填得不对：要填 1 到 ${periods().length} 之间的数字`);
      const s = { ...block, start: Number(String(block.start).trim()), end: Number(String(block.end).trim()) };
      if (!Number.isInteger(s.start) || !Number.isInteger(s.end) || s.start < 1 || s.end < s.start || s.end > periods().length) throw new Error(`时间段 ${i + 1}：节次应在 1–${periods().length} 之间，结束节不能早于起始节`);
      const weeks = parseWeeks(s.weeksText);
      if (!weeks.length) throw new Error(`第 ${i + 1} 个时间段的周次无法解析出有效范围，请写成 1-16 或 1,3,5-8 的形式`);
      return { ...s, weeks, room: s.room.trim() };
    });
    if (new Set(sessions.map(s => `${s.day}|${s.start}|${s.end}`)).size !== sessions.length) throw new Error('存在重复的上课时间段');
    const credit = String(draft.credit).trim();
    const id = draft.id || `m-${Date.now()}`;
    const c = { id, name: draft.name.trim(), teacher: draft.teacher.trim(), credit: credit && Number.isFinite(Number(credit)) ? Number(credit) : '', note: draft.note.trim(), colorOverride: draft.colorOverride, code: draft.code || '', classNo: draft.classNo || '' };
    mutateBoard(b => { b.courses = [...b.courses.filter(old => old.id !== id), c]; b.sessions = [...b.sessions.filter(s => s.courseId !== id), ...sessions.map((s, i) => { const { weeksText, ...rest } = s; return { ...rest, id: `${id}|b${i}`, courseId: id }; })]; });
    draft = null; sessionStorage.removeItem('seu-preview-draft'); state.courseId = id; selectTab('timetable'); toast('已保存到浏览器演示数据');
  } catch (error) { $('#form-error').hidden = false; $('#form-error').textContent = error.message; $('#form-error').scrollIntoView({ block: 'nearest' }); }
}

function boardsPage() {
  return `${navBar('我的课表', iconBtn('new-board', 'plus', '导入或新建课表'))}<div class="page-scroll subpage-scroll"><div class="stack">${allBoards().map(b => `<div class="card board-card ${b.id === state.activeId ? 'active' : ''}"><div class="between"><button class="board-name" data-action="activate-board" data-id="${esc(b.id)}">${esc(b.name)}</button>${b.id === state.activeId ? '<span class="tag">当前</span>' : ''}</div><p class="hint">${b.source === 'MANUAL' ? '自建课表' : b.source === 'COPY' ? '课表副本' : '教务导入'} · ${b.courses.length} 门课 · ${b.sessions.length} 个时间块</p><div class="board-actions">${btn('activate-board', '使用课表', '', `data-id="${esc(b.id)}"`)}${btn('board-settings', '设置', '', `data-id="${esc(b.id)}"`)}${btn('copy-board', '复制', '', `data-id="${esc(b.id)}"`)}${btn('delete-board', '删除', '', `data-id="${esc(b.id)}"`)}</div></div>`).join('')}<div class="spacer"></div>${btn('new-board', '＋ 导入 / 新建课表', 'primary-button')}</div><p class="preview-prompt">这里的切换、复制和删除只影响浏览器中的演示数据。</p></div>`;
}
function newBoardPage() {
  return `${navBar('导入 / 新建课表')}<div class="page-scroll subpage-scroll"><div class="card"><h3>从教务导入</h3><p class="help-paragraph">选择学期后，使用校园账号获取课表。浏览器中可加载仓库自带的测试样例体验导入后的界面。</p><div class="spacer"></div>${btn('import-fixture', '加载演示课表', 'primary-button')}<div style="height:10px"></div>${btn('native-import', '校园账号导入（安卓端）', 'secondary-button')}</div><div class="section-title">或创建一张空白课表</div><form id="board-form" class="card">${field('课表名称', 'boardName', '', 'text', '例如 我的自建课表', 'required')}${field('第一周周一', 'firstMonday', source.board.term.firstMonday, 'date')}${field('总周数', 'totalWeeks', 18, 'number', '', 'min="1" max="30"')}<p id="board-error" class="form-error" role="alert"></p>${btn('create-board', '创建课表', 'primary-button')}</form></div>`;
}
function settingsPage() {
  const b = board();
  return `${navBar('课表设置', btn('save-settings', '保存', 'text-action'))}<form id="settings-form" class="page-scroll subpage-scroll"><div class="card">${field('课表名称', 'name', b.name)}${b.source === 'MANUAL' ? `${field('第一周周一', 'firstMonday', b.term.firstMonday, 'date')}${field('总周数', 'totalWeeks', b.term.totalWeeks, 'number', '', 'min="1" max="30"')}` : `<p class="preview-prompt">学期开始：${b.term.firstMonday} · 共 ${b.term.totalWeeks} 周</p>`}</div>
    ${section('课表显示', `<button type="button" class="setting-row" data-action="toggle-ghost"><div><p>显示非本周课程</p><small>将其他周课程显示为淡色影子块</small></div><span class="toggle ${b.showOutOfWeek ? 'on' : ''}" role="switch" aria-checked="${b.showOutOfWeek}"></span></button>`)}
    ${section('作息时间', `<div class="between"><h3>每节课的起止时间</h3>${btn('reset-periods', '恢复默认', 'small muted')}</div>${periods().map(p => `<label class="lesson-time-row"><span>第 ${p.index} 节</span><input type="time" name="begin-${p.index}" value="${p.begin}" aria-label="第 ${p.index} 节开始时间"><span style="width:auto">–</span><input type="time" name="end-${p.index}" value="${p.end}" aria-label="第 ${p.index} 节结束时间"></label>`).join('')}`)}<p id="settings-error" class="form-error" role="alert"></p>${btn('save-settings', '保存设置', 'primary-button')}</form>`;
}
function reminderPage() {
  return `${navBar('上课提醒')}<div class="page-scroll subpage-scroll"><div class="native-note">以下开关用于预览设置界面。浏览器不会发送课程通知。</div><div class="card setting-card"><button class="setting-row" data-action="toggle-reminder"><div><p>上课提醒</p><small>在课程开始前提醒你</small></div><span class="toggle ${state.reminder ? 'on' : ''}" role="switch" aria-checked="${state.reminder}"></span></button></div>${section('提醒时间', `<p>提前多久提醒</p><div class="segmented">${[5, 10, 15, 30].map(n => btn('advance', `${n} 分钟`, state.advance === n ? 'selected' : '', `data-minutes="${n}"`)).join('')}</div>`)}${section('系统权限', setting('通知权限', '需在 Android 端允许通知', 'native-notification') + setting('精确闹钟权限', '用于在预定时间触发提醒', 'native-alarm'), 'setting-card')}</div>`;
}
function accountPage() {
  return `${navBar('校园账号')}<div class="page-scroll subpage-scroll"><div class="native-note">浏览器预览不收集校园账号密码。真实登录与凭据管理请在安卓应用中操作。</div><div class="card"><h3>未登录校园账号</h3><p class="help-paragraph">登录用于导入和同步课表。已经导入的课程保存在本地，离线也能继续查看。</p><div class="spacer"></div>${btn('native-login', '登录校园账号', 'primary-button')}</div></div>`;
}
function helpPage() {
  return `${navBar('使用帮助')}<div class="page-scroll subpage-scroll">${section('查看课程', '<p class="help-paragraph">「今日」显示正在进行和还没开始的课。「课表」按周展示课程，可点箭头或左右滑动切周，点击周数可以跳转。</p>')}${section('编辑与管理', '<p class="help-paragraph">点击课程块进入详情，可修改课程颜色或编辑课程时间。课表页右上角加号可新增课程，标题旁的「切换」进入课表管理。</p>')}${section('浏览器预览范围', '<p class="help-paragraph">主题、字号、圆角、作息和课程样例直接读取源码。页面结构与交互是浏览器实现，原生登录、通知、安装更新及桌面组件不在浏览器执行。编辑不会写回 Android 或教务系统。</p>')}</div>`;
}
function emptyPage() { return `<div class="empty-view"><div class="empty-icon">${icon('timetable', 32)}</div><h2>还没有课表</h2><p>从教务导入一张课表，<br>或创建自己的课程安排。</p>${btn('new-board', '导入 / 新建课表', 'primary-button')}${allBoards().length ? btn('leave-empty', '查看已有演示课表', 'secondary-button') : ''}</div>`; }

function applyTheme() {
  const phone = $('#phone');
  for (const [name, value] of Object.entries(source.theme.colors[dark() ? 'dark' : 'light'])) phone.style.setProperty(`--${name}`, value);
  for (const [name, value] of Object.entries(source.theme.typography)) { phone.style.setProperty(`--${name}-size`, `${value.size}px`); phone.style.setProperty(`--${name}-line`, `${value.lineHeight}px`); }
  for (const [name, value] of Object.entries(source.theme.radii)) phone.style.setProperty(`--radius-${name}`, `${value}px`);
  phone.style.colorScheme = dark() ? 'dark' : 'light';
}
function fitDevice() {
  const [width, height] = state.device.split(',').map(Number);
  const available = $('.device-stage').clientWidth - 24;
  const maxHeight = window.innerWidth <= 620 ? 800 : Math.max(500, window.innerHeight - ($('#sync-warning').hidden ? 178 : 245));
  const scale = Math.min(1, available / width, maxHeight / height);
  Object.assign($('#phone').style, { width: `${width}px`, height: `${height}px`, transform: `scale(${scale})` });
  Object.assign($('#device-shell').style, { width: `${width * scale}px`, height: `${height * scale}px` });
  positionDialog();
}
function fitCourseLabels() {
  for (const block of document.querySelectorAll('.course-block')) {
    const name = block.querySelector('.course-name');
    if (name) name.style.webkitLineClamp = String(Math.max(1, Math.min(8, Math.floor((block.clientHeight - 24 - (block.classList.contains('ghost') ? 12 : 0)) / 13))));
    const room = block.querySelector('.room');
    if (!room) continue;
    room.style.fontSize = `${source.theme.typography.gridRoom.size}px`;
    if (room.scrollWidth > room.clientWidth) room.style.fontSize = `${Math.max(5, source.theme.typography.gridRoom.size * room.clientWidth / room.scrollWidth)}px`;
  }
}
function updateShell() {
  $('#screen-title').textContent = pageTitles[state.page] || '东大课表';
  $('#page-links').innerHTML = [['today', '今日课程'], ['timetable', '周课表'], ['profile', '我的']].map(([id, label]) => `<button data-tab="${id}" class="${state.tab === id ? 'active' : ''}">${icon(id)}${label}<span class="chevron">${icon('next', 12)}</span></button>`).join('');
  $('#theme').value = state.theme; $('#device').value = state.device; $('#preview-date').value = state.date; $('#preview-time').value = state.time; $('#status-time').textContent = state.time;
  $('#source-status').textContent = `Theme.kt + PeriodTimes.kt + 接口样例 · ${source.board.courses.length} 门课程`;
  const warning = $('#sync-warning'); warning.hidden = !source.changedLayouts.length;
  warning.textContent = source.changedLayouts.length ? `检测到 Android 页面源码变化：${source.changedLayouts.map(p => p.split('/').pop()).join('、')}。浏览器不能直接运行 Compose，请同步预览布局后再核对；当前显示可能与安卓端不同。` : '';
}
function render(preserveScroll = false) {
  if (!source) return;
  if (!board() && !['newBoard', 'boards', 'profile', 'llm', 'timetable'].includes(state.page)) state.page = 'today';
  const horizontalScroll = preserveScroll ? [...document.querySelectorAll('[data-scroll-key]')].map(el => [el.dataset.scrollKey, el.scrollLeft]) : [];
  const oldScroll = preserveScroll ? $('.page-scroll')?.scrollTop || $('.grid-scroll')?.scrollTop || 0 : 0;
  applyTheme(); updateShell();
  $('#phone').classList.toggle('course-screen', ['detail', 'edit', 'llm'].includes(state.page));
  const main = ['today', 'timetable', 'profile'].includes(state.page);
  const pages = { today: todayPage, timetable: timetablePage, profile: profilePage, detail: detailPage, edit: editPage, boards: boardsPage, newBoard: newBoardPage, settings: settingsPage, reminder: reminderPage, account: accountPage, help: helpPage, llm: aiChat.settingsPage };
  const showEmpty = ['today', 'timetable'].includes(state.page) && (state.empty || !board());
  $('#app').innerHTML = (showEmpty ? emptyPage() : (pages[state.page] || todayPage)()) + (main ? tabs() : '');
  if (preserveScroll) { const area = $('.page-scroll') || $('.grid-scroll'); if (area) area.scrollTop = oldScroll; }
  for (const [key, left] of horizontalScroll) { const el = document.querySelector(`[data-scroll-key="${key}"]`); if (el) el.scrollLeft = left; }
  save(); fitDevice(); fitCourseLabels(); aiChat.updatePage(state.page);
}

$('#app').addEventListener('input', event => { if (event.target.closest('#course-form')) { readDraft(); updateSessionFeedback(); } });
$('#app').addEventListener('submit', event => { event.preventDefault(); if (event.target.id === 'course-form') saveCourse(); });
document.addEventListener('click', event => {
  const target = event.target.closest('[data-action]'); if (!target || target.disabled) return;
  const action = target.dataset.action, id = target.dataset.id;
  if (action.startsWith('native-')) return nativeFeature(target.textContent.trim());
  switch (action) {
    case 'tab': return selectTab(target.dataset.tab);
    case 'back': return goBack();
    case 'llm-settings': return navigate('llm');
    case 'course': return navigate('detail', id);
    case 'boards': case 'reminder': case 'account': case 'help': return navigate(action);
    case 'new-board': return navigate('newBoard');
    case 'edit-course': makeDraft(); saveDraftSession(); return navigate('edit');
    case 'add-course': state.courseId = null; makeDraft(); saveDraftSession(); return navigate('edit', null);
    case 'prev-week': state.week = Math.max(1, state.week - 1); return render(true);
    case 'next-week': state.week = Math.min(board().term.totalWeeks, state.week + 1); return render(true);
    case 'jump-week': return modal('跳转到第几周', `范围：1–${board().term.totalWeeks} 周`, { input: { label: '周数', value: state.week, type: 'number', min: 1, max: board().term.totalWeeks }, cancel: '取消', confirm: '跳转', onConfirm: value => { const n = Number(value); if (!Number.isInteger(n)) return toast('请输入整数周次'); state.week = Math.max(1, Math.min(board().term.totalWeeks, n)); render(true); } });
    case 'hide-unplaced': state.unplacedHidden = true; return render(true);
    case 'course-color': mutateBoard(b => { b.courses.find(c => c.id === state.courseId).colorOverride = +target.dataset.slot; }); return render(true);
    case 'auto-color': mutateBoard(b => { b.courses.find(c => c.id === state.courseId).colorOverride = null; }); return render(true);
    case 'session-day': readDraft(); draft.sessions[+target.dataset.index].day = +target.dataset.day; saveDraftSession(); return render(true);
    case 'session-weeks': readDraft(); draft.sessions[+target.dataset.index].weeksText = weeksLabel(weekPresets(board().term.totalWeeks).find(p => p.id === target.dataset.preset).weeks); saveDraftSession(); return render(true);
    case 'draft-color': readDraft(); draft.colorOverride = +target.dataset.slot; saveDraftSession(); return render(true);
    case 'add-session': readDraft(); draft.sessions.push({ day: 1, start: 1, end: 2, room: '', weeksText: '1-16' }); saveDraftSession(); return render(true);
    case 'remove-session': readDraft(); draft.sessions.splice(+target.dataset.index, 1); saveDraftSession(); return render(true);
    case 'save-course': return saveCourse();
    case 'delete-course': {
      const courseId = state.courseId, count = board().sessions.filter(s => s.courseId === courseId).length;
      return modal(`删除「${courseOf(courseId)?.name}」？`, `会把这门课和它的 ${count} 个时间段一起从本机删掉。${state.page === 'edit' ? (board().source !== 'MANUAL' ? '以后手动同步时它会被重新拉回来。' : '删除后无法恢复。') : ''}`, { confirm: '删除', cancel: '取消', danger: true, onConfirm: () => {
        mutateBoard(b => { b.courses = b.courses.filter(c => c.id !== courseId); b.sessions = b.sessions.filter(s => s.courseId !== courseId); });
        draft = null; sessionStorage.removeItem('seu-preview-draft'); selectTab('timetable');
      } });
    }
    case 'set-theme': state.theme = target.dataset.mode; return render(true);
    case 'toggle-reminder': state.reminder = !state.reminder; return render(true);
    case 'advance': state.advance = +target.dataset.minutes; return render(true);
    case 'activate-board': state.activeId = id; state.empty = false; state.week = Math.max(1, Math.min(board().term.totalWeeks, weekOf(board().term, state.date))); return selectTab('timetable');
    case 'copy-board': { const b = clone(allBoards().find(b => b.id === id)); b.id = `copy-${Date.now()}`; b.name += ' 副本'; b.source = 'COPY'; state.boards[b.id] = b; render(true); return toast('已复制演示课表'); }
    case 'delete-board': return modal('删除课表？', '将删除这张浏览器演示课表，可以通过左侧「重置演示数据」恢复。', { confirm: '删除', cancel: '取消', onConfirm: () => { state.removed.push(id); delete state.boards[id]; if (state.activeId === id) state.activeId = allBoards()[0]?.id; render(); } });
    case 'board-settings': state.activeId = id; return navigate('settings');
    case 'toggle-ghost': { mutateBoard(b => { b.showOutOfWeek = !b.showOutOfWeek; }); target.querySelector('.toggle').classList.toggle('on', board().showOutOfWeek); target.querySelector('.toggle').setAttribute('aria-checked', String(board().showOutOfWeek)); return; }
    case 'reset-periods': for (const p of source.periods) { const begin = $(`[name="begin-${p.index}"]`), end = $(`[name="end-${p.index}"]`); if (begin) begin.value = p.begin; if (end) end.value = p.end; } return;
    case 'save-settings': return saveSettings();
    case 'create-board': return createBoard();
    case 'import-fixture': { const b = clone(source.board); b.id = `demo-${Date.now()}`; state.boards[b.id] = b; state.activeId = b.id; state.empty = false; state.week = 1; selectTab('timetable'); return toast('已加载仓库课程样例'); }
    case 'leave-empty': state.empty = false; return selectTab('today');
    case 'guide': return modal('主要操作', '今日：查看当前和接下来的课。\n课表：切换周次，点击课程查看详情，右上角新增课程。\n我的：管理课表、账号和外观。\n左侧演示场景可快速查看上课中、无课和空白状态。');
    case 'modal-cancel': $('#modal-root dialog')?.close(); return;
    case 'modal-confirm': { const value = $('#modal-input')?.value; $('#modal-root dialog')?.close(); const callback = modalCallback; modalCallback = null; callback?.(value); return; }
  }
});

function createBoard() {
  const data = new FormData($('#board-form'));
  try {
    const name = data.get('boardName').trim(), monday = data.get('firstMonday'), weeks = +data.get('totalWeeks');
    if (!name) throw new Error('请填写课表名称');
    if (!monday || dayOf(monday) !== 1) throw new Error('第一周的开始日期必须是周一');
    if (!Number.isInteger(weeks) || weeks < 1 || weeks > 30) throw new Error('总周数应为 1–30');
    const id = `manual-${Date.now()}`;
    state.boards[id] = { id, name, source: 'MANUAL', term: { firstMonday: monday, totalWeeks: weeks, lastTeachingWeek: weeks, morning: 5, afternoon: 5, evening: 3 }, courses: [], sessions: [], unplaced: [], showOutOfWeek: false };
    state.activeId = id; state.empty = false; state.week = 1; selectTab('timetable'); toast('已创建空白演示课表');
  } catch (error) { $('#board-error').textContent = error.message; }
}
function saveSettings() {
  const data = new FormData($('#settings-form'));
  try {
    const name = data.get('name').trim(); if (!name) throw new Error('请填写课表名称');
    const schedule = periods().map(p => { const begin = data.get(`begin-${p.index}`), end = data.get(`end-${p.index}`); if (!begin || !end || minuteOf(end) <= minuteOf(begin)) throw new Error(`第 ${p.index} 节的结束时间必须晚于开始时间`); return { index: p.index, begin, end }; });
    const term = clone(board().term);
    if (board().source === 'MANUAL') { const monday = data.get('firstMonday'), weeks = +data.get('totalWeeks'); if (!monday || dayOf(monday) !== 1) throw new Error('学期开始日期必须是周一'); if (!Number.isInteger(weeks) || weeks < 1 || weeks > 30) throw new Error('总周数应为 1–30'); term.firstMonday = monday; term.totalWeeks = weeks; term.lastTeachingWeek = weeks; }
    mutateBoard(b => { b.name = name; b.schedule = schedule; b.term = term; }); state.week = Math.min(state.week, term.totalWeeks); goBack(); toast('已保存演示设置');
  } catch (error) { $('#settings-error').textContent = error.message; $('#settings-error').scrollIntoView({ block: 'nearest' }); }
}

$('#page-links').addEventListener('click', event => { const button = event.target.closest('[data-tab]'); if (button) selectTab(button.dataset.tab); });
$('#device').addEventListener('change', event => { state.device = event.target.value; save(); fitDevice(); fitCourseLabels(); });
$('#theme').addEventListener('change', event => { state.theme = event.target.value; render(true); });
$('#preview-date').addEventListener('change', event => { if (!event.target.value) return; state.date = event.target.value; if (board()) state.week = Math.max(1, Math.min(board().term.totalWeeks, weekOf(board().term, state.date))); render(true); });
$('#preview-time').addEventListener('change', event => { if (!event.target.value) return; state.time = event.target.value; render(true); });
$('.scenario-buttons').addEventListener('click', event => {
  const scenario = event.target.dataset.scenario; if (!scenario) return;
  if (scenario === 'empty') { state.empty = true; return selectTab('today'); }
  state.activeId = allBoards().find(b => b.id === 'fixture')?.id || allBoards()[0]?.id;
  if (!board()) { state.removed = state.removed.filter(id => id !== 'fixture'); state.activeId = 'fixture'; }
  state.empty = false;
  const term = board().term;
  state.date = addDays(term.firstMonday, scenario === 'week9' ? 60 : 4); state.time = scenario === 'done' ? '22:00' : '10:00'; state.week = scenario === 'week9' ? Math.min(9, term.totalWeeks) : 1;
  selectTab(scenario === 'week9' ? 'timetable' : 'today');
});
$('#reset').addEventListener('click', () => modal('重置演示数据？', '清除在浏览器中新增或修改的课程、课表和设置，重新加载仓库测试样例。不会影响安卓应用。', { confirm: '重置', cancel: '取消', onConfirm: () => { state = defaultState(); draft = null; sessionStorage.removeItem('seu-preview-draft'); render(); toast('已恢复演示数据'); } }));
let touchStart;
$('#app').addEventListener('pointerdown', event => { if (state.page === 'timetable') touchStart = { x: event.clientX, y: event.clientY }; });
$('#app').addEventListener('pointerup', event => {
  if (!touchStart || state.page !== 'timetable' || !board()) return;
  const dx = event.clientX - touchStart.x, dy = event.clientY - touchStart.y; touchStart = null;
  if (Math.abs(dx) > 56 && Math.abs(dx) > Math.abs(dy) * 1.4) { state.week = Math.max(1, Math.min(board().term.totalWeeks, state.week + (dx < 0 ? 1 : -1))); render(true); }
});
$('#app').addEventListener('pointercancel', () => { touchStart = null; });
window.addEventListener('resize', fitDevice);
window.addEventListener('scroll', positionDialog, true);
matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => { if (source) render(true); });
window.addEventListener('keydown', event => { if (event.key !== 'Escape' || $('#modal-root dialog[open]')) return; if (aiChat.isOpen()) aiChat.minimize(); else if (!['today', 'timetable', 'profile'].includes(state.page)) goBack(); });

// Commit course data and operation receipts in one localStorage write. A failed
// write or another tab's intervening change must not report a successful action.
function commitAiBoard(next, expectedState) {
  if (localStorage.getItem(storageKey) !== expectedState) throw new Error('另一个页面已修改数据，本次未执行，请刷新后重试');
  const nextState = { ...state, boards: { ...state.boards, [next.id]: next } };
  try { localStorage.setItem(storageKey, JSON.stringify(nextState)); }
  catch { throw new Error('本机存储写入失败，课表未修改。请检查浏览器存储空间后重试'); }
  state = nextState;
  render(true);
}
const timetableBridge = {
  context(editableIds, defaultWeeks) {
    if (state.empty || !board()) throw new Error('请先创建或选择一张课表');
    return operationContext(board(), periods(), state.date, state.week, editableIds, defaultWeeks);
  },
  execute(decision, context, requestId) {
    if (state.empty || board()?.id !== context.board.id) throw new Error('当前课表已切换，本次未执行，请重新发送');
    if (JSON.stringify(operationContext(board(), periods(), state.date, state.week).periods) !== JSON.stringify(context.periods)) throw new Error('作息时间已改变，本次未执行，请重新发送');
    const stored = localStorage.getItem(storageKey);
    if (stored !== JSON.stringify(state)) throw new Error('其他页面已修改课表，请刷新后重试');
    const result = applyTimetableAction(board(), context, decision, requestId);
    if (result.changed) commitAiBoard(result.board, stored);
    return result;
  },
  import(draft,context,strategy,requestId,previewOnly=false) {
    if(state.empty||board()?.id!==context.board.id)throw new Error('当前课表已切换，请返回原课表再确认');
    if(JSON.stringify(operationContext(board(),periods(),state.date,state.week).periods)!==JSON.stringify(context.periods))throw new Error('作息已改变，请重新核对清单');
    const stored=localStorage.getItem(storageKey);
    if(stored!==JSON.stringify(state))throw new Error('其他页面已修改课表，请刷新后重试');
    const result=planTimetableImport(board(),context,draft,strategy,requestId);
    if(!previewOnly&&result.changed)commitAiBoard(result.board,stored);
    return result;
  },
  undo(receipt) {
    if (board()?.id !== receipt.boardId || state.empty) throw new Error('请先切换回操作时的课表');
    const stored = localStorage.getItem(storageKey);
    if (stored !== JSON.stringify(state)) throw new Error('其他页面已修改课表，请刷新后重试');
    commitAiBoard(undoTimetableAction(board(), receipt.id), stored);
  },
  canUndo(receipt) { const b = board(); return b?.id === receipt.boardId && b?.aiLastOperation?.id === receipt.id && boardVersion(b) === b.aiLastOperation.afterVersion; },
};
const aiChat = createAiChat({ phone: $('#phone'), navigate, notify: toast, confirm: modal, timetable: timetableBridge });

async function refreshSource() {
  const response = await fetch('/api/source');
  if (!response.ok) throw new Error((await response.json()).error || '读取项目源码失败');
  source = await response.json(); lastRevision = source.revision;
  render(true);
}
try {
  await refreshSource();
  const events = new EventSource('/events');
  const connected = () => { $('#connection').textContent = '本地服务已连接'; $('.connection').classList.remove('offline'); };
  events.addEventListener('ready', async event => { connected(); const revision = JSON.parse(event.data).revision; if (lastRevision !== revision) { await refreshSource(); $('#reload-status').textContent = '连接恢复 · 已重新读取源码'; } });
  events.addEventListener('change', async event => {
    const change = JSON.parse(event.data);
    if (state.page === 'edit') readDraft();
    if (change.kind === 'preview') { save(); location.reload(); return; }
    try { await refreshSource(); $('#reload-status').textContent = `${new Date().toLocaleTimeString('zh-CN')} · 已读取源码变化`; toast(source.changedLayouts.length ? '安卓页面已改动，请同步浏览器布局' : '已从源码刷新主题、作息与课程样例'); }
    catch (error) { modal('源码读取失败', `${error.message}\n当前保留上一次成功加载的界面。`); }
  });
  events.onerror = () => { $('#connection').textContent = '连接中断，正在重连'; $('.connection').classList.add('offline'); };
} catch (error) { $('#app').innerHTML = `<div class="load-error">预览加载失败：${esc(error.message)}<br>请检查服务日志后刷新。</div>`; $('#connection').textContent = '源码加载失败'; }
