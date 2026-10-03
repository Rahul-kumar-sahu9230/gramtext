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
const CHANGED_WHILE_PLAYING = 22; // while speaking, a shaky hand must not restart it
const BIG_MOTION = 40; // frame-to-frame: hand tremor 19-31, swapping a label / turning away 53-64
const LIVE_MAX_SIDE = 768; // live frames: box only, JPEG 0.80 (one Gemini tile: ~2x faster OCR)
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
  constructor({ enabled, busy, playing = () => false, onFrame }) {
    this.enabled = enabled;
    this.busy = busy;
    this.playing = playing;
    this.onFrame = onFrame;
    this.previous = null;
    this.sent = null;
    this.lastScan = 0;
    this.motionPeak = 0; // biggest frame-to-frame movement since the last frame sent
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
    const movement = diff(signature, this.previous);
    if (this.previous) this.motionPeak = Math.max(this.motionPeak, movement);
    const steady = movement < STEADY;
    this.previous = signature;
    // A swapped label can look alike to this small signature, but swapping it needs big movement.
    const changed =
      diff(signature, this.sent) > (this.playing() ? CHANGED_WHILE_PLAYING : CHANGED) || this.motionPeak > BIG_MOTION;
    const now = performance.now();
    if (!steady || !changed || this.busy() || now - this.lastScan < MIN_GAP_MS) return;

    this.sent = signature;
    this.lastScan = now;
    this.motionPeak = 0;
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

const NOT_WORD = /[^\p{L}\p{M}\p{N}]+/u; // \p{M} keeps Devanagari matras

const words = (text) => text.toLowerCase().split(NOT_WORD).filter(Boolean);

/** Equal, or one character inserted/removed/changed (only for words of 4+ characters). */
function nearlyEqual(a, b) {
  if (a === b) return true;
  if (Math.min(a.length, b.length) < 4 || Math.abs(a.length - b.length) > 1) return false;
  let i = 0;
  let j = 0;
  let edits = 0;
  while (i < a.length && j < b.length) {
    if (a[i] === b[j]) {
      i++;
      j++;
      continue;
    }
    if (++edits > 1) return false;
    if (a.length > b.length) i++;
    else if (a.length < b.length) j++;
    else {
      i++;
      j++;
    }
  }
  return edits + (a.length - i) + (b.length - j) <= 1;
}

/**
 * Same label as before? (identical to sameLabel() in LabelMatch.kt) Two camera frames of one
 * label rarely give identical OCR, so compare WORDS: same label when at least 60% of the new
 * words also appear (exactly or nearly) in the previous reading.
 */
export function sameLabel(text, previous) {
  const fresh = words(text);
  const old = words(previous);
  if (!fresh.length || !old.length) return false;
  const oldSet = new Set(old);
  const matched = fresh.filter((w) => oldSet.has(w) || old.some((o) => nearlyEqual(w, o))).length;
  return matched >= 0.6 * fresh.length;
}
