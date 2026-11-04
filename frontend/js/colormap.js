/**
 * Paleta de calor perceptualmente uniforme (aprox. "inferno" de matplotlib):
 * negro → morado/azul (baja energía) → rojo → naranja → amarillo (picos).
 * Se precalcula una tabla (LUT) de 256 colores RGB para pintar rápido.
 */
const STOPS = [
  '#000004', '#1b0c41', '#4a0c6b', '#781c6d', '#a52c60',
  '#cf4446', '#ed6925', '#fb9b06', '#f7d13d', '#fcffa4',
];

function hexToRgb(hex) {
  const n = parseInt(hex.slice(1), 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

/** LUT plana: [r0, g0, b0, r1, g1, b1, ...] con 256 entradas. */
export const LUT = (() => {
  const rgb = STOPS.map(hexToRgb);
  const lut = new Uint8ClampedArray(256 * 3);
  const segments = rgb.length - 1;
  for (let i = 0; i < 256; i++) {
    const t = (i / 255) * segments;
    const s = Math.min(Math.floor(t), segments - 1);
    const local = t - s;
    for (let c = 0; c < 3; c++) {
      lut[i * 3 + c] = rgb[s][c] + (rgb[s + 1][c] - rgb[s][c]) * local;
    }
  }
  return lut;
})();

/** Pinta la barra de color de la leyenda (de izquierda=mín a derecha=máx). */
export function drawColorbar(canvas) {
  const ctx = canvas.getContext('2d');
  const img = ctx.createImageData(256, 1);
  for (let i = 0; i < 256; i++) {
    img.data.set([LUT[i * 3], LUT[i * 3 + 1], LUT[i * 3 + 2], 255], i * 4);
  }
  canvas.width = 256;
  canvas.height = 1;
  ctx.putImageData(img, 0, 0);
}
