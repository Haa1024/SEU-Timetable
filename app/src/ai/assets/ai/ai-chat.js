import { marked } from '/vendor/marked.js';
import DOMPurify from '/vendor/purify.js';
import { DEFAULT_LLM_CONFIG, validateConfig, buildMessages, constrainWindow, constrainBubble, windowLimits, sseData } from './ai-core.js';
import { readChat, saveChat } from './ai-storage.js';
import { requestTimetableDecision, requestImageReview } from './timetable-request.js';
import { prepareContext } from './ai-context.js';
import { validateImportDraft, importMissing, importDescription } from './timetable-import.js';
import { timetableImageViews } from './ai-image-views.js';

const CONFIG_KEY = 'seu-llm-config-v1', KEY_KEY = 'seu-llm-api-key', POSITION_KEY = 'seu-llm-window-v1';
const escapeHtml = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const glyphs = {
  sparkle: '<path d="m12 3 2.4 6.6L21 12l-6.6 2.4L12 21l-2.4-6.6L3 12l6.6-2.4Z"/>',
  send: '<path d="M12 19V5m-6 6 6-6 6 6"/>',
  stop: '<rect x="6" y="6" width="12" height="12" rx="2" fill="currentColor" stroke="none"/>',
  settings: '<path d="M4 7h16M4 17h16"/><circle cx="9" cy="7" r="3"/><circle cx="16" cy="17" r="3"/>',
  image: '<rect x="3" y="3" width="18" height="18" rx="4"/><circle cx="8" cy="8" r="1.4"/><path d="m4 17 5-5 4 3 3-4 5 6"/>',
  minus: '<path d="M5 12h14"/>', plus: '<path d="M5 12h14M12 5v14"/>',
  expand: '<path d="M9 4H4v5m11-5h5v5M4 15v5h5m11-5v5h-5"/>',
  close: '<path d="m6 6 12 12M6 18 18 6"/>',
  trash: '<path d="M3 6h18M9 6V3h6v3M6 6l1 15h10l1-15M10 10v7m4-7v7"/>',
  copy: '<rect x="8" y="8" width="12" height="13" rx="2"/><path d="M16 8V3H3v13h5"/>',
  retry: '<path d="M20 7v5h-5M4 17a8 8 0 1 0 1-11"/>',
  resize: '<path d="m8 20 12-12m-6 12 6-6"/>',
  back: '<path d="m14 5-7 7 7 7"/>', eye: '<path d="M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12Z"/><circle cx="12" cy="12" r="3"/>',
};
const icon = (name, size = 18) => `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${glyphs[name] || glyphs.sparkle}</svg>`;
const button = (action, title, name, extra = '') => `<button type="button" data-ai="${action}" class="ai-icon-button" aria-label="${title}" title="${title}" ${extra}>${icon(name)}</button>`;
function markdown(text) {
  return DOMPurify.sanitize(marked.parse(text || '', { breaks: true, gfm: true }), {
    ALLOWED_TAGS: ['p', 'br', 'strong', 'em', 'del', 'code', 'pre', 'blockquote', 'ul', 'ol', 'li', 'h1', 'h2', 'h3', 'h4', 'hr', 'table', 'thead', 'tbody', 'tr', 'th', 'td', 'a'],
    ALLOWED_ATTR: ['href', 'title'], ALLOW_DATA_ATTR: false,
  });
}
const loadJson = (key, fallback) => { try { return JSON.parse(localStorage.getItem(key)) || fallback; } catch { return fallback; } };

