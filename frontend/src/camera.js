// Web twin of android/.../ui/ScanBox.kt + LiveAnalyzer.kt + data/ImagePrep.kt.
// Same box, same timings, same thresholds, same crop and JPEG settings as the APK.

/** The scan box as fractions of the visible camera area (identical to ScanBox.kt). */
export const SCAN_BOX = { left: 0.04, top: 0.1, right: 0.96, bottom: 0.62 };

const SIG_W = 32;
const SIG_H = 24;
const CHECK_MS = 200;
const MIN_GAP_MS = 600;
const STEADY = 7;
const CHANGED = 10;
const LIVE_MAX_SIDE = 1024; // live frames: box only, JPEG 0.80
const PHOTO_MAX_SIDE = 1280; // shutter/gallery photos: JPEG 0.85

/**
 * The video is shown with object-fit: cover (like PreviewView FILL_CENTER). Return the scan box
 * in VIDEO pixels, so the crop is exactly what the user sees inside the box.
 */
export function boxInVideo(video, containerWidth, containerHeight) {
  const vw = video.videoWidth;
  const vh = video.videoHeight;
  if (!vw || !vh || !containerWidth || !containerHeight) return null;
  const scale = Math.max(containerWidth / vw, containerHeight / vh);
  const offsetX = (vw * scale - containerWidth) / 2;
  const offsetY = (vh * scale - containerHeight) / 2;
  const x = (containerWidth * SCAN_BOX.left + offsetX) / scale;
  const y = (containerHeight * SCAN_BOX.top + offsetY) / scale;
  const w = (containerWidth * (SCAN_BOX.right - SCAN_BOX.left)) / scale;
  const h = (containerHeight * (SCAN_BOX.bottom - SCAN_BOX.top)) / scale;
  return { x, y, w, h };
}

function drawRegion(source, region, maxSide) {
  const scale = Math.min(1, maxSide / Math.max(region.w, region.h));
  const canvas = document.createElement("canvas");
  canvas.width = Math.max(1, Math.round(region.w * scale));
  canvas.height = Math.max(1, Math.round(region.h * scale));
  canvas.getContext("2d").drawImage(source, region.x, region.y, region.w, region.h, 0, 0, canvas.width, canvas.height);
  return canvas;
}

const toJpeg = (canvas, quality) =>
  new Promise((resolve) => canvas.toBlob((blob) => resolve(blob), "image/jpeg", quality));

/** Shutter photo: the scan box at full camera resolution (max 1280 px), like ImagePrep(cropToBox). */
export async function captureBox(video, containerWidth, containerHeight) {
  const region = boxInVideo(video, containerWidth, containerHeight);
  if (!region) return null;
  const canvas = drawRegion(video, region, PHOTO_MAX_SIDE);
  return { blob: await toJpeg(canvas, 0.85), preview: canvas.toDataURL("image/jpeg", 0.8) };
}

/** Gallery photo: whole image, EXIF rotation applied, max 1280 px (like ImagePrep.fromUri). */
export async function prepareFile(file) {
  const bitmap = await createImageBitmap(file, { imageOrientation: "from-image" });
  const canvas = drawRegion(bitmap, { x: 0, y: 0, w: bitmap.width, h: bitmap.height }, PHOTO_MAX_SIDE);
  bitmap.close?.();
  return { blob: await toJpeg(canvas, 0.85), preview: canvas.toDataURL("image/jpeg", 0.8) };
}

/**
 * Same logic as LiveAnalyzer.kt: every 0.2 s sample the box (32x24 grayscale); when the box
 * content is steady AND different from the last frame sent, send a JPEG of the box only.
 */
export class LiveAnalyzer {
  constructor({ enabled, busy, onFrame }) {
    this.enabled = enabled;
    this.busy = busy;
    this.onFrame = onFrame;
    this.previous = null;
    this.sent = null;
    this.lastScan = 0;
    this.timer = null;
    this.sigCanvas = document.createElement("canvas");
    this.sigCanvas.width = SIG_W;
    this.sigCanvas.height = SIG_H;
  }

  start(video, getSize) {
    this.stop();
    this.timer = setInterval(() => this.tick(video, getSize()), CHECK_MS);
  }

  stop() {
    clearInterval(this.timer);
    this.timer = null;
  }

  /** Forget the last label so the same one is read again (e.g. Live switched back on). */
  reset() {
    this.sent = null;
  }

  async tick(video, size) {
    if (!this.enabled() || video.readyState < 2) return;
    const region = boxInVideo(video, size.width, size.height);
    if (!region) return;

    const signature = this.signature(video, region);
    const steady = diff(signature, this.previous) < STEADY;
    this.previous = signature;
    const changed = diff(signature, this.sent) > CHANGED;
    const now = performance.now();
    if (!steady || !changed || this.busy() || now - this.lastScan < MIN_GAP_MS) return;

    this.sent = signature;
    this.lastScan = now;
    const blob = await toJpeg(drawRegion(video, region, LIVE_MAX_SIDE), 0.8);
    if (blob) this.onFrame(blob);
  }

  signature(video, region) {
    const ctx = this.sigCanvas.getContext("2d", { willReadFrequently: true });
    ctx.drawImage(video, region.x, region.y, region.w, region.h, 0, 0, SIG_W, SIG_H);
    const data = ctx.getImageData(0, 0, SIG_W, SIG_H).data;
    const out = new Float32Array(SIG_W * SIG_H);
    for (let i = 0; i < out.length; i++) out[i] = (data[i * 4] + data[i * 4 + 1] + data[i * 4 + 2]) / 3;
    return out;
  }
}

function diff(a, b) {
  if (!b || a.length !== b.length) return 255;
  let sum = 0;
  for (let i = 0; i < a.length; i++) sum += Math.abs(a[i] - b[i]);
  return sum / a.length;
}

/** Same label as before? (identical to sameLabel() in GramViewModel.kt) */
export function sameLabel(sentence, previous) {
  const a = sentence.replace(/\s+/g, " ").trim().slice(0, 120);
  const b = previous.replace(/\s+/g, " ").trim().slice(0, a.length + 4);
  if (!a || !b) return false;
  let prev = Array.from({ length: b.length + 1 }, (_, j) => j);
  for (let i = 1; i <= a.length; i++) {
    const cur = [i];
    for (let j = 1; j <= b.length; j++) {
      cur[j] = Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
    }
    prev = cur;
  }
  return prev[b.length] <= Math.max(2, Math.round(a.length * 0.2));
}
