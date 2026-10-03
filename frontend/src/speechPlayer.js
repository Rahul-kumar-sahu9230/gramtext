// Web twin of android/.../audio/SpeechPlayer.kt.
// Streaming: sentences from /api/read play back-to-back as they arrive; when the stream ends
// they are joined so "Read aloud" replays instantly without the network.

function silentWavUrl() {
  const buf = new ArrayBuffer(46);
  const v = new DataView(buf);
  const w = (o, s) => [...s].forEach((c, i) => v.setUint8(o + i, c.charCodeAt(0)));
  w(0, "RIFF"); v.setUint32(4, 38, true); w(8, "WAVE"); w(12, "fmt ");
  v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, 1, true);
  v.setUint32(24, 8000, true); v.setUint32(28, 16000, true); v.setUint16(32, 2, true);
  v.setUint16(34, 16, true); w(36, "data"); v.setUint32(40, 2, true);
  return URL.createObjectURL(new Blob([buf], { type: "audio/wav" }));
}

export class SpeechPlayer {
  constructor() {
    this.audio = new Audio();
    this.audio.addEventListener("ended", () => this.onEnded());
    this.audio.addEventListener("error", () => this.onEnded());
    this.queue = [];
    this.chunks = [];
    this.cachedText = null;
    this.cachedUrl = null;
    this.speed = 1;
    this.onState = () => {};
    this.mode = "idle"; // idle | stream | replay
    this.replayDone = null;
    this.unlocked = false;
  }

  /** Browsers allow sound only after a tap: call this from a tap handler once. */
  unlock() {
    if (this.unlocked) return;
    this.unlocked = true;
    this.audio.src = silentWavUrl();
    this.audio.play().catch(() => {});
  }

  hasAudioFor(text) {
    return this.cachedText === text && Boolean(this.cachedUrl);
  }

  store(text, blob) {
    this.release();
    if (this.cachedUrl) URL.revokeObjectURL(this.cachedUrl);
    this.cachedUrl = URL.createObjectURL(blob);
    this.cachedText = text;
  }

  play(speed, onDone) {
    this.release();
    this.mode = "replay";
    this.replayDone = onDone;
    this.audio.src = this.cachedUrl;
    this.audio.playbackRate = speed;
    return this.audio.play();
  }

  /* ---------- streaming ---------- */

  startStream(speed, onState) {
    this.clear();
    this.speed = speed;
    this.onState = onState;
    this.chunks = [];
  }

  addChunk(blob, play) {
    this.chunks.push(blob);
    if (!play) return;
    this.queue.push(URL.createObjectURL(blob));
    if (this.mode !== "stream") this.playNext();
  }

  endStream(text) {
    if (!this.chunks.length) return;
    if (this.cachedUrl) URL.revokeObjectURL(this.cachedUrl);
    this.cachedUrl = URL.createObjectURL(new Blob(this.chunks, { type: "audio/mpeg" }));
    this.cachedText = text;
  }

  playNext() {
    const url = this.queue.shift();
    if (!url) {
      this.mode = "idle";
      this.onState(false);
      return;
    }
    this.mode = "stream";
    this.audio.src = url;
    this.audio.playbackRate = this.speed;
    this.audio.play().then(() => this.onState(true)).catch(() => this.playNext());
  }

  onEnded() {
    if (this.mode === "stream") this.playNext();
    else if (this.mode === "replay") {
      this.mode = "idle";
      this.replayDone?.();
    }
  }

  stop() {
    this.queue.forEach((u) => URL.revokeObjectURL(u));
    this.queue = [];
    this.release();
  }

  clear() {
    this.stop();
    this.cachedText = null;
  }

  release() {
    this.mode = "idle";
    this.audio.pause();
  }
}
