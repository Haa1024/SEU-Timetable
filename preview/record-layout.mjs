// Run only after reviewing the browser implementation against the Android UI.
import { writeFile } from 'node:fs/promises';
import { layoutHashes } from './source.mjs';
await writeFile(new URL('./layout-baseline.json', import.meta.url), JSON.stringify(await layoutHashes(), null, 2) + '\n');
console.log('Recorded Android UI source hashes for browser-preview drift detection.');
