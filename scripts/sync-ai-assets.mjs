// Bundle only the AI feature, never the preview's demo board, server or secrets.
import { readFile, writeFile, mkdir, copyFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { resolve, dirname } from 'node:path';
import { createHash } from 'node:crypto';
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const out = resolve(root, 'app/src/main/assets/ai');
await mkdir(resolve(out, 'vendor'), { recursive: true });
const files = ['ai-chat.js','ai-core.js','ai-storage.js','ai-context.js','ai-image-views.js','timetable-ai.js','timetable-import.js','timetable-request.js','timetable-protocol.js','model.js','ai-chat.css','styles.css','course-pages.css'];
const hashes = {};
for (const name of files) {
  const data = await readFile(resolve(root, 'preview/public', name));
  await writeFile(resolve(out, name), data);
  hashes[name] = createHash('sha256').update(data).digest('hex');
}
for (const name of ['timetable-prompt.mjs','image-import-prompt.mjs']) {
  const data = (await readFile(resolve(root, 'preview', name), 'utf8')).replaceAll('./public/', './');
  await writeFile(resolve(out, name), data);
  hashes[name] = createHash('sha256').update(data).digest('hex');
}
const proxy = (await readFile(resolve(root, 'preview/llm-proxy.mjs'), 'utf8')).split('\nasync function readJson(')[0].replaceAll('./public/', './');
await writeFile(resolve(out, 'request-builder.mjs'), proxy);
hashes['request-builder.mjs'] = createHash('sha256').update(proxy).digest('hex');
await copyFile(resolve(root, 'preview/node_modules/marked/lib/marked.esm.js'), resolve(out, 'vendor/marked.js'));
await copyFile(resolve(root, 'preview/node_modules/dompurify/dist/purify.es.mjs'), resolve(out, 'vendor/purify.js'));
await copyFile(resolve(root, 'preview/node_modules/marked/LICENSE'), resolve(out, 'vendor/marked.LICENSE'));
await copyFile(resolve(root, 'preview/node_modules/dompurify/LICENSE'), resolve(out, 'vendor/DOMPurify.LICENSE'));
const mplLicense = await readFile(resolve(root, 'preview/node_modules/dompurify/LICENSE-MPL'), 'utf8');
await writeFile(resolve(out, 'vendor/DOMPurify.LICENSE-MPL'), mplLicense.replace(/[ \t]+$/gm, ''));
await writeFile(resolve(out, 'shared-manifest.json'), JSON.stringify(hashes, null, 2));
console.log(`Synced ${Object.keys(hashes).length} shared AI modules; native shell files preserved.`);
