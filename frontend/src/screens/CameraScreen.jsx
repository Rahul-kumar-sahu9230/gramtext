// Web twin of android/.../ui/CameraScreen.kt: live camera, big scan box, read-out panel,
// gallery button bottom-left, shutter in the center.
import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { Camera, Images, LoaderCircle, Settings, Square, Volume2, Zap } from "lucide-react";
import { LiveAnalyzer, SCAN_BOX, captureBox, prepareFile } from "../camera";

export default function CameraScreen({
  s, state, liveBusy, onLiveFrame, onToggleLive, onRetryServer, onSettings,
  onCaptured, onGallery, onImageError, onStart, onStopSpeech,
}) {
  const containerRef = useRef(null);
  const videoRef = useRef(null);
  const fileRef = useRef(null);
  const [size, setSize] = useState({ width: 0, height: 0 });
  const [camera, setCamera] = useState("starting"); // starting | on | denied | insecure
  const [busy, setBusy] = useState(false);
  const sizeRef = useRef(size);
  sizeRef.current = size;
  const live = useRef(state.liveMode && state.started);
  live.current = state.liveMode && state.started;

  /* container size (box + crop are fractions of it) */
  useLayoutEffect(() => {
    const el = containerRef.current;
    const observer = new ResizeObserver(([entry]) =>
      setSize({ width: entry.contentRect.width, height: entry.contentRect.height })
    );
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  /* camera */
  const startCamera = async () => {
    if (!window.isSecureContext || !navigator.mediaDevices?.getUserMedia) {
      setCamera("insecure");
      return;
    }
    setCamera("starting");
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: false,
        video: { facingMode: { ideal: "environment" }, width: { ideal: 1920 }, height: { ideal: 1080 } },
      });
      const video = videoRef.current;
      video.srcObject = stream;
      await video.play().catch(() => {});
      setCamera("on");
    } catch {
      setCamera("denied");
    }
  };

  useEffect(() => {
    startCamera();
    const video = videoRef.current;
    return () => video?.srcObject?.getTracks().forEach((t) => t.stop());
  }, []);

  /* live analyzer (same logic and timings as LiveAnalyzer.kt). Created once per camera
     session; callbacks go through refs (like rememberUpdatedState in the APK), so re-renders
     never reset its memory of the last label. */
  const analyzer = useRef(null);
  const busyRef = useRef(liveBusy);
  busyRef.current = liveBusy;
  const playingRef = useRef(state.playing);
  playingRef.current = state.playing;
  const frameRef = useRef(onLiveFrame);
  frameRef.current = onLiveFrame;
  useEffect(() => {
    if (camera !== "on") return;
    analyzer.current = new LiveAnalyzer({
      enabled: () => live.current,
      busy: () => busyRef.current(),
      playing: () => playingRef.current,
      onFrame: (blob) => frameRef.current(blob),
    });
    analyzer.current.start(videoRef.current, () => sizeRef.current);
    return () => analyzer.current?.stop();
  }, [camera]);
  useEffect(() => {
    if (state.liveMode) analyzer.current?.reset();
  }, [state.liveMode]);

  async function shutter() {
    onStart();
    setBusy(true);
    const photo = await captureBox(videoRef.current, size.width, size.height);
    setBusy(false);
    if (photo) onCaptured(photo);
  }

  async function onFile(event) {
    const file = event.target.files?.[0];
    event.target.value = "";
    if (!file) return;
    try {
      onGallery(await prepareFile(file));
    } catch {
      onImageError();
    }
  }

  const { width: W, height: H } = size;
  const box = {
    x: W * SCAN_BOX.left, y: H * SCAN_BOX.top,
    w: W * (SCAN_BOX.right - SCAN_BOX.left), h: H * (SCAN_BOX.bottom - SCAN_BOX.top),
  };
  const boxColor = state.liveReading ? "#0891B2" : "#FFFFFF";
  const corner = 34;
  const server = {
    online: ["#34D399", s.serverOnline],
    offline: ["#F87171", s.serverOffline],
    checking: ["#FDE68A", s.serverChecking],
  }[state.server];
  const status = state.liveReading
    ? s.liveReadingNow
    : state.liveSpokeIn != null && state.liveText
      ? `${s.spokeIn}: ${state.liveSpokeIn.toFixed(1)} s`
      : s.boxHint;

  const galleryInput = (
    <input ref={fileRef} type="file" accept="image/*" onChange={onFile} hidden />
  );

  if (camera === "denied" || camera === "insecure") {
    return (
      <div className="permission">
        <Camera size={72} color="#5B21B6" />
        <p className="title-md">
          {camera === "insecure" ? "Camera needs an https:// page. Open the https:// address." : s.cameraPermission}
        </p>
        <button type="button" className="btn primary block" onClick={startCamera}>{s.grantPermission}</button>
        <button type="button" className="btn outline block" onClick={() => fileRef.current?.click()}>
          <Images size={20} /> {s.pickGallery}
        </button>
        {galleryInput}
      </div>
    );
  }

  return (
    <div className="camera" ref={containerRef}>
      <video ref={videoRef} playsInline muted autoPlay />

      {W > 0 && (
        <svg className="overlay" width={W} height={H} aria-hidden="true">
          <defs>
            <mask id="box-hole">
              <rect width={W} height={H} fill="white" />
              <rect x={box.x} y={box.y} width={box.w} height={box.h} rx="22" fill="black" />
            </mask>
          </defs>
          <rect width={W} height={H} fill="rgba(0,0,0,0.55)" mask="url(#box-hole)" />
          <rect x={box.x} y={box.y} width={box.w} height={box.h} rx="22" fill="none"
            stroke={boxColor} strokeOpacity="0.7" strokeWidth="2" />
          <g stroke={boxColor} strokeWidth="6" strokeLinecap="round" fill="none">
            <path d={`M${box.x} ${box.y + corner}V${box.y}H${box.x + corner}`} />
            <path d={`M${box.x + box.w - corner} ${box.y}H${box.x + box.w}V${box.y + corner}`} />
            <path d={`M${box.x} ${box.y + box.h - corner}V${box.y + box.h}H${box.x + corner}`} />
            <path d={`M${box.x + box.w - corner} ${box.y + box.h}H${box.x + box.w}V${box.y + box.h - corner}`} />
          </g>
        </svg>
      )}

      {/* Top bar: server status (left), Live toggle + Settings (right) */}
      <div className="cam-top">
        <button type="button" className="pill" onClick={onRetryServer}>
          <span className="dot" style={{ background: server[0] }} />
          {server[1]}
        </button>
        <span className="grow" />
        <button
          type="button"
          className={`live-chip ${state.liveMode ? "on" : ""}`}
          onClick={() => {
            onStart();
            onToggleLive(!state.liveMode);
          }}
          aria-pressed={state.liveMode}
        >
          <Zap size={18} /> {state.liveMode ? s.liveOn : s.liveOff}
        </button>
        <button type="button" className="round-btn" onClick={onSettings} aria-label={s.settings}>
          <Settings size={22} />
        </button>
      </div>

      {/* Read-out panel just below the box */}
      {H > 0 && (
        <div className="readout" style={{ top: H * SCAN_BOX.bottom + 10, maxHeight: H * 0.2 }}>
          <div className="readout-status">
            {state.liveReading ? <LoaderCircle size={18} className="spin" color="#5B21B6" />
              : state.playing ? <Volume2 size={20} color="#5B21B6" />
              : <Zap size={20} color="#5B21B6" />}
            <span>{status}</span>
          </div>
          {state.liveMode && state.liveText && <div className="readout-text" lang="hi">{state.liveText}</div>}
        </div>
      )}

      {/* Bottom row: gallery (left), shutter (center) */}
      <div className="cam-bottom">
        <div className="gallery-wrap">
          <button type="button" className="gallery-btn" onClick={() => { onStart(); fileRef.current?.click(); }}
            aria-label={s.pickGallery}>
            <Images size={30} />
          </button>
          <span>{s.gallery}</span>
        </div>
        <button type="button" className="shutter" onClick={shutter} disabled={busy || camera !== "on"} aria-label={s.capture}>
          {busy ? <LoaderCircle size={34} className="spin" /> : <Camera size={40} />}
        </button>
        {/* Stop: silence the voice at once. Always tappable (red while speaking/reading), so a tap
            between two sentences is never lost. */}
        <div className={`gallery-wrap ${state.playing || state.liveReading ? "" : "dim"}`}>
          <button type="button" className={`stop-btn ${state.playing || state.liveReading ? "on" : ""}`}
            onClick={onStopSpeech} aria-label={s.stop}>
            <Square size={28} fill="currentColor" />
          </button>
          <span>{s.stop}</span>
        </div>
      </div>
      {galleryInput}

      {/* Web-only: browsers need one tap before they may play sound */}
      {camera === "on" && !state.started && (
        <button type="button" className="start-overlay" onClick={onStart}>
          <span className="start-btn"><Volume2 size={26} /> {s.startReading}</span>
          <span className="start-hint">{s.startHint}</span>
        </button>
      )}
    </div>
  );
}
