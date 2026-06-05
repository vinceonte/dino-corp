// Convert the Antagonist/ ANSI art tree into transparent PNG sprites for the
// on-scene NPC panel. Output naming (all lowercase) consumed by lost_in_woods.html:
//   <id>.png            base form        (default whenever the sin is present)
//   <id>_<stance>.png   emotion/stance   (chosen per scene by keyword match)
//   <id>_demon.png      ending form      (only on escape/transformation/lost/secret)
// where <id> is the sin key: adam, banner, felicia, nagi, sammuel, vincent, yuri.
import fs from 'node:fs';
import path from 'node:path';
import { rasterizeFile } from './ansi2png.mjs';

const SRC = process.argv[2] || 'Antagonist';
const OUT = process.argv[3] || 'src/main/resources/static/assets/npc';

const txts = (dir) => { try { return fs.readdirSync(dir).filter(f => f.toLowerCase().endsWith('.txt')); } catch { return []; } };
const subdir = (dir, prefix) => { try { return fs.readdirSync(dir, { withFileTypes: true }).find(d => d.isDirectory() && d.name.toLowerCase().startsWith(prefix))?.name; } catch { return undefined; } };

fs.mkdirSync(OUT, { recursive: true });
let count = 0;
for (const folder of fs.readdirSync(SRC, { withFileTypes: true }).filter(d => d.isDirectory())) {
  const dir = path.join(SRC, folder.name);
  const id = folder.name.split('_')[0].toLowerCase(); // Adam_Pride -> adam

  // 1) Base form: the .txt sitting directly in the character folder.
  const base = txts(dir)[0];
  if (base) { rasterizeFile(path.join(dir, base), path.join(OUT, `${id}.png`)); count++; console.log(`${id} base    <- ${base}`); }

  // 2) Emotion & Stance variants (folder name varies: "Emotion & Stance" / "Emotions&Stance").
  const emoDir = subdir(dir, 'emotion');
  if (emoDir) {
    for (const f of txts(path.join(dir, emoDir))) {
      const stem = path.basename(f, path.extname(f));            // Adam_Attacking
      const stance = (stem.split('_').slice(1).join('_') || stem).toLowerCase(); // attacking
      if (stance === id || stance === folder.name.split('_')[1]?.toLowerCase()) continue; // skip base-dup (e.g. Felicia_Greed)
      rasterizeFile(path.join(dir, emoDir, f), path.join(OUT, `${id}_${stance}.png`)); count++;
      console.log(`${id} stance  <- ${f}  -> ${id}_${stance}.png`);
    }
  }

  // 3) Ending form (DemonForm) -> <id>_demon.png
  const endDir = subdir(dir, 'ending');
  if (endDir) {
    const demon = txts(path.join(dir, endDir)).find(f => /demon/i.test(f)) || txts(path.join(dir, endDir))[0];
    if (demon) { rasterizeFile(path.join(dir, endDir, demon), path.join(OUT, `${id}_demon.png`)); count++; console.log(`${id} demon   <- ${demon}`); }
  }
}
console.log(`\nDone: ${count} sprites -> ${OUT}`);
