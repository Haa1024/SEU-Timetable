import { readFile, readdir } from 'node:fs/promises';
import { resolve, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';

export const root = fileURLToPath(new URL('../', import.meta.url));
export const kotlinRoot = 'app/src/main/java/com/seu/timetable';
const read = path => readFile(resolve(root, path), 'utf8');

export function parseTheme(source) {
  const colors = {};
  for (const mode of ['Light', 'Dark']) {
    const block = source.match(new RegExp(`val ${mode}SeuColors = SeuColors\\(([\\s\\S]*?)\\n\\)`))?.[1];
    if (!block) throw new Error(`Theme.kt: ${mode}SeuColors was not found`);
    colors[mode.toLowerCase()] = Object.fromEntries(
      [...block.matchAll(/(\w+)\s*=\s*Color\(0xFF([A-Fa-f0-9]{6})\)/g)].map(m => [m[1], `#${m[2]}`]),
    );
    if (!colors[mode.toLowerCase()].primary) throw new Error('Theme.kt: primary color was not found');
  }
  const paletteBlock = source.match(/val CourseBarPalette[\s\S]*?listOf\(([\s\S]*?)\n\)/)?.[1];
  const palette = [...(paletteBlock ?? '').matchAll(/Color\(0xFF([A-Fa-f0-9]{6})\)/g)].map(m => `#${m[1]}`);
  if (palette.length !== 16) throw new Error('Theme.kt: expected 16 course colors; update the browser adapter');
  const typography = Object.fromEntries([...source.matchAll(/(\w+) = style\((\d+), (\d+), FontWeight\.(\w+)\)/g)]
    .map(([, key, size, lineHeight, weight]) => [key, { size: +size, lineHeight: +lineHeight, weight }]));
  const radiusBlock = source.match(/object SeuRadius\s*\{([\s\S]*?)\}/)?.[1] ?? '';
  const radii = Object.fromEntries([...radiusBlock.matchAll(/val (\w+) = (\d+)\.dp/g)].map(m => [m[1], +m[2]]));
  return { colors, palette, typography, radii };
}

export function parsePeriods(source) {
  const periods = [...source.matchAll(/PeriodTime\((\d+), LocalTime\.of\((\d+), (\d+)\), LocalTime\.of\((\d+), (\d+)\)\)/g)]
    .map(([, index, bh, bm, eh, em]) => ({ index: +index, begin: `${bh.padStart(2, '0')}:${bm.padStart(2, '0')}`, end: `${eh.padStart(2, '0')}:${em.padStart(2, '0')}` }));
  if (!periods.length) throw new Error('PeriodTimes.kt: no periods found; update the browser adapter');
  return periods;
}

export function mapFixtures(rows, term, unplaced) {
  const courses = new Map();
  const sessions = rows.map((row, i) => {
    const id = row.JXBID || `${row.KCH}|${row.KXH}|${row.KCM}`;
    courses.set(id, { id, name: row.KCM, teacher: row.SKJS || '', code: row.KCH || '', classNo: row.KXH || '', note: '', credit: '', colorOverride: null });
    return { id: row.KBID || `fixture-${i}`, courseId: id, day: +row.SKXQ, start: +row.KSJC, end: +row.JSJC,
      room: row.JASMC || '', weeks: [...String(row.SKZC)].flatMap((bit, index) => bit === '1' ? [index + 1] : []) };
  });
  return { id: 'fixture', name: `${term.XN}学年秋季学期`, source: 'EHALL',
    term: { firstMonday: term.XQKSRQ.slice(0, 10), totalWeeks: +term.ZZC, lastTeachingWeek: +term.ZJXZC, morning: +term.SFJJ, afternoon: +term.XFJJ, evening: +term.WSJJ },
    courses: [...courses.values()], sessions, showOutOfWeek: false,
    unplaced: unplaced.map(row => ({ id: row.JXBID, name: row.KCM, teacher: row.SKJS || '', weeks: row.SKZC || '', credit: row.XF })) };
}

export async function layoutHashes() {
  const base = resolve(root, kotlinRoot, 'ui');
  const files = (await readdir(base, { recursive: true })).filter(p => p.endsWith('.kt') && !p.startsWith('theme'));
  const hashes = {};
  for (const file of files.sort()) {
    const full = resolve(base, file);
    hashes[relative(root, full).replaceAll('\\', '/')] = createHash('sha256').update(await readFile(full)).digest('hex');
  }
  return hashes;
}

export async function loadSource() {
  const [theme, periods, rows, term, unplaced, gradle, hashes, baseline, strings] = await Promise.all([
    read(`${kotlinRoot}/ui/theme/Theme.kt`), read(`${kotlinRoot}/domain/PeriodTimes.kt`),
    read('app/src/test/resources/ehall_xskcb.json'), read('app/src/test/resources/ehall_cxjcs.json'),
    read('app/src/test/resources/ehall_xswpkc.json'), read('app/build.gradle.kts'), layoutHashes(),
    read('preview/layout-baseline.json').then(JSON.parse).catch(() => ({})),
    read('app/src/main/res/values/strings.xml'),
  ]);
  const changedLayouts = [...new Set([...Object.keys(hashes), ...Object.keys(baseline)])].filter(p => hashes[p] !== baseline[p]);
  const playful = Object.fromEntries([...strings.matchAll(/<string-array name="(playful_\w+)">([\s\S]*?)<\/string-array>/g)].map(([, name, block]) => [name, [...block.matchAll(/<item>([\s\S]*?)<\/item>/g)].map(m => m[1].replaceAll('\\n', '\n'))]));
  return { theme: parseTheme(theme), periods: parsePeriods(periods), playful,
    board: mapFixtures(JSON.parse(rows).datas.xskcb.rows, JSON.parse(term).datas.cxjcs.rows[0], JSON.parse(unplaced).datas.xswpkc.rows),
    version: gradle.match(/versionName\s*=\s*"([^"]+)"/)?.[1] ?? 'unknown',
    changedLayouts, loadedAt: new Date().toISOString() };
}
