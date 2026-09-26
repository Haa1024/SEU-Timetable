import http from 'node:http';
import { readFile, stat } from 'node:fs/promises';
import { watch } from 'node:fs';
import { resolve, extname, sep } from 'node:path';
import { pathToFileURL } from 'node:url';
import { root, loadSource } from './source.mjs';
import { handleLlm } from './llm-proxy.mjs';

const publicRoot = resolve(root, 'preview/public');
const mime = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml' };

export function createPreviewServer({ llmOptions } = {}) {
  const clients = new Set();
  const watchers = [];
  let debounce;
  let revision = Date.now();
  const server = http.createServer(async (req, res) => {
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('Content-Security-Policy', "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'");
    try {
      if (req.url === '/api/llm/chat') return await handleLlm(req, res, llmOptions);
      if (req.method !== 'GET') { res.writeHead(405); return res.end(); }
      const pathname = new URL(req.url, 'http://localhost').pathname;
      const vendors = { '/vendor/marked.js': 'marked/lib/marked.esm.js', '/vendor/purify.js': 'dompurify/dist/purify.es.mjs' };
      if (vendors[pathname]) {
        res.setHeader('Content-Type', 'text/javascript; charset=utf-8');
        return res.end(await readFile(resolve(root, 'preview/node_modules', vendors[pathname])));
      }
      if (pathname === '/api/health') {
        res.setHeader('Content-Type', 'application/json');
        return res.end(JSON.stringify({ service: 'seu-timetable-preview', root, pid: process.pid }));
      }
      if (pathname === '/api/source') {
        res.setHeader('Content-Type', 'application/json');
        return res.end(JSON.stringify({ ...await loadSource(), revision }));
      }
      if (pathname === '/events') {
        res.writeHead(200, { 'Content-Type': 'text/event-stream', Connection: 'keep-alive' });
        res.write(`event: ready\ndata: ${JSON.stringify({ revision })}\n\n`);
        clients.add(res);
        req.on('close', () => clients.delete(res));
        return;
      }
      const file = resolve(publicRoot, '.' + decodeURIComponent(pathname === '/' ? '/index.html' : pathname));
      if (!file.startsWith(publicRoot + sep)) { res.writeHead(403); return res.end('Forbidden'); }
      if (!(await stat(file)).isFile()) { res.writeHead(404); return res.end('Not found'); }
      res.setHeader('Content-Type', mime[extname(file)] ?? 'application/octet-stream');
      res.end(await readFile(file));
    } catch (error) {
      res.writeHead(error.code === 'ENOENT' ? 404 : 500, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ error: error.code === 'ENOENT' ? 'Not found' : error.message }));
    }
  });
  const publish = (kind, file) => {
    clearTimeout(debounce);
    debounce = setTimeout(() => {
      revision = Math.max(Date.now(), revision + 1);
      for (const res of clients) res.write(`event: change\ndata: ${JSON.stringify({ revision, kind, file })}\n\n`);
    }, 250);
  };
  for (const [dir, kind] of [['preview/public', 'preview'], ['app/src/main', 'android'], ['app/src/test/resources', 'fixtures']]) {
    const watcher = watch(resolve(root, dir), { recursive: true }, (_, file) => {
      if (file && /\.(kt|xml|json|css|js|html)$/.test(file)) publish(kind, String(file));
    });
    watcher.on('error', error => console.error('File watcher:', error.message));
    watchers.push(watcher);
  }
  watchers.push(watch(resolve(root, 'preview/layout-baseline.json'), () => publish('baseline', 'layout-baseline.json')));
  const heartbeat = setInterval(() => { for (const res of clients) res.write(': keepalive\n\n'); }, 15000);
  server.on('close', () => { clearInterval(heartbeat); clearTimeout(debounce); watchers.forEach(w => w.close()); clients.forEach(res => res.end()); });
  return server;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const port = Number(process.env.PREVIEW_PORT || 4173);
  const server = createPreviewServer();
  server.on('error', error => { console.error(error.message); process.exitCode = 1; server.close(); });
  server.listen(port, '127.0.0.1', () => console.log(`SEU Timetable preview: http://127.0.0.1:${port}`));
  for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => { server.closeAllConnections(); server.close(); });
}
