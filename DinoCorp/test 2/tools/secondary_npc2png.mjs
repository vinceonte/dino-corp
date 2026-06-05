// Rasterize the Secondary NPC ANSI .txt files into transparent PNG sprites.
// Each file becomes assets/npc/<lowercased-stem>.png — joining the same NPC pool
// the 7 Sins live in, so renderNpc() picks them up automatically.
import fs from 'node:fs';
import path from 'node:path';
import { rasterizeFile } from './ansi2png.mjs';

const SRC = process.argv[2] || 'Assets/Secodary_NPC';     // NOTE: folder typo on disk
const OUT = process.argv[3] || 'src/main/resources/static/assets/npc';

fs.mkdirSync(OUT, { recursive: true });
let n = 0;
for (const f of fs.readdirSync(SRC).sort()) {
  if (!f.toLowerCase().endsWith('.txt')) continue;
  const id = path.basename(f, path.extname(f)).toLowerCase();
  rasterizeFile(path.join(SRC, f), path.join(OUT, `${id}.png`));
  console.log(`npc ${id.padEnd(18)} <- ${f}`);
  n++;
}
console.log(`\nDone: ${n} secondary NPC sprites -> ${OUT}`);
