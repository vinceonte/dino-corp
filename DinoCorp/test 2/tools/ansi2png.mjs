// Rasterize 24-bit ANSI text art into a PNG portrait.
// Each terminal cell -> one pixel: RGB from the cell's foreground color,
// alpha from the glyph's ink coverage (space = transparent background).
// No external deps: PNG is assembled by hand with the built-in zlib.
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';

const SRC = process.argv[2];
const OUT_DIR = process.argv[3];
const OPAQUE = process.argv[4] === 'opaque';            // flatten onto a bg color (for full-bleed backgrounds)
const BG = (process.argv[5] || '5,13,5').split(',').map(Number); // composite color when OPAQUE

// Dark -> light density ramp; index gives ink coverage 0..1.
const RAMP = " .'`^\",:;Il!i><~+_-?][}{1)(|/\\tfjrxnuvczXYUJCLQ0OZmwqpdbkhao*#MW&8%B@$";
const COV = new Map();
for (let i = 0; i < RAMP.length; i++) COV.set(RAMP[i], i / (RAMP.length - 1));
function coverage(ch) {
  if (ch === ' ' || ch === '\t') return 0;
  const c = COV.get(ch);
  return c === undefined ? 0.6 : c; // unknown glyph -> mid coverage
}

const CRC = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[n] = c >>> 0; }
  return (buf) => { let c = 0xffffffff; for (let i = 0; i < buf.length; i++) c = t[(c ^ buf[i]) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
})();

function chunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length, 0);
  const typeBuf = Buffer.from(type, 'ascii');
  const body = Buffer.concat([typeBuf, data]);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(CRC(body), 0);
  return Buffer.concat([len, body, crc]);
}

function writePng(file, width, height, pix, channels = 4) {
  const sig = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0); ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;                 // bit depth
  ihdr[9] = channels === 4 ? 6 : 2; // color type: 6 = truecolor+alpha, 2 = truecolor
  // 10,11,12 = compression, filter, interlace = 0
  const stride = width * channels;
  const raw = Buffer.alloc(height * (1 + stride));
  for (let y = 0; y < height; y++) {
    raw[y * (1 + stride)] = 0; // filter: none
    pix.copy(raw, y * (1 + stride) + 1, y * stride, (y + 1) * stride);
  }
  const idat = zlib.deflateSync(raw, { level: 9 });
  fs.writeFileSync(file, Buffer.concat([sig, chunk('IHDR', ihdr), chunk('IDAT', idat), chunk('IEND', Buffer.alloc(0))]));
}

// Parse one ANSI art file into a grid of { r,g,b, cov } cells.
function parse(text) {
  const lines = text.replace(/\r/g, '').split('\n');
  while (lines.length && lines[lines.length - 1].trim() === '') lines.pop();
  const rows = [];
  let width = 0;
  const esc = /\x1b\[([0-9;]*)m/y;
  for (const line of lines) {
    const cells = [];
    let r = 200, g = 255, b = 200; // default fg
    let i = 0;
    while (i < line.length) {
      if (line[i] === '\x1b') {
        esc.lastIndex = i;
        const m = esc.exec(line);
        if (m) {
          const parts = m[1].split(';').map(Number);
          // handle 38;2;r;g;b truecolor sequences
          for (let k = 0; k < parts.length; k++) {
            if (parts[k] === 38 && parts[k + 1] === 2) { r = parts[k + 2] || 0; g = parts[k + 3] || 0; b = parts[k + 4] || 0; k += 4; }
          }
          i = esc.lastIndex;
          continue;
        }
      }
      cells.push({ r, g, b, cov: coverage(line[i]) });
      i++;
    }
    rows.push(cells);
    if (cells.length > width) width = cells.length;
  }
  return { rows, width, height: rows.length };
}

function convert(file) {
  const text = fs.readFileSync(file, 'utf8');
  const { rows, width, height } = parse(text);
  const ch = OPAQUE ? 3 : 4;
  const pix = Buffer.alloc(width * height * ch);
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const cell = rows[y][x];
      const i = (y * width + x) * ch;
      const cov = cell ? Math.pow(cell.cov, 0.7) : 0; // gamma lift faint glyphs
      if (OPAQUE) {
        // composite glyph color over the panel background -> full-bleed scene
        pix[i] = Math.round((cell ? cell.r : 0) * cov + BG[0] * (1 - cov));
        pix[i + 1] = Math.round((cell ? cell.g : 0) * cov + BG[1] * (1 - cov));
        pix[i + 2] = Math.round((cell ? cell.b : 0) * cov + BG[2] * (1 - cov));
      } else if (cell) {
        pix[i] = cell.r; pix[i + 1] = cell.g; pix[i + 2] = cell.b; pix[i + 3] = Math.round(255 * cov);
      } // else fully transparent (already zeroed)
    }
  }
  const id = path.basename(file, path.extname(file)).toLowerCase();
  const out = path.join(OUT_DIR, id + '.png');
  writePng(out, width, height, pix, ch);
  console.log(`${path.basename(file)} -> ${out}  (${width}x${height})`);
}

fs.mkdirSync(OUT_DIR, { recursive: true });
for (const f of fs.readdirSync(SRC).filter(x => x.endsWith('.txt')).sort()) {
  convert(path.join(SRC, f));
}
