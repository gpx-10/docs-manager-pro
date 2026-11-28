// Builds the test PDFs in app/src/androidTest/assets that need special content.
// Needs pdf-lib, e.g. from the llmlabs project: NODE_PATH=../llmlabs/node_modules node tools/make-fixtures.cjs app/src/androidTest/assets
const { PDFDocument, StandardFonts } = require('pdf-lib');
const fs = require('fs');
const zlib = require('zlib');

/** A noisy colour photo-like PNG, which compresses badly as PNG and well as a smaller JPEG. */
function photoPng(w, h) {
  const raw = Buffer.alloc((w * 3 + 1) * h);
  let seed = 7;
  const rnd = () => (seed = (seed * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff;
  for (let y = 0; y < h; y++) {
    raw[y * (w * 3 + 1)] = 0;
    for (let x = 0; x < w; x++) {
      const o = y * (w * 3 + 1) + 1 + x * 3;
      const n = rnd() * 40;
      raw[o] = (x / w) * 200 + n; raw[o + 1] = (y / h) * 200 + n; raw[o + 2] = 120 + n;
    }
  }
  const chunk = (type, data) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
    const td = Buffer.concat([Buffer.from(type), data]);
    const crc = Buffer.alloc(4); crc.writeUInt32BE(zlib.crc32 ? zlib.crc32(td) >>> 0 : crc32(td));
    return Buffer.concat([len, td, crc]);
  };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 2;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]);
}
function crc32(buf) {
  let c, crc = 0xffffffff;
  for (let n = 0; n < buf.length; n++) { c = (crc ^ buf[n]) & 0xff; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; crc = (crc >>> 8) ^ c; }
  return (crc ^ 0xffffffff) >>> 0;
}

(async () => {
  const out = process.argv[2];
  // big-photo.pdf: one 400x400 pt page showing a 1200x1200 px photo (216 dpi).
  const d = await PDFDocument.create();
  const img = await d.embedPng(photoPng(1200, 1200));
  const page = d.addPage([400, 400]);
  page.drawImage(img, { x: 0, y: 0, width: 400, height: 400 });
  fs.writeFileSync(out + '/big-photo.pdf', await d.save());

  // damaged.pdf: three-pages.pdf with its cross-reference table wrecked.
  const ok = fs.readFileSync(out + '/three-pages.pdf', 'latin1');
  const at = ok.lastIndexOf('xref');
  const broken = at > 0 ? ok.slice(0, at) + 'xref\nGARBAGE GARBAGE\n' + ok.slice(at + 4).replace(/startxref\s*\d+/, 'startxref\n999999') : ok.replace(/startxref\s*\d+/, 'startxref\n999999');
  fs.writeFileSync(out + '/damaged.pdf', Buffer.from(broken, 'latin1'));

  // wide.png: a 600x400 gradient photo. Red grows to the right, green grows downwards,
  // so the colour in each corner shows whether an image was turned or flipped.
  fs.writeFileSync(out + '/wide.png', photoPng(600, 400));
  // wide.jpg, wide.heic and wide-exif6.jpg are made from wide.png by tools/make-image-fixtures.sh (macOS sips).
})();