export function createAiChat({ phone, navigate, notify, confirm, timetable, settingsStore }) {
  const nativeSettings = settingsStore?.load();
  let config;
  try { config = validateConfig(nativeSettings?.config || loadJson(CONFIG_KEY, DEFAULT_LLM_CONFIG)); } catch { config = { ...DEFAULT_LLM_CONFIG }; }
  let apiKey = '';
  try { apiKey = settingsStore ? nativeSettings?.apiKey || '' : (config.rememberKey ? localStorage : sessionStorage).getItem(KEY_KEY) || ''; } catch {}
  const emptyChat = () => ({ messages: [], draft: { text: '', images: [] } });
  let normalChat = emptyChat(), operationChats = {}, conversation = normalChat;
  let mode = loadJson('seu-ai-mode', 'chat') === 'timetable' ? 'timetable' : 'chat', operationBoardId = '';
  let defaultWeeks = 'ask';
  let geometry = loadJson(POSITION_KEY, {}), currentPage = 'today', opened = false, ready = false;
  let controller, busy = false, importing = false, error = '', cacheError = '', persistTimer, paintTimer, generation = 0;
  let previousSize, pendingDraw = false, testedController;
  let workStatus = '';
  const root = document.createElement('div'); root.id = 'ai-layer'; phone.append(root);
  root.innerHTML = `<button type="button" id="ai-bubble" class="ai-bubble" aria-label="打开 AI 聊天，可拖动" title="点击聊天 · 拖动移动">${icon('sparkle', 22)}<span>AI</span><i></i></button>
    <section id="ai-window" class="ai-window" role="dialog" aria-modal="false" aria-label="AI 助手聊天窗口" hidden>
      <header class="ai-window-header" tabindex="0" aria-label="拖动聊天窗口，方向键移动"><span class="ai-brand-mark">${icon('sparkle', 16)}</span><div class="ai-window-title"><strong>AI 助手</strong><span id="ai-model-label"></span></div>
        <div class="ai-window-actions">${button('settings', 'AI 模型设置', 'settings')}${button('size', '放大或还原聊天窗口', 'expand')}${button('minimize', '收起聊天窗口', 'minus')}</div>
      </header>
      <div class="ai-mode-switch" role="group" aria-label="AI 对话模式"><button type="button" data-ai="mode" data-mode="chat">普通聊天</button><button type="button" data-ai="mode" data-mode="timetable">课表操作</button></div>
      <div id="ai-operation-context" class="ai-operation-context" hidden><span id="ai-board-label"></span><span>未说明周次时先追问</span></div>
      <div class="ai-chat-toolbar"><span id="ai-cache-status">正在读取本机记录</span><div>${button('shrink', '缩小聊天窗口', 'minus')}${button('grow', '放大聊天窗口', 'plus')}${button('clear', '清空聊天记录', 'trash')}</div></div>
      <details id="ai-memory" class="ai-memory" hidden><summary>历史上下文摘要</summary><p>用于延续对话，完整聊天记录仍保留在下方。</p><pre></pre></details>
      <div id="ai-messages" class="ai-messages" role="log" aria-label="聊天记录"></div>
      <div id="ai-error" class="ai-error" role="alert" hidden></div>
      <form id="ai-composer" class="ai-composer">
        <div id="ai-attachments" class="ai-attachments"></div>
        <textarea id="ai-input" rows="2" maxlength="20000" placeholder="发消息，或添加图片…" aria-label="聊天消息"></textarea>
        <div class="ai-composer-actions"><div>${button('attach', '添加照片', 'image')}<span id="ai-compose-hint">Enter 发送 · Shift+Enter 换行</span></div><button type="submit" id="ai-send" class="ai-send" aria-label="发送消息">${icon('send')}</button></div>
      </form>
      <div class="ai-window-foot"><button id="ai-resize" type="button" aria-label="调整聊天窗口大小，方向键缩放" title="拖动调整大小">${icon('resize', 16)}</button></div>
      <input type="file" id="ai-file" accept="image/jpeg,image/png,image/webp,image/gif" multiple hidden>
    </section>`;
  const $ = selector => root.querySelector(selector), panel = $('#ai-window'), bubble = $('#ai-bubble'), input = $('#ai-input');

  function configured() { return !!apiKey || /^https?:\/\/(localhost|127\.0\.0\.1|\[::1\])(?=[:/]|$)/.test(config.baseUrl); }
  function saveGeometry() { try { localStorage.setItem(POSITION_KEY, JSON.stringify(geometry)); } catch {} }
  function layout() {
    const w = phone.clientWidth, h = phone.clientHeight;
    geometry.bubble = constrainBubble(geometry.bubble || { x: w - 70, y: h - 202 }, w, h);
    geometry.window = constrainWindow(geometry.window || { x: 20, y: h - 602, width: w - 40, height: 492 }, w, h);
    Object.assign(bubble.style, { left: `${geometry.bubble.x}px`, top: `${geometry.bubble.y}px` });
    Object.assign(panel.style, { left: `${geometry.window.x}px`, top: `${geometry.window.y}px`, width: `${geometry.window.width}px`, height: `${geometry.window.height}px` });
    bubble.hidden = currentPage !== 'timetable' || opened;
    panel.hidden = currentPage !== 'timetable' || !opened;
  }
  new ResizeObserver(layout).observe(phone);
  function selectConversation() {
    if (busy || !ready) return;
    if (mode === 'chat') conversation = normalChat;
    else {
      let id = 'no-board';
      try { id = timetable.context([], defaultWeeks).board.id; } catch {}
      operationBoardId = id;
      conversation = operationChats[id] ||= emptyChat();
    }
    input.value = conversation.draft.text || '';
    drawAttachments(); drawMessages();
  }
  function updatePage(page) { currentPage = page; layout(); if (ready && !busy) selectConversation(); if (page === 'llm') mountSettings(); }
  async function persistNow() {
    clearTimeout(persistTimer);
    if (!ready) return;
    try { await saveChat(mode === 'chat' ? normalChat : operationChats, mode === 'chat' ? 'conversation' : 'timetable-conversations'); cacheError = ''; }
    catch { cacheError = '本机缓存失败，请检查浏览器存储空间'; }
    updateControls();
  }
  function persistSoon() { clearTimeout(persistTimer); persistTimer = setTimeout(persistNow, 250); }
  function updateControls() {
    $('#ai-model-label').textContent = config.model;
    for (const el of root.querySelectorAll('[data-ai="mode"]')) { el.setAttribute('aria-pressed', String(el.dataset.mode === mode)); el.disabled = busy || importing || !ready; }
    $('#ai-operation-context').hidden = mode !== 'timetable';
    if (mode === 'timetable') {
      try { const ctx = timetable.context([], defaultWeeks); $('#ai-board-label').textContent = `${ctx.board.name} · ${ctx.date} · 查看第 ${ctx.viewedWeek} 周`; }
      catch { $('#ai-board-label').textContent = '请先创建或选择课表'; }
    }
    input.placeholder = mode === 'timetable' ? '例如：把今天的英语调到本周二2、3节…' : '发消息，或添加图片…';
    $('#ai-cache-status').textContent = cacheError || (!ready ? '正在读取本机记录' : busy ? (workStatus || (mode === 'timetable' ? '正在理解课表操作…' : '正在回复 · 自动保存到本机')) : conversation.memory ? `历史已压缩 ${conversation.memory.count} 次 · 原记录保留` : '聊天记录自动保存在本机');
    $('#ai-memory').hidden = !conversation.memory;
    $('#ai-memory pre').textContent = conversation.memory?.text || '';
    $('#ai-cache-status').classList.toggle('has-error', !!cacheError);
    bubble.classList.toggle('is-busy', busy);
    $('#ai-compose-hint').textContent = importing ? '正在处理照片…' : config.vision ? 'Enter 发送 · 可添加图片' : 'Enter 发送 · 文本模式';
    $('#ai-send').innerHTML = icon(busy ? 'stop' : 'send');
    $('#ai-send').setAttribute('aria-label', busy ? '停止生成' : '发送消息');
    $('#ai-send').disabled = !ready || importing || (!busy && !input.value.trim() && !conversation.draft.images.length);
    $('[data-ai="attach"]').disabled = !ready || importing || !config.vision || busy;
    $('[data-ai="clear"]').disabled = busy || importing;
    $('#ai-error').hidden = !error;
    $('#ai-error').textContent = error;
  }
  function drawMessages(forceScroll = false) {
    const container = $('#ai-messages'), nearBottom = container.scrollHeight - container.scrollTop - container.clientHeight < 65;
    const expanded = new Set([...container.querySelectorAll('details[open]')].map(el => el.dataset.message));
    if (!conversation.messages.length) {
      container.innerHTML = `<div class="ai-welcome"><div class="ai-welcome-symbol">${icon('sparkle', 28)}</div><h3>${mode === 'timetable' ? '用一句话安排课程' : '有什么想聊的？'}</h3><p>${mode === 'timetable' ? '添加、删除、调课或换课，直接说就好。<br>只改一次还是每周，都可以告诉我。' : '问问题、读图片，<br>一起理清学习思路。'}</p>${!configured() ? '<button type="button" data-ai="settings" class="ai-setup-link">先配置你的模型 →</button>' : mode === 'timetable' ? '<div class="ai-suggestions"><button type="button" data-ai="suggest" data-text="添加一个课程，星期六第1、2节，工科数学分析，在礼东上课">添加一门课</button><button type="button" data-ai="suggest" data-text="我想删除一门课程">删除课程</button></div>' : '<div class="ai-suggestions"><button type="button" data-ai="suggest" data-text="帮我制定一个可执行的学习计划。">整理学习计划</button><button type="button" data-ai="suggest" data-text="我想弄懂一道题，请一步步帮我分析。">一起分析题目</button></div>'}</div>`;
    } else container.innerHTML = conversation.messages.map((message, index) => {
      const assistant = message.role === 'assistant';
      const attachments = message.images?.length ? `<div class="ai-message-images">${message.images.map(image => `<button type="button" data-ai="view-image" data-message="${index}" data-image="${message.images.indexOf(image)}"><img src="${image.dataUrl}" alt="${escapeHtml(image.name)}"></button>`).join('')}</div>` : '';
      const text = assistant ? `<div class="ai-markdown">${markdown(message.text)}</div>` : `<div class="ai-user-text">${escapeHtml(message.text)}</div>`;
      const stateText = { stopped: '已停止', interrupted: '上次回复已中断', error: message.error || '回复失败', streaming: message.text ? '正在生成…' : '正在等待回复…' }[message.status];
      return `<article class="ai-message ${assistant ? 'assistant' : 'user'}" data-message-id="${message.id}">
        <div class="ai-message-label">${assistant ? `${icon('sparkle', 12)} AI 助手` : '你'}<time>${new Date(message.createdAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })}</time></div>
        ${message.reasoning ? `<details class="ai-reasoning" data-message="${message.id}" ${expanded.has(message.id) ? 'open' : ''}><summary>思考过程</summary><div>${escapeHtml(message.reasoning)}</div></details>` : ''}
        ${attachments}${text}${importButtons(message)}${message.operationStatus === 'unchanged' ? '<p class="ai-message-state">课表未修改</p>' : ''}${stateText ? `<p class="ai-message-state ${message.status === 'error' ? 'has-error' : ''}">${escapeHtml(stateText)}</p>` : ''}
        <div class="ai-message-tools">${message.text ? button('copy', '复制消息', 'copy', `data-index="${index}"`) : ''}${assistant && index === conversation.messages.length - 1 && !busy && (mode === 'chat' || ['error', 'stopped', 'interrupted'].includes(message.status)) && !message.receipt ? `<button type="button" data-ai="retry" data-index="${index}">${icon('retry', 12)} ${message.status === 'complete' ? '重新生成' : '重试'}</button>` : ''}${message.receipt && !message.undone && timetable.canUndo(message.receipt) ? `<button type="button" data-ai="undo" data-index="${index}" ${busy ? 'disabled' : ''}>${icon('retry', 12)} 撤销这次操作</button>` : ''}${message.undone ? '<span class="ai-undone">已撤销</span>' : ''}</div>
      </article>`;
    }).join('');
    for (const link of container.querySelectorAll('a')) {
      if (!/^https?:\/\//i.test(link.getAttribute('href') || '')) link.removeAttribute('href');
      else { link.target = '_blank'; link.rel = 'noopener noreferrer'; }
    }
    if (forceScroll || nearBottom) container.scrollTop = container.scrollHeight;
    updateControls();
  }
  function importButtons(message) {
    const pending=conversation.importDraft;
    if(mode!=='timetable'||pending?.messageId!==message.id)return '';
    const incomplete=importMissing(pending.draft).length>0;
    const existing=pending.context.board.courses.length||pending.context.board.unplaced.length;
    return `<div class="ai-import-actions"><span>待确认 · ${existing?'原课表已有内容，请选择处理方式':'原课表为空'}</span>
      <button type="button" data-ai="import-confirm" data-strategy="append" ${busy||incomplete?'disabled':''}>${existing?'追加，保留原课程':'确认添加'}</button>
      ${existing?`<button type="button" data-ai="import-confirm" data-strategy="weeks" ${busy||incomplete?'disabled':''}>覆盖涉及周</button><button type="button" data-ai="import-confirm" data-strategy="all" ${busy||incomplete?'disabled':''}>覆盖整张课表</button>`:''}
      <button type="button" data-ai="import-cancel" ${busy?'disabled':''}>取消这张清单</button></div>`;
  }
  function scheduleDraw() {
    if (pendingDraw) return;
    pendingDraw = true;
    paintTimer = setTimeout(() => { pendingDraw = false; drawMessages(); }, 70);
  }
  function drawAttachments() {
    $('#ai-attachments').innerHTML = conversation.draft.images.map((image, i) => `<div class="ai-attachment"><img src="${image.dataUrl}" alt="${escapeHtml(image.name)}">${button('remove-image', `移除照片 ${i + 1}`, 'close', `data-index="${i}"`)}<span>${escapeHtml(image.name)}</span></div>`).join('');
    updateControls();
  }
  function open() { opened = true; layout(); drawMessages(true); input.focus({ preventScroll: true }); }
  function minimize() { opened = false; layout(); persistNow(); }
  function setSize(width, height) {
    const old = geometry.window;
    geometry.window = constrainWindow({ ...old, x: old.x + (old.width - width) / 2, y: old.y + (old.height - height) / 2, width, height }, phone.clientWidth, phone.clientHeight);
    layout(); saveGeometry();
  }
  function resizeBy(amount) { setSize(geometry.window.width + amount, geometry.window.height + amount * 1.5); }
  function draggable(handle, kind) {
    let drag;
    handle.addEventListener('pointerdown', event => {
      if (event.button !== 0 || (kind === 'window' && event.target.closest('button'))) return;
      const scale = phone.getBoundingClientRect().width / phone.clientWidth;
      drag = { pointer: event.pointerId, x: event.clientX, y: event.clientY, scale, origin: { ...(kind === 'bubble' ? geometry.bubble : geometry.window) }, moved: false };
      handle.setPointerCapture(event.pointerId); event.preventDefault();
    });
    handle.addEventListener('pointermove', event => {
      if (!drag || drag.pointer !== event.pointerId) return;
      const dx = (event.clientX - drag.x) / drag.scale, dy = (event.clientY - drag.y) / drag.scale;
      if (Math.abs(dx) + Math.abs(dy) > 4) drag.moved = true;
      if (kind === 'bubble') geometry.bubble = constrainBubble({ x: drag.origin.x + dx, y: drag.origin.y + dy }, phone.clientWidth, phone.clientHeight);
      else geometry.window = constrainWindow(kind === 'resize' ? { ...drag.origin, width: drag.origin.width + dx, height: drag.origin.height + dy } : { ...drag.origin, x: drag.origin.x + dx, y: drag.origin.y + dy }, phone.clientWidth, phone.clientHeight);
      layout();
    });
    handle.addEventListener('pointerup', event => { if (!drag || drag.pointer !== event.pointerId) return; const moved = drag.moved; drag = null; handle.releasePointerCapture(event.pointerId); saveGeometry(); if (kind === 'bubble' && !moved) open(); });
    handle.addEventListener('pointercancel', () => { drag = null; saveGeometry(); });
    handle.addEventListener('keydown', event => {
      if (kind === 'bubble' && ['Enter', ' '].includes(event.key)) { event.preventDefault(); return open(); }
      if (!['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(event.key)) return;
      event.preventDefault(); const dx = event.key === 'ArrowLeft' ? -12 : event.key === 'ArrowRight' ? 12 : 0, dy = event.key === 'ArrowUp' ? -12 : event.key === 'ArrowDown' ? 12 : 0;
      if (kind === 'bubble') geometry.bubble = constrainBubble({ x: geometry.bubble.x + dx, y: geometry.bubble.y + dy }, phone.clientWidth, phone.clientHeight);
      else { const rect = geometry.window; geometry.window = constrainWindow(kind === 'resize' ? { ...rect, width: rect.width + dx, height: rect.height + dy } : { ...rect, x: rect.x + dx, y: rect.y + dy }, phone.clientWidth, phone.clientHeight); }
      layout(); saveGeometry();
    });
  }
  draggable(bubble, 'bubble'); draggable($('.ai-window-header'), 'window'); draggable($('#ai-resize'), 'resize');

  async function addImages(files) {
    if (!config.vision) { error = '请先在模型设置中启用图片输入，并选择支持视觉的模型'; return updateControls(); }
    if (busy || importing) return;
    importing = true; error = ''; updateControls();
    try {
      for (const file of files) {
        if (conversation.draft.images.length >= 4) throw new Error('每条消息最多添加 4 张照片');
        if (!['image/jpeg', 'image/png', 'image/webp', 'image/gif'].includes(file.type)) throw new Error('支持 JPEG、PNG、WebP 和 GIF 图片');
        if (file.size > 8 * 1024 * 1024) throw new Error('单张原图不能超过 8 MB');
        const bitmap = await createImageBitmap(file);
        const ratio = Math.min(1, (mode==='timetable'?2400:1600) / Math.max(bitmap.width, bitmap.height));
        const canvas = document.createElement('canvas'); canvas.width = Math.max(1, Math.round(bitmap.width * ratio)); canvas.height = Math.max(1, Math.round(bitmap.height * ratio));
        const ctx = canvas.getContext('2d'); ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, canvas.width, canvas.height); ctx.drawImage(bitmap, 0, 0, canvas.width, canvas.height); bitmap.close();
        const dataUrl = canvas.toDataURL('image/jpeg', 0.85);
        if (dataUrl.length > 3 * 1024 * 1024) throw new Error('图片处理后仍过大，请选择较小的图片');
        conversation.draft.images.push({ id: crypto.randomUUID(), name: file.name || '粘贴的图片', dataUrl, width: canvas.width, height: canvas.height });
      }
    } catch (failure) { error = failure.message || '图片无法读取，请换一张重试'; }
    finally { importing = false; $('#ai-file').value = ''; drawAttachments(); await persistNow(); }
  }
  $('#ai-file').addEventListener('change', event => addImages([...event.target.files]));
  input.addEventListener('paste', event => { const files = [...(event.clipboardData?.files || [])]; if (files.length) { event.preventDefault(); addImages(files); } });
  panel.addEventListener('dragover', event => { if (event.dataTransfer.types.includes('Files')) { event.preventDefault(); panel.classList.add('is-drop-target'); } });
  panel.addEventListener('dragleave', event => { if (!panel.contains(event.relatedTarget)) panel.classList.remove('is-drop-target'); });
  panel.addEventListener('drop', event => { event.preventDefault(); panel.classList.remove('is-drop-target'); addImages([...event.dataTransfer.files]); });
  input.addEventListener('input', () => { conversation.draft.text = input.value; persistSoon(); updateControls(); });
  input.addEventListener('keydown', event => { if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) { event.preventDefault(); if (!busy) send(); } });
  $('#ai-composer').addEventListener('submit', event => { event.preventDefault(); if (busy) controller?.abort(); else send(); });

  async function send(retryIndex) {
    if (!ready || busy || importing) return;
    if (!configured()) { error = '请先在「我的 → AI 模型设置」填写 API Key。'; updateControls(); return; }
    const retry = Number.isInteger(retryIndex);
    const text = input.value.trim();
    if (!retry && !text && !conversation.draft.images.length) return;
    let messages, operationContext;
    const candidate = retry ? conversation.messages.slice(0, retryIndex) : [...conversation.messages, { id: crypto.randomUUID(), role: 'user', text, images: structuredClone(conversation.draft.images), createdAt: Date.now(), status: 'complete' }];
    try {
      // Validate attachments now; prepareContext below summarizes old turns
      // before constructing the actual request, so no old turns silently vanish.
      buildMessages(candidate.slice(-1), config);
      if (mode === 'timetable') {
        const ids = conversation.messages.filter(m => m.receipt && !m.undone).flatMap(m => m.receipt.createdCourseIds || (m.receipt.type === 'add' ? [m.receipt.courseId] : []));
        operationContext = timetable.context(ids, defaultWeeks);
        if (operationContext.board.id !== operationBoardId) throw new Error('课表已切换，请重新打开对话');
      }
    }
    catch (failure) { error = failure.message; updateControls(); return; }
    conversation.messages = candidate;
    if (!retry) { conversation.draft = { text: '', images: [] }; input.value = ''; }
    const answer = { id: crypto.randomUUID(), role: 'assistant', text: '', reasoning: '', images: [], status: 'streaming', createdAt: Date.now(), model: config.model };
    conversation.messages.push(answer);
    busy = true; error = ''; controller = new AbortController(); const run = ++generation;
    drawAttachments(); drawMessages(true); await persistNow();
    try {
      const prepared=await prepareContext(candidate.filter(m=>m!==answer),conversation.memory,config,apiKey,{signal:controller.signal,operationMode:mode==='timetable',onCompress:()=>{workStatus='正在压缩历史上下文…';updateControls();}});
      controller.signal.throwIfAborted();
      conversation.memory=prepared.memory; messages=prepared.messages;workStatus='';updateControls();
      await persistNow();
      if (mode === 'timetable') {
        const imageReview=!!conversation.importDraft || !!candidate.filter(m=>m.role==='user').at(-1)?.images?.length;
        const request=candidate.filter(m=>m.role==='user').at(-1)?.images?.length ? requestImageReview : requestTimetableDecision;
        const decision = await request({ config, apiKey, messages, memory:conversation.memory?.text, context: operationContext,
          imageReview, importDraft:conversation.importDraft?.draft }, { signal: controller.signal,prepareViews:timetableImageViews,onVerify:()=>{workStatus='正在复核星期、节次和遗漏课程…';updateControls();} });
        controller.signal.throwIfAborted();
        if(imageReview) {
          if(decision.action && decision.action.type!=='import_preview')throw new Error('图片识别必须先生成待确认清单，课表未修改。请重试');
          if(decision.action) {
            const draft=validateImportDraft(decision.action,operationContext);
            conversation.importDraft={id:crypto.randomUUID(),messageId:answer.id,draft,context:operationContext};
            // The validated draft supplies counts and questions. Free-form model
            // prose can contradict its own JSON (e.g. say 11 slots but return 12).
            answer.text=importDescription(draft);
          } else answer.text=decision.reply;
          answer.status='complete';answer.operationStatus='unchanged';
          return;
        }
        const result = timetable.execute(decision, operationContext, candidate.filter(m => m.role === 'user').at(-1).id);
        answer.text = result.text; answer.receipt = result.receipt; answer.operationStatus = result.changed ? 'applied' : 'unchanged'; answer.status = 'complete';
        if (result.changed) notify('课表已更新');
        return;
      }
      const response = await fetch('/api/llm/chat', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ config, apiKey, messages, memory:conversation.memory?.text, stream: true }), signal: controller.signal });
      if (!response.ok) { const body = await response.json().catch(() => ({})); throw new Error(body.error || `请求失败（${response.status}）`); }
      let finished = false, finishReason;
      const apply = data => {
        if (data.error) throw new Error(typeof data.error === 'string' ? data.error : data.error.message || '模型接口返回错误');
        const choice = data.choices?.[0];
        if (!choice) return;
        const delta = choice.delta || choice.message || {};
        if (typeof delta.content === 'string') answer.text += delta.content;
        if (typeof delta.reasoning_content === 'string') answer.reasoning += delta.reasoning_content;
        if (choice.finish_reason) { finished = true; finishReason = choice.finish_reason; }
        persistSoon(); scheduleDraw();
      };
      if (response.headers.get('content-type')?.includes('text/event-stream')) {
        for await (const data of sseData(response.body)) {
          if (data.trim() === '[DONE]') { finished = true; break; }
          apply(JSON.parse(data));
        }
      } else { apply(await response.json()); finished = true; }
      if (!finished) throw new Error('连接提前断开，已保留收到的内容，可点击重试');
      if (!answer.text.trim()) throw new Error(finishReason === 'tool_calls' ? '模型返回了工具调用；当前仅支持聊天，不执行课表操作' : '模型未返回可显示的回答，请重试或检查模型设置');
      answer.status = 'complete';
      if (finishReason === 'length') answer.text += '\n\n> 回复达到输出长度上限，可提高设置中的最大输出长度后重新生成。';
    } catch (failure) {
      if (mode === 'timetable' && failure.clarification && !controller.signal.aborted) {
        answer.text = failure.message; answer.status = 'complete'; answer.operationStatus = 'unchanged';
      } else answer.status = controller.signal.aborted ? 'stopped' : 'error';
      if (answer.status === 'error') answer.error = failure.message || '请求失败，请重试';
    } finally {
      if (run === generation) { busy = false; workStatus=''; controller = null; clearTimeout(paintTimer); pendingDraw = false; drawMessages(); await persistNow(); selectConversation(); }
    }
  }

  root.addEventListener('click', async event => {
    const target = event.target.closest('[data-ai]'); if (!target) return;
    const action = target.dataset.ai;
    if(action==='import-cancel'&&!busy) {
      const pending=conversation.importDraft;
      if(pending){const message=conversation.messages.find(m=>m.id===pending.messageId);if(message)message.text+='\n\n这张清单已取消，未导入。';}
      conversation.importDraft=null;drawMessages();await persistNow();
    }
    else if(action==='import-confirm'&&!busy&&conversation.importDraft) {
      const chat=conversation,pending=chat.importDraft,strategy=target.dataset.strategy;
      try {
        const planned=timetable.import(pending.draft,pending.context,strategy,pending.id,true);
        confirm('确认导入图片课表？',planned.impact+'\n请核对聊天中的课程清单。确认后整次导入可以撤销。',{confirm:'确认导入',cancel:'返回核对',danger:strategy!=='append',onConfirm:async()=>{
          if(busy||conversation!==chat||chat.importDraft?.id!==pending.id){error='识别清单已更新，请重新确认';updateControls();return;}
          busy=true;
          try {
            const result=timetable.import(pending.draft,pending.context,strategy,pending.id);
            chat.importDraft=null;
            chat.messages.push({id:crypto.randomUUID(),role:'assistant',text:result.text,status:'complete',createdAt:Date.now(),receipt:result.receipt,operationStatus:result.changed?'applied':'unchanged'});
            error='';notify(result.changed?'图片课表已导入':'没有重复添加');
          }catch(failure){error=failure.message;}
          finally{busy=false;drawMessages(true);await persistNow();}
        }});
      }catch(failure){error=failure.message;updateControls();}
    }
    else if (action === 'mode' && !busy && !importing && ready) {
      // saveChat takes a synchronous snapshot before its queued IndexedDB write.
      // Switch immediately so fast typing cannot land in the previous draft.
      persistNow(); mode = target.dataset.mode; localStorage.setItem('seu-ai-mode', JSON.stringify(mode)); error = ''; selectConversation();
    }
    else if (action === 'undo' && !busy) {
      const message = conversation.messages[+target.dataset.index];
      try { timetable.undo(message.receipt); message.undone = true; message.text += '\n\n这次操作已撤销。'; error = ''; drawMessages(); await persistNow(); notify('已撤销课表操作'); }
      catch (failure) { error = failure.message; updateControls(); }
    }
    else if (action === 'settings') { persistNow(); navigate('llm'); }
    else if (action === 'minimize') minimize();
    else if (action === 'shrink') resizeBy(-35);
    else if (action === 'grow') resizeBy(35);
    else if (action === 'size') { if (previousSize) { const old = previousSize; previousSize = null; setSize(old.width, old.height); } else { previousSize = { ...geometry.window }; const limit = windowLimits(phone.clientWidth, phone.clientHeight); setSize(limit.maxWidth, limit.maxHeight); } }
    else if (action === 'attach') $('#ai-file').click();
    else if (action === 'remove-image') { conversation.draft.images.splice(+target.dataset.index, 1); drawAttachments(); persistNow(); }
    else if (action === 'suggest') { input.value = target.dataset.text; conversation.draft.text = input.value; persistSoon(); updateControls(); input.focus(); }
    else if (action === 'copy') { try { await navigator.clipboard.writeText(conversation.messages[+target.dataset.index].text); notify('已复制'); } catch { notify('复制失败，请选中文字手动复制'); } }
    else if (action === 'retry') send(+target.dataset.index);
    else if (action === 'clear' && !busy) confirm('清空聊天记录？', '将删除本机缓存的这段聊天和图片，无法恢复。模型配置和课表不受影响。', { confirm: '清空', cancel: '取消', danger: true, onConfirm: async () => { conversation = emptyChat(); if (mode === 'chat') normalChat = conversation; else operationChats[operationBoardId] = conversation; input.value = ''; error = ''; drawMessages(); drawAttachments(); await persistNow(); } });
    else if (action === 'view-image') showImage(conversation.messages[+target.dataset.message].images[+target.dataset.image]);
    else if (action === 'close-image') root.querySelector('.ai-image-viewer')?.remove();
  });
  function showImage(image) {
    root.querySelector('.ai-image-viewer')?.remove();
    const viewer = document.createElement('div'); viewer.className = 'ai-image-viewer';
    viewer.innerHTML = `${button('close-image', '关闭图片', 'close')}<img src="${image.dataUrl}" alt="${escapeHtml(image.name)}">`;
    root.append(viewer);
  }

  function settingsPage() {
    const field = (label, name, value, type = 'text', extra = '') => `<label class="form-field">${label}<input name="${name}" type="${type}" value="${escapeHtml(value)}" ${extra}></label>`;
    const check = (name, label, checked, hint) => `<label class="llm-check"><input type="checkbox" name="${name}" ${checked ? 'checked' : ''}><span>${label}<small>${hint}</small></span></label>`;
    return `<header class="nav-bar course-nav detail-nav"><button type="button" class="icon-button" data-action="back" aria-label="返回">${icon('back')}</button><h2>AI 模型设置</h2><span></span></header>
      <form id="llm-settings-form" class="page-scroll llm-settings" autocomplete="off">
        <div class="llm-settings-intro"><span>${icon('sparkle', 24)}</span><div><h3>连接你自己的模型</h3><p>支持普通聊天与课表操作两种模式。</p></div></div>
        <div class="section-title">接口配置</div><section class="card">
          <label class="form-field">接口类型<select name="provider"><option value="deepseek" ${config.provider === 'deepseek' ? 'selected' : ''}>DeepSeek</option><option value="compatible" ${config.provider === 'compatible' ? 'selected' : ''}>自定义 · OpenAI 兼容接口</option></select></label>
          ${field('API 地址', 'baseUrl', config.baseUrl, 'url', 'placeholder="https://api.deepseek.com"')}
          <p class="llm-field-note">可填写基础地址、/v1 或完整 /chat/completions 地址。</p>
          <label class="form-field">API Key<div class="llm-key-field"><input name="apiKey" type="password" value="${escapeHtml(apiKey)}" placeholder="输入你的 API Key" autocomplete="new-password" spellcheck="false">${button('show-key', '显示或隐藏 API Key', 'eye')}</div></label>
          ${check('rememberKey', '在本机记住 API Key', config.rememberKey, '勾选后保存在此浏览器中；不勾选仅在本次浏览器会话保存。')}
          ${field('模型名称', 'model', config.model, 'text', 'list="llm-model-options" placeholder="例如 deepseek-flash" spellcheck="false"')}<datalist id="llm-model-options"><option value="deepseek-flash">Flash · 支持图片</option><option value="deepseek-v4-pro">Pro</option></datalist>
          ${check('vision', '启用图片输入', config.vision, '需要模型和接口支持视觉；DeepSeek Flash 支持。')}
          ${check('thinking', '深度思考', config.thinking, '仅向 DeepSeek 接口发送思考模式参数。')}
        </section>
        <div class="section-title">对话参数</div><section class="card">
          ${field('温度', 'temperature', config.temperature, 'number', 'min="0" max="2" step="0.1"')}
          <p class="llm-field-note">越低越稳定。DeepSeek 思考模式下不使用此参数。</p>
          ${field('最大输出长度（Tokens）', 'maxTokens', config.maxTokens, 'number', 'min="128" max="32768" step="128"')}
          ${field('保留近期原文轮数', 'contextTurns', config.contextTurns, 'number', 'min="1" max="30"')}
          <p class="llm-field-note">更早的对话会自动生成摘要，较长的对话会提前压缩。完整记录仍保存在本机；两种模式和不同课表分别保留摘要。</p>
          <label class="form-field">系统提示词（普通聊天）<textarea name="systemPrompt" rows="4" maxlength="8000">${escapeHtml(config.systemPrompt)}</textarea></label>
        </section>
        <p class="llm-field-note">聊天与图片自动缓存到当前浏览器。普通聊天只发送对话；课表操作模式会同时发送当前课表、作息和预览日期，用于理解添加和删除指令。两种模式均不发送校园账号。</p>
        <p id="llm-settings-status" role="status"></p>
        <button type="button" class="secondary-button" data-ai="test-connection">测试连接</button>
      </form><footer class="course-action-bar"><button type="button" class="primary-button" data-ai="save-settings">保存设置</button></footer>`;
  }
  function readSettings() {
    const form = document.querySelector('#llm-settings-form');
    const data = new FormData(form);
    const next = validateConfig({ provider: data.get('provider'), baseUrl: data.get('baseUrl'), model: data.get('model'), temperature: data.get('temperature'), maxTokens: data.get('maxTokens'), contextTurns: data.get('contextTurns'), systemPrompt: data.get('systemPrompt'), vision: data.has('vision'), thinking: data.has('thinking'), rememberKey: data.has('rememberKey') });
    return { config: next, apiKey: String(data.get('apiKey') || '').trim() };
  }
  function settingsStatus(text, success = false) { const el = document.querySelector('#llm-settings-status'); if (el) { el.textContent = text; el.className = success ? 'llm-success' : 'form-error'; } }
  function mountSettings() {
    const form = document.querySelector('#llm-settings-form'); if (!form) return;
    form.addEventListener('submit', event => event.preventDefault());
    form.elements.model.addEventListener('change', () => { if (form.elements.model.value === 'deepseek-flash') form.elements.vision.checked = true; else if (form.elements.model.value === 'deepseek-v4-pro') form.elements.vision.checked = false; });
    form.elements.provider.addEventListener('change', () => { if (form.elements.provider.value === 'deepseek') { form.elements.baseUrl.value = DEFAULT_LLM_CONFIG.baseUrl; form.elements.model.value = DEFAULT_LLM_CONFIG.model; form.elements.vision.checked = true; } });
  }
  document.addEventListener('click', async event => {
    if (currentPage !== 'llm') return;
    const target = event.target.closest('[data-ai]'); if (!target || root.contains(target)) return;
    const action = target.dataset.ai;
    if (action === 'show-key') { const field = document.querySelector('[name="apiKey"]'); field.type = field.type === 'password' ? 'text' : 'password'; return; }
    if (!['save-settings', 'test-connection'].includes(action)) return;
    try {
      const values = readSettings();
      if (action === 'save-settings') {
        if (settingsStore) settingsStore.save(values);
        else {
          localStorage.setItem(CONFIG_KEY, JSON.stringify(values.config));
          localStorage.removeItem(KEY_KEY); sessionStorage.removeItem(KEY_KEY);
          (values.config.rememberKey ? localStorage : sessionStorage).setItem(KEY_KEY, values.apiKey);
        }
        config = values.config; apiKey = values.apiKey; error = ''; updateControls(); drawMessages();
        settingsStatus('设置已保存，返回课表后即可打开 AI 气泡。', true); notify('模型设置已保存');
      } else {
        if (testedController) return;
        testedController = new AbortController(); target.disabled = true; settingsStatus('正在测试模型连接…', true);
        try {
          const response = await fetch('/api/llm/chat', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ...values, messages: [{ role: 'user', content: '请仅回复 OK。' }], stream: false, test: true }), signal: testedController.signal });
          const data = await response.json();
          if (!response.ok || data.error) throw new Error(typeof data.error === 'string' ? data.error : data.error?.message || `HTTP ${response.status}`);
          if (typeof data.choices?.[0]?.message?.content !== 'string' || !data.choices[0].message.content.trim()) throw new Error('接口可达，但模型没有返回文字回答');
          settingsStatus(`连接成功 · ${values.config.model}。可点击下方保存设置。`, true);
        } finally { target.disabled = false; testedController = null; }
      }
    } catch (failure) { settingsStatus(failure.message || '操作失败，请重试'); }
  });
  Promise.all([readChat(), readChat('timetable-conversations')]).then(([saved, operations]) => {
    if (saved?.messages && saved?.draft) normalChat = saved;
    if (operations && typeof operations === 'object') operationChats = operations;
    for (const chat of [normalChat, ...Object.values(operationChats)]) {
      for (const message of chat.messages || []) if (message.status === 'streaming') message.status = 'interrupted';
    }
  }).catch(() => { cacheError = '无法读取本机缓存，当前记录暂存在内存'; }).finally(() => {
    ready = true; selectConversation(); layout();
  });
  document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'hidden') persistNow(); });
  window.addEventListener('pagehide', () => { controller?.abort(); persistNow(); });
  return { updatePage, settingsPage, summary: () => `${config.model} · ${configured() ? '已配置' : '未填写 API Key'}`, open, isOpen: () => opened && currentPage === 'timetable', minimize, flush: persistNow, stop: () => { controller?.abort(); testedController?.abort(); } };
}
