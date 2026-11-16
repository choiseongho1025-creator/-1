// GIF / WebP 의 1회 재생 길이(ms)를 파일에서 직접 계산. 정지 이미지는 0.
function gifDur(b) {
  let p = 13;
  const packed = b[10];
  if (packed & 0x80) p += 3 * (1 << ((packed & 7) + 1));
  let cs = 0;
  while (p < b.length) {
    const t = b[p];
    if (t === 0x21) {
      if (p + 5 < b.length && b[p + 1] === 0xf9) {
        const d = b[p + 4] | (b[p + 5] << 8);
        cs += d <= 1 ? 10 : d;
      }
      p += 2;
      while (p < b.length && b[p] !== 0) p += b[p] + 1;
      p++;
    } else if (t === 0x2c) {
      if (p + 10 > b.length) break;
      const pk = b[p + 9];
      p += 10;
      if (pk & 0x80) p += 3 * (1 << ((pk & 7) + 1));
      p++;
      while (p < b.length && b[p] !== 0) p += b[p] + 1;
      p++;
    } else break;
  }
  return cs * 10;
}

function webpDur(b) {
  let p = 12;
  let total = 0;
  while (p + 8 <= b.length) {
    const id = b.toString('latin1', p, p + 4);
    const len = b.readUInt32LE(p + 4);
    if (id === 'ANMF' && p + 8 + 15 <= b.length) {
      total += b[p + 20] | (b[p + 21] << 8) | (b[p + 22] << 16);
    }
    p += 8 + len + (len & 1);
  }
  return total;
}

function animDuration(buf) {
  try {
    if (buf.length > 13 && buf.toString('latin1', 0, 3) === 'GIF') return gifDur(buf);
    if (buf.length > 16 && buf.toString('latin1', 0, 4) === 'RIFF' && buf.toString('latin1', 8, 12) === 'WEBP') return webpDur(buf);
  } catch (e) {}
  return 0;
}

module.exports = { animDuration };
