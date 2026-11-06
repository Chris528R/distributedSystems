/**
 * Renderizador del espectrograma sobre <canvas>.
 *
 * Diseño para rendimiento:
 *  1. La matriz llega ya en dB (FFTWorker usa magnitudeSpectrumDb); solo se
 *     calcula su máximo para fijar la escala de color.
 *  2. Se pinta en un canvas fuera de pantalla de tamaño (columnas x bins),
 *     1 píxel por celda, usando ImageData + LUT (sin llamadas por celda a la API 2D).
 *  3. En cada redimensionado solo se escala esa imagen con drawImage() y se
 *     redibujan los ejes; el movimiento del cursor se dibuja en un canvas
 *     superpuesto, sin repintar el mapa de calor.
 */
import { LUT } from './colormap.js';

const MARGIN = { top: 10, right: 14, bottom: 34, left: 62 };
/** Límite de columnas del canvas fuera de pantalla (los navegadores limitan el ancho). */
const MAX_IMAGE_COLUMNS = 8192;

export class SpectrogramView {
  /**
   * @param {object} els
   * @param {HTMLElement}       els.wrap
   * @param {HTMLCanvasElement} els.canvas   mapa de calor + ejes
   * @param {HTMLCanvasElement} els.overlay  cruz del cursor
   * @param {HTMLElement}       els.tooltip
   */
  constructor({ wrap, canvas, overlay, tooltip }) {
    this.wrap = wrap;
    this.canvas = canvas;
    this.overlay = overlay;
    this.tooltip = tooltip;
    this.record = null;
    this.db = null;          // Float32Array en dB, frame-major (record.data)
    this.maxDb = 0;
    this.dynamicRange = 80;  // dB visibles por debajo del máximo
    this.image = document.createElement('canvas');
    this.plot = { x: 0, y: 0, w: 0, h: 0 };

    new ResizeObserver(() => this.#resize()).observe(wrap);
    overlay.addEventListener('pointermove', (e) => this.#onPointer(e));
    overlay.addEventListener('pointerleave', () => this.#clearCursor());
  }

  /** Muestra un nuevo espectrograma. */
  setData(record) {
    this.record = record;
    const { data } = record;
    let max = -Infinity;
    for (let i = 0; i < data.length; i++) {
      if (data[i] > max) max = data[i];
    }
    this.db = data;
    this.maxDb = max;
    this.#renderImage();
    this.#draw();
  }

  setDynamicRange(range) {
    this.dynamicRange = range;
    if (this.record) {
      this.#renderImage();
      this.#draw();
    }
  }

  get minDb() { return this.maxDb - this.dynamicRange; }

  // ------------------------------------------------------------------
  // Pintado
  // ------------------------------------------------------------------

  /** Pinta la matriz (en dB) en el canvas fuera de pantalla, 1 px por celda. */
  #renderImage() {
    const { frames, bins } = this.record;
    const cols = Math.min(frames, MAX_IMAGE_COLUMNS);
    const framesPerCol = frames / cols;
    this.image.width = cols;
    this.image.height = bins;

    const ctx = this.image.getContext('2d');
    const img = ctx.createImageData(cols, bins);
    const px = img.data;
    const min = this.minDb;
    const scale = 255 / this.dynamicRange;

    for (let c = 0; c < cols; c++) {
      // Si hay más frames que columnas, cada columna toma el máximo de su grupo
      // (así no se pierden transitorios al reducir).
      const f0 = Math.floor(c * framesPerCol);
      const f1 = Math.max(f0 + 1, Math.floor((c + 1) * framesPerCol));
      for (let b = 0; b < bins; b++) {
        let v = -Infinity;
        for (let f = f0; f < f1; f++) {
          const x = this.db[f * bins + b];
          if (x > v) v = x;
        }
        const idx = Math.max(0, Math.min(255, ((v - min) * scale) | 0)) * 3;
        // Bin 0 (frecuencia baja) abajo: se invierte la fila.
        const p = ((bins - 1 - b) * cols + c) * 4;
        px[p] = LUT[idx];
        px[p + 1] = LUT[idx + 1];
        px[p + 2] = LUT[idx + 2];
        px[p + 3] = 255;
      }
    }
    ctx.putImageData(img, 0, 0);
  }

  #resize() {
    const dpr = window.devicePixelRatio || 1;
    const { clientWidth: w, clientHeight: h } = this.wrap;
    for (const c of [this.canvas, this.overlay]) {
      c.width = Math.round(w * dpr);
      c.height = Math.round(h * dpr);
      c.getContext('2d').setTransform(dpr, 0, 0, dpr, 0, 0);
    }
    this.plot = {
      x: MARGIN.left,
      y: MARGIN.top,
      w: Math.max(1, w - MARGIN.left - MARGIN.right),
      h: Math.max(1, h - MARGIN.top - MARGIN.bottom),
    };
    this.#draw();
  }

  #draw() {
    const ctx = this.canvas.getContext('2d');
    const { clientWidth: w, clientHeight: h } = this.wrap;
    ctx.clearRect(0, 0, w, h);
    if (!this.record) return;

    const { x, y, w: pw, h: ph } = this.plot;
    // Suavizado solo al reducir; al ampliar se ven las celdas nítidas.
    ctx.imageSmoothingEnabled = this.image.width > pw;
    ctx.drawImage(this.image, x, y, pw, ph);
    this.#drawAxes(ctx);
  }

