export const DEFAULT_LLM_CONFIG = Object.freeze({
  provider: 'deepseek', baseUrl: 'https://api.deepseek.com', model: 'deepseek-flash',
  vision: true, thinking: false, temperature: 0.7, maxTokens: 4096, contextTurns: 12,
  systemPrompt: '你是一位耐心、清晰的学习助手。优先使用中文，准确回答问题，不确定时说明。',
  rememberKey: false,
});

export function completionUrl(baseUrl) {
  let url;
  try { url = new URL(String(baseUrl).trim()); } catch { throw new Error('请输入完整的 API 地址，例如 https://api.deepseek.com'); }
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash) throw new Error('API 地址只支持 HTTP/HTTPS，不能包含账号、查询参数或片段');
  if (url.protocol === 'http:' && !['localhost', '127.0.0.1', '[::1]'].includes(url.hostname)) throw new Error('远程 API 请使用 HTTPS；HTTP 仅用于本机模型服务');
  const path = url.pathname.replace(/\/+$/, '');
  url.pathname = path.endsWith('/chat/completions') ? path : `${path}/chat/completions`;
  return url.href;
}

export function validateConfig(input) {
  const value = { ...DEFAULT_LLM_CONFIG, ...input };
  completionUrl(value.baseUrl);
  if (!['deepseek', 'compatible'].includes(value.provider)) throw new Error('请选择接口类型');
  for (const key of ['vision', 'thinking', 'rememberKey']) if (typeof value[key] !== 'boolean') throw new Error('模型开关设置格式不正确');
  value.baseUrl = String(value.baseUrl).trim();
  value.model = String(value.model).trim();
  if (!value.model || value.model.length > 160 || /[\r\n]/.test(value.model)) throw new Error('请填写有效的模型名称');
  for (const [key, min, max] of [['temperature', 0, 2], ['maxTokens', 128, 32768], ['contextTurns', 1, 30]]) {
    value[key] = Number(value[key]);
    if (!Number.isFinite(value[key]) || value[key] < min || value[key] > max || (key !== 'temperature' && !Number.isInteger(value[key]))) throw new Error(`${{ temperature: '温度', maxTokens: '最大输出长度', contextTurns: '上下文轮数' }[key]}应在 ${min}–${max} 之间`);
  }
  value.systemPrompt = String(value.systemPrompt ?? '').trim();
  if (value.systemPrompt.length > 8000) throw new Error('系统提示词最多 8000 字');
  return Object.fromEntries(Object.keys(DEFAULT_LLM_CONFIG).map(key => [key, value[key]]));
}

export function buildMessages(history, config) {
  // Only chat data enters this boundary. Timetable state is never a parameter.
  const turns = [];
  for (const message of history) {
    if (message.role === 'user') turns.push([message]);
    else if (message.role === 'assistant' && message.status === 'complete' && message.text && turns.length) turns.at(-1).push(message);
  }
  return turns.slice(-config.contextTurns).flat().map(message => {
    if (message.role === 'assistant' || !message.images?.length) return { role: message.role, content: message.text || '' };
    if (!config.vision) throw new Error('这段对话包含图片。请启用图片输入并选择支持视觉的模型，或清空聊天后使用文本模型。');
    return { role: 'user', content: [
      ...(message.text ? [{ type: 'text', text: message.text }] : [{ type: 'text', text: '请查看这张图片。' }]),
      ...message.images.map(image => ({ type: 'image_url', image_url: { url: image.dataUrl } })),
    ] };
  });
}

const clamp = (n, lo, hi) => Math.max(lo, Math.min(hi, Number.isFinite(n) ? n : lo));
export function windowLimits(width, height) {
  const maxWidth = Math.max(200, width - 24), maxHeight = Math.max(240, Math.floor((height - 140) * 0.88));
  return { minWidth: Math.min(280, maxWidth), maxWidth, minHeight: Math.min(300, maxHeight), maxHeight, top: 52, bottom: height - 94 };
}
export function constrainWindow(rect, width, height) {
  const limits = windowLimits(width, height);
  const w = clamp(rect.width, limits.minWidth, limits.maxWidth), h = clamp(rect.height, limits.minHeight, limits.maxHeight);
  return { x: clamp(rect.x, 12, width - 12 - w), y: clamp(rect.y, limits.top, limits.bottom - h), width: w, height: h };
}
export function constrainBubble(point, width, height) {
  return { x: clamp(point.x, 10, width - 62), y: clamp(point.y, 58, height - 150) };
}

export async function* sseData(stream) {
  const reader = stream.getReader(), decoder = new TextDecoder();
  let buffer = '';
  try {
    while (true) {
      const { value, done } = await reader.read();
      buffer += decoder.decode(value || new Uint8Array(), { stream: !done });
      let match;
      while ((match = /\r?\n\r?\n/.exec(buffer))) {
        const event = buffer.slice(0, match.index); buffer = buffer.slice(match.index + match[0].length);
        const data = event.split(/\r?\n/).filter(line => line.startsWith('data:')).map(line => line.slice(5).trimStart()).join('\n');
        if (data) yield data;
      }
      if (done) {
        const data = buffer.split(/\r?\n/).filter(line => line.startsWith('data:')).map(line => line.slice(5).trimStart()).join('\n');
        if (data) yield data;
        break;
      }
    }
  } finally { await reader.cancel().catch(() => {}); reader.releaseLock(); }
}
