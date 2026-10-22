// Generated test PNG: the verification digits exist only in image pixels, never in model prompts.
import { deflateSync } from 'node:zlib'
const digits = ['111101101101111', '010110010010111', '111001111100111', '111001111001111', '101101111001001', '111100111001111', '111100111101111', '111001001001001', '111101111101111', '111101111001111']
function crc32(bytes) {
  let crc = 0xffffffff
  for (const byte of bytes) { crc ^= byte; for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0) }
  return (crc ^ 0xffffffff) >>> 0
}
function chunk(type, data) {
  const name = Buffer.from(type), length = Buffer.alloc(4), crc = Buffer.alloc(4)
  length.writeUInt32BE(data.length); crc.writeUInt32BE(crc32(Buffer.concat([name, data])))
  return Buffer.concat([length, name, data, crc])
}
export function digitImage(code) {
  const scale = 14, margin = 28, width = margin * 2 + code.length * scale * 4, height = margin * 2 + scale * 5
  const stride = width * 3 + 1, pixels = Buffer.alloc(height * stride, 255)
  for (let row = 0; row < height; row++) pixels[row * stride] = 0
  for (let index = 0; index < code.length; index++) for (let row = 0; row < 5; row++) for (let col = 0; col < 3; col++) {
    if (digits[Number(code[index])][row * 3 + col] !== '1') continue
    for (let y = 0; y < scale; y++) for (let x = 0; x < scale; x++) {
      const at = (margin + row * scale + y) * stride + 1 + (margin + index * scale * 4 + col * scale + x) * 3
      pixels.fill(0, at, at + 3)
    }
  }
  const header = Buffer.alloc(13); header.writeUInt32BE(width); header.writeUInt32BE(height, 4); header[8] = 8; header[9] = 2
  return Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]), chunk('IHDR', header), chunk('IDAT', deflateSync(pixels)), chunk('IEND', Buffer.alloc(0))])
}