  #drawAxes(ctx) {
    const { x, y, w, h } = this.plot;
    const style = getComputedStyle(document.documentElement);
    const muted = style.getPropertyValue('--muted').trim() || '#7d8aa5';
    const border = style.getPropertyValue('--border').trim() || '#243049';

    ctx.strokeStyle = border;
    ctx.lineWidth = 1;
    ctx.strokeRect(x + 0.5, y + 0.5, w - 1, h - 1);

    ctx.fillStyle = muted;
    ctx.font = '11px ui-monospace, Menlo, Consolas, monospace';

    // Eje X: tiempo
    const xAxis = this.#xAxis();
    ctx.textAlign = 'center';
    ctx.textBaseline = 'top';
    for (const v of niceTicks(0, xAxis.max, Math.max(2, Math.floor(w / 90)))) {
      const px = x + (v / xAxis.max) * w;
      ctx.fillRect(Math.round(px), y + h, 1, 4);
      ctx.fillText(xAxis.format(v), px, y + h + 6);
    }
    ctx.fillText(xAxis.label, x + w / 2, y + h + 20);

    // Eje Y: frecuencia
    const yAxis = this.#yAxis();
    ctx.textAlign = 'right';
    ctx.textBaseline = 'middle';
    for (const v of niceTicks(0, yAxis.max, Math.max(2, Math.floor(h / 50)))) {
      const py = y + h - (v / yAxis.max) * h;
      ctx.fillRect(x - 4, Math.round(py), 4, 1);
      ctx.fillText(yAxis.format(v), x - 7, py);
    }
    ctx.save();
    ctx.translate(12, y + h / 2);
    ctx.rotate(-Math.PI / 2);
    ctx.textAlign = 'center';
    ctx.fillText(yAxis.label, 0, 0);
    ctx.restore();
  }

  // ------------------------------------------------------------------
  // Conversión índice ↔ unidades físicas
  // ------------------------------------------------------------------

  /** Segundos al inicio de un frame (null si se desconoce el sampleRate). */
  frameToSeconds(frame) {
    const { sampleRate, hopSize } = this.record;
    return sampleRate ? (frame * hopSize) / sampleRate : null;
  }

  /** Frecuencia central de un bin en Hz (resolución = sampleRate / windowSize). */
  binToHz(bin) {
    const { sampleRate, windowSize } = this.record;
    return sampleRate ? (bin * sampleRate) / windowSize : null;
  }

  #xAxis() {
    const { frames, sampleRate } = this.record;
    if (sampleRate) {
      const max = this.frameToSeconds(frames);
      return { max, label: 'Tiempo (s)', format: (v) => formatNumber(v, max) };
    }
    return { max: frames, label: 'Frame', format: (v) => String(Math.round(v)) };
  }

  #yAxis() {
    const { bins, sampleRate } = this.record;
    if (sampleRate) {
      const max = this.binToHz(bins);
      return max >= 2000
        ? { max, label: 'Frecuencia (kHz)', format: (v) => formatNumber(v / 1000, max / 1000) }
        : { max, label: 'Frecuencia (Hz)', format: (v) => String(Math.round(v)) };
    }
    return { max: bins, label: 'Bin', format: (v) => String(Math.round(v)) };
  }

  // ------------------------------------------------------------------
  // Interacción: cruz + tooltip
  // ------------------------------------------------------------------

  #onPointer(e) {
    if (!this.record) return;
    const rect = this.overlay.getBoundingClientRect();
    const mx = e.clientX - rect.left;
    const my = e.clientY - rect.top;
    const { x, y, w, h } = this.plot;
    if (mx < x || mx >= x + w || my < y || my >= y + h) {
      this.#clearCursor();
      return;
    }

    const { frames, bins } = this.record;
    const frame = Math.min(frames - 1, Math.floor(((mx - x) / w) * frames));
    const bin = Math.min(bins - 1, Math.floor(((y + h - my) / h) * bins));
    const value = this.db[frame * bins + bin];

    // Cruz
    const ctx = this.overlay.getContext('2d');
    ctx.clearRect(0, 0, this.wrap.clientWidth, this.wrap.clientHeight);
    ctx.strokeStyle = 'rgba(255,255,255,.45)';
    ctx.setLineDash([4, 4]);
    ctx.beginPath();
    ctx.moveTo(Math.round(mx) + 0.5, y);
    ctx.lineTo(Math.round(mx) + 0.5, y + h);
    ctx.moveTo(x, Math.round(my) + 0.5);
    ctx.lineTo(x + w, Math.round(my) + 0.5);
    ctx.stroke();

    // Tooltip
    const t = this.frameToSeconds(frame);
    const hz = this.binToHz(bin);
    const timeLine = t !== null ? `${t.toFixed(3)} s <span class="muted">(frame ${frame})</span>` : `frame ${frame}`;
    const freqLine = hz !== null ? `${formatHz(hz)} <span class="muted">(bin ${bin})</span>` : `bin ${bin}`;
    this.tooltip.innerHTML =
      `t = ${timeLine}<br>f = ${freqLine}<br><b>${value.toFixed(1)} dB</b>`;
    this.tooltip.hidden = false;

    // Posiciona el tooltip evitando que se salga del panel
    const tw = this.tooltip.offsetWidth;
    const th = this.tooltip.offsetHeight;
    const left = mx + 14 + tw > this.wrap.clientWidth ? mx - tw - 14 : mx + 14;
    const top = my + 14 + th > this.wrap.clientHeight ? my - th - 14 : my + 14;
    this.tooltip.style.transform = `translate(${left}px, ${top}px)`;
  }

  #clearCursor() {
    this.overlay.getContext('2d').clearRect(0, 0, this.wrap.clientWidth, this.wrap.clientHeight);
    this.tooltip.hidden = true;
  }
}

// ----------------------------------------------------------------------
// Utilidades
// ----------------------------------------------------------------------

/** Marcas "redondas" (1, 2, 5 × 10^n) entre min y max. */
function niceTicks(min, max, count) {
  const span = max - min;
  if (span <= 0) return [min];
  const rough = span / count;
  const pow = 10 ** Math.floor(Math.log10(rough));
  const step = [1, 2, 5, 10].map((m) => m * pow).find((s) => s >= rough);
  const ticks = [];
  for (let v = Math.ceil(min / step) * step; v <= max + step * 1e-9; v += step) {
    ticks.push(+v.toPrecision(12));
  }
  return ticks;
}

function formatNumber(v, max) {
  return max < 2 ? v.toFixed(2) : max < 20 ? v.toFixed(1) : String(Math.round(v));
}

function formatHz(hz) {
  return hz >= 1000 ? `${(hz / 1000).toFixed(2)} kHz` : `${hz.toFixed(1)} Hz`;
}
