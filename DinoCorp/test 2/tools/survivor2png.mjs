// Convert the survivor ANSI art tree into transparent PNG portraits.
//
// Source structure (under SRC):
//   Base_Form/<Name>_<Class>Base.txt             -> assets/char/<id>.png
//   Emotion & Stance/<Name>/<Name>_<Stance>.txt  -> assets/char/<id>_<stance>.png
//
// where <id> is the survivor key used in the frontend: runa, kane, esme, voss, pip, morrow.
// "Old_Morrow_VeteranBase.txt" -> id=morrow (we map the *last* name token before the class).
import fs from 'node:fs';
import path from 'node:path';
import { rasterizeFile } from './ansi2png.mjs';

const SRC = process.argv[2] || 'Assets/choose_your_survivor_or_protagonist';
const OUT = process.argv[3] || 'src/main/resources/static/assets/char';

// Stance-subfolder name -> survivor id used by the frontend.
const FOLDER_TO_ID = {
  esme:'esme', kane:'kane', morrow:'morrow', pip:'pip', runa:'runa', voss:'voss',
};
// Recover the survivor id from a base-form filename like "Old_Morrow_VeteranBase.txt".
// Strategy: drop the trailing "<Class>Base.txt", then take the LAST remaining token.
function idFromBaseFile(name) {
  const stem = path.basename(name, path.extname(name));   // Old_Morrow_VeteranBase
  const noBase = stem.replace(/[A-Z][a-z]*Base$/,'').replace(/_+$/,''); // Old_Morrow
  const last = noBase.split('_').pop();                   // Morrow
  return last ? last.toLowerCase() : null;
}

fs.mkdirSync(OUT, { recursive: true });
let n = 0;

// 1) Base forms
const baseDir = path.join(SRC, 'Base_Form');
for (const f of (fs.existsSync(baseDir) ? fs.readdirSync(baseDir) : [])) {
  if (!f.toLowerCase().endsWith('.txt')) continue;
  const id = idFromBaseFile(f);
  if (!id) { console.warn('skip base, cannot derive id:', f); continue; }
  rasterizeFile(path.join(baseDir, f), path.join(OUT, `${id}.png`));
  console.log(`base   ${id.padEnd(7)} <- ${f}`);
  n++;
}

// 2) Emotion & Stance variants
const emoDir = path.join(SRC, 'Emotion & Stance');
if (fs.existsSync(emoDir)) {
  for (const sub of fs.readdirSync(emoDir, { withFileTypes: true })) {
    if (!sub.isDirectory()) continue;
    const id = FOLDER_TO_ID[sub.name.toLowerCase()];
    if (!id) { console.warn('unknown stance folder:', sub.name); continue; }
    const dir = path.join(emoDir, sub.name);
    for (const f of fs.readdirSync(dir)) {
      if (!f.toLowerCase().endsWith('.txt')) continue;
      const stem = path.basename(f, path.extname(f));     // Pip_Panicking
      const parts = stem.split('_');
      const stance = (parts.slice(1).join('_') || stem).toLowerCase();
      if (!stance || stance === id) continue;             // skip dup-of-base names
      rasterizeFile(path.join(dir, f), path.join(OUT, `${id}_${stance}.png`));
      console.log(`stance ${id.padEnd(7)} <- ${f}  ->  ${id}_${stance}.png`);
      n++;
    }
  }
}
console.log(`\nDone: ${n} survivor sprites -> ${OUT}`);
