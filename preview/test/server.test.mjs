import test from 'node:test';
import assert from 'node:assert/strict';
import { createPreviewServer } from '../server.mjs';

test('server serves preview and source but does not expose repository files', async () => {
  const server = createPreviewServer();
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const url = `http://127.0.0.1:${server.address().port}`;
  try {
    assert.equal((await fetch(url)).status, 200);
    assert.equal((await (await fetch(`${url}/api/source`)).json()).board.courses.length, 6);
    assert.equal((await fetch(`${url}/local.properties`)).status, 404);
    assert.equal((await fetch(`${url}/..%2f..%2fapp%2fbuild.gradle.kts`)).status, 403);
    assert.equal((await fetch(`${url}/api/source`, { method: 'POST' })).status, 405);
    const stream = await fetch(`${url}/events`);
    const reader = stream.body.getReader();
    const initial = new TextDecoder().decode((await reader.read()).value);
    assert.match(initial, /event: ready/);
    await reader.cancel();
  } finally { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
});
