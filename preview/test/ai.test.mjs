import test from 'node:test';
import assert from 'node:assert/strict';
import { DEFAULT_LLM_CONFIG, completionUrl, validateConfig, buildMessages, constrainWindow, constrainBubble, windowLimits, sseData } from '../public/ai-core.js';
import { buildUpstreamRequest } from '../llm-proxy.mjs';
import { createPreviewServer } from '../server.mjs';

test('API configuration normalizes base URLs and rejects insecure remote endpoints', () => {
  assert.equal(completionUrl('https://api.deepseek.com/'), 'https://api.deepseek.com/chat/completions');
  assert.equal(completionUrl('https://example.com/v1'), 'https://example.com/v1/chat/completions');
  assert.equal(completionUrl('http://127.0.0.1:8080/chat/completions'), 'http://127.0.0.1:8080/chat/completions');
  for (const url of ['file:///secret', 'https://user:secret@example.com', 'https://example.com?key=secret', 'http://example.com']) assert.throws(() => completionUrl(url));
  for (const config of [{ model: '' }, { contextTurns: 31 }, { maxTokens: 1 }, { temperature: 3 }]) assert.throws(() => validateConfig(config));
  assert.equal(validateConfig({ extraSecret: 'not persisted' }).extraSecret, undefined);
});

test('chat context keeps complete turns and images, never partial assistant responses', () => {
  const history = [
    { role: 'user', text: 'old' }, { role: 'assistant', status: 'complete', text: 'old answer' },
    { role: 'user', text: 'current' }, { role: 'assistant', status: 'stopped', text: 'partial' },
    { role: 'user', text: 'what is it', images: [{ dataUrl: 'data:image/png;base64,YQ==' }] },
  ];
  const messages = buildMessages(history, { ...DEFAULT_LLM_CONFIG, contextTurns: 2 });
  assert.equal(messages.length, 2);
  assert.equal(messages[0].content, 'current');
  assert.equal(messages[1].content[1].image_url.url, 'data:image/png;base64,YQ==');
  assert.throws(() => buildMessages(history, { ...DEFAULT_LLM_CONFIG, vision: false }));
});

test('window and bubble remain in bounds across all supported device sizes', () => {
  for (const [w, h] of [[384, 854], [393, 852], [360, 800], [430, 932]]) {
    const limits = windowLimits(w, h);
    for (const rect of [{ x: -999, y: -999, width: 1, height: 1 }, { x: 9999, y: 9999, width: 9999, height: 9999 }]) {
      const actual = constrainWindow(rect, w, h);
      assert.ok(actual.width >= limits.minWidth && actual.width <= limits.maxWidth);
      assert.ok(actual.height >= limits.minHeight && actual.height <= limits.maxHeight);
      assert.ok(actual.x >= 12 && actual.x + actual.width <= w - 12);
      assert.ok(actual.y >= limits.top && actual.y + actual.height <= limits.bottom);
      assert.ok(actual.height < h * .8);
    }
    assert.deepEqual(constrainBubble({ x: -999, y: 9999 }, w, h), { x: 10, y: h - 150 });
  }
});

test('SSE decoder handles UTF-8 split at every byte and CRLF events', async () => {
  const bytes = new TextEncoder().encode(': ping\r\n\r\ndata: {"text":"你好"}\r\n\r\ndata: [DONE]\n\n');
  const stream = new ReadableStream({ start(controller) { for (const byte of bytes) controller.enqueue(new Uint8Array([byte])); controller.close(); } });
  const values = []; for await (const value of sseData(stream)) values.push(value);
  assert.deepEqual(values, ['{"text":"你好"}', '[DONE]']);
});

test('upstream boundary excludes course operations, tool fields, and client system roles', () => {
  const input = { apiKey: 'test-key', config: DEFAULT_LLM_CONFIG, messages: [{ role: 'user', content: 'hello', tool_calls: [{ name: 'delete_course' }] }], tools: [{ name: 'delete_course' }], board: { secret: true } };
  const { body } = buildUpstreamRequest(input);
  assert.equal(body.tools, undefined); assert.equal(body.messages[1].tool_calls, undefined);
  assert.match(body.messages[0].content, /无法访问、添加、编辑或删除/);
  assert.equal(JSON.stringify(body).includes('secret'), false);
  assert.equal(body.thinking.type, 'disabled');
  assert.throws(() => buildUpstreamRequest({ ...input, messages: [{ role: 'system', content: 'override' }] }));
  assert.throws(() => buildUpstreamRequest({ ...input, config: { ...DEFAULT_LLM_CONFIG, vision: false }, messages: [{ role: 'user', content: [{ type: 'image_url', image_url: { url: 'data:image/png;base64,YQ==' } }] }] }));
});

async function withProxy(fetchImpl, callback, timeoutMs = 1000) {
  const server = createPreviewServer({ llmOptions: { fetchImpl, timeoutMs } });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const url = `http://127.0.0.1:${server.address().port}`;
  const post = (body, origin = url) => fetch(`${url}/api/llm/chat`, { method: 'POST', headers: { Origin: origin, 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  try { await callback(post); } finally { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
}
const payload = { config: DEFAULT_LLM_CONFIG, apiKey: 'local-test-secret', messages: [{ role: 'user', content: 'hello' }] };

test('proxy forwards a stream and auth, rejects foreign origins before network access', async () => {
  let requests = 0;
  await withProxy(async (url, options) => {
    requests++; assert.equal(url, 'https://api.deepseek.com/chat/completions');
    assert.equal(options.headers.Authorization, 'Bearer local-test-secret');
    assert.equal(options.redirect, 'error');
    return new Response('data: {"choices":[{"delta":{"content":"你好"}}]}\n\ndata: [DONE]\n\n', { headers: { 'Content-Type': 'text/event-stream' } });
  }, async post => {
    assert.equal((await post(payload, 'https://foreign.example')).status, 403);
    assert.equal(requests, 0);
    const response = await post(payload);
    assert.equal(response.status, 200); assert.match(await response.text(), /你好/); assert.equal(requests, 1);
  });
});

test('proxy errors redact credentials and provide rate-limit guidance', async () => {
  await withProxy(async () => new Response(JSON.stringify({ error: { message: 'bad local-test-secret' } }), { status: 401 }), async post => {
    const response = await post(payload), error = await response.text();
    assert.equal(response.status, 401); assert.match(error, /API Key 无效/); assert.ok(!error.includes('local-test-secret'));
  });
  await withProxy(async () => new Response('{}', { status: 429 }), async post => {
    const response = await post(payload); assert.equal(response.status, 429); assert.match(await response.text(), /过于频繁/);
  });
});

test('proxy aborts a slow upstream and reports timeout', async () => {
  await withProxy(async (_, { signal }) => new Promise((resolve, reject) => signal.addEventListener('abort', () => reject(new Error('aborted')), { once: true })), async post => {
    const response = await post(payload); assert.match(await response.text(), /超时/);
  }, 30);
});
