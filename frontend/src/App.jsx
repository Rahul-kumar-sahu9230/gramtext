// Web twin of the Android app (android/.../GramViewModel.kt + MainActivity.kt).
// Same screens, same state, same flows, same strings — so testing one tests the other.
import { useCallback, useEffect, useRef, useState } from "react";
import { checkHealth, generateSpeech, getServerUrl, readAloud, sendFeedback, setServerUrl } from "./api";
import { sameLabel } from "./camera";
import { SpeechPlayer } from "./speechPlayer";
import { ENGLISH, HINDI } from "./strings";
import CameraScreen from "./screens/CameraScreen";
import ResultScreen from "./screens/ResultScreen";
import SettingsScreen from "./screens/SettingsScreen";

const PREFS_KEY = "gramtext.prefs";
const DEFAULT_PREFS = { language: "hi", autoRead: true, slowSpeech: false, showEngine: false, liveMode: true };

function loadPrefs() {
  try {
    return { ...DEFAULT_PREFS, ...JSON.parse(localStorage.getItem(PREFS_KEY) || "{}") };
  } catch {
    return DEFAULT_PREFS;
  }
}

function savePrefs(prefs) {
  try {
    localStorage.setItem(PREFS_KEY, JSON.stringify(prefs));
  } catch {
    // storage blocked: settings just won't persist
  }
}

const base64ToBlob = (b64) => new Blob([Uint8Array.from(atob(b64), (c) => c.charCodeAt(0))], { type: "audio/mpeg" });

export default function App() {
  const prefs = useRef(loadPrefs());
  const [state, setState] = useState(() => ({
    screen: "camera",
    server: "checking",
    preview: null,
    text: "",
    recognizedText: "",
    scanId: null,
    engine: null,
    confidence: null,
    scanning: false,
    loadingAudio: false,
    playing: false,
    feedbackGiven: false,
    askCorrection: false,
    liveText: "",
    liveReading: false,
    liveSpokeIn: null,
    serverUrl: getServerUrl(),
    message: null,
    started: false, // web-only: one tap unlocks sound
    ...prefs.current,
  }));
  const stateRef = useRef(state);
  stateRef.current = state;
  const update = useCallback((patch) => setState((s) => ({ ...s, ...(typeof patch === "function" ? patch(s) : patch) })), []);

  const player = useRef(null);
  if (!player.current) player.current = new SpeechPlayer();
  const liveBusy = useRef(false);
  // Bumped by the Stop button: audio still arriving for an older read is dropped.
  const liveGeneration = useRef(0);
  const liveAbort = useRef(null);
  const lastSpoken = useRef("");
  const scanRun = useRef(0);

  const strings = state.language === "en" ? ENGLISH : HINDI;
  const say = useCallback((text) => update({ message: { text, id: Date.now() } }), [update]);
  const sayError = useCallback(
    (err) => {
      if (err?.kind === "network") say((s) => s.networkError);
      else if (err?.kind === "timeout") say((s) => s.timeoutError);
      else say(() => err?.message || "");
    },
    [say]
  );

  const setPref = (key, value) => {
    prefs.current = { ...prefs.current, [key]: value };
    savePrefs(prefs.current);
    update({ [key]: value });
  };

  /* ---------------- server ---------------- */
  const checkServer = useCallback(
    async (url = getServerUrl()) => {
      update({ server: "checking" });
      update({ server: (await checkHealth(url)) ? "online" : "offline" });
    },
    [update]
  );
  useEffect(() => {
    // Render's free plan sleeps when idle and takes ~50 s to wake, so keep trying for a while.
    let cancelled = false;
    (async () => {
      update({ server: "checking" });
      for (let attempt = 0; attempt < 8 && !cancelled; attempt++) {
        if (await checkHealth(getServerUrl())) {
          if (!cancelled) update({ server: "online" });
          return;
        }
        await new Promise((resolve) => setTimeout(resolve, 2000));
      }
      if (!cancelled) update({ server: "offline" });
    })();
    return () => {
      cancelled = true;
    };
  }, [update]);

  /* ---------------- navigation ---------------- */
  const stopAudio = useCallback(() => {
    player.current.stop();
    update({ playing: false });
  }, [update]);

  const navigate = useCallback(
    (screen) => {
      if (screen !== "result") stopAudio();
      update({ screen });
    },
    [stopAudio, update]
  );

  const back = () => {
    if (stateRef.current.screen === "result") scanRun.current += 1;
    if (stateRef.current.screen !== "camera") navigate("camera");
  };

  const start = () => {
    player.current.unlock();
    update({ started: true });
  };

  /* ---------------- scanning (shutter / gallery -> result screen) ---------------- */
  async function onImageSelected(prepared) {
    start();
    const run = ++scanRun.current;
    const current = () => run === scanRun.current;
    player.current.clear();
    update({
      screen: "result", preview: prepared.preview, text: "", recognizedText: "", scanId: null,
      engine: null, confidence: null, scanning: true, playing: false, feedbackGiven: false, askCorrection: false,
    });
    const speed = stateRef.current.slowSpeech ? 0.8 : 1;
    const autoRead = stateRef.current.autoRead;
    player.current.startStream(speed, (playing) => update({ playing }));
    try {
      await readAloud(prepared.blob, (ev) => {
        if (!current()) return;
        if (ev.type === "text") {
          update({ text: ev.text, recognizedText: ev.text, engine: ev.engine || null, server: "online" });
        } else if (ev.type === "audio") {
          player.current.addChunk(base64ToBlob(ev.data), autoRead);
        } else if (ev.type === "error") {
          say(() => ev.detail);
        } else if (ev.type === "done") {
          player.current.endStream((ev.text || "").trim());
          update({
            text: ev.text, recognizedText: ev.text, scanning: false, scanId: ev.scan_id ?? null,
            engine: ev.engine || null, confidence: ev.confidence ?? null,
          });
          if (!ev.text?.trim()) say((s) => s.noText);
        }
      });
      if (current()) update({ scanning: false });
    } catch (err) {
      if (!current()) return;
      update({ scanning: false, ...(err.kind === "network" ? { server: "offline" } : {}) });
      sayError(err);
    }
  }

  /* ---------------- live reading (camera keeps running) ---------------- */
  function setLiveMode(value) {
    setPref("liveMode", value);
    if (!value) stopAudio();
    lastSpoken.current = "";
  }

  const onLiveFrame = useCallback(
    async (blob) => {
      if (liveBusy.current) return;
      liveBusy.current = true;
      const generation = liveGeneration.current;
      const abort = new AbortController();
      liveAbort.current = abort;
      const sentAt = performance.now();
      update({ liveReading: true });
      let decided = false;
      let skip = false;
      let latest = "";
      const speed = stateRef.current.slowSpeech ? 0.8 : 1;
      try {
        await readAloud(blob, (ev) => {
          if (skip || generation !== liveGeneration.current) return; // stopped by the user
          if (ev.type === "text") {
            latest = ev.text;
            if (decided) update({ liveText: latest });
          } else if (ev.type === "audio") {
            if (!decided) {
              // Compare all text read so far, not just the first sentence.
              if (sameLabel(latest || ev.text, lastSpoken.current)) {
                skip = true;
                return;
              }
              decided = true;
              player.current.startStream(speed, (playing) => update({ playing }));
              update({ liveText: latest, liveSpokeIn: (performance.now() - sentAt) / 1000 });
            }
            player.current.addChunk(base64ToBlob(ev.data), true);
          } else if (ev.type === "done") {
            const text = (ev.text || "").trim();
            if (!text) {
              lastSpoken.current = ""; // label taken away: showing it again reads it again
              stopAudio(); // camera turned to something without text: stop the old label
            } else if (decided) {
              lastSpoken.current = text;
              player.current.endStream(text);
              update({
                liveText: text, text, recognizedText: text, scanId: ev.scan_id ?? null,
                engine: ev.engine || null, confidence: ev.confidence ?? null, feedbackGiven: false, server: "online",
              });
            }
          }
        });
      } catch (err) {
        if (err.kind === "network" && generation === liveGeneration.current) {
          update({ server: "offline" });
          say((s) => s.networkError);
        }
      } finally {
        if (generation === liveGeneration.current) { // a Stop already reset these
          update({ liveReading: false });
          liveBusy.current = false;
        }
      }
    },
    [say, stopAudio, update]
  );

  /**
   * Stop button on the camera screen: silence now, cancel the current read, and do not read
   * the same label again while it stays in front of the camera.
   */
  function stopSpeech() {
    liveGeneration.current += 1;
    liveAbort.current?.abort();
    liveBusy.current = false;
    const text = stateRef.current.liveText.trim();
    if (text) lastSpoken.current = text;
    update({ liveReading: false });
    stopAudio();
  }

  /* ---------------- speech ---------------- */
  function onTextChanged(text) {
    if (stateRef.current.playing) stopAudio();
    update({ text });
  }

  function startPlayback() {
    const speed = stateRef.current.slowSpeech ? 0.8 : 1;
    player.current
      .play(speed, () => update({ playing: false }))
      .then(() => update({ playing: true }))
      .catch(() => {
        update({ playing: false });
        say((s) => s.networkError);
      });
  }

  async function speak() {
    start();
    const s = stateRef.current;
    const text = s.text.trim();
    if (!text || s.loadingAudio) return;
    if (s.playing) {
      stopAudio();
      return;
    }
    if (player.current.hasAudioFor(text)) {
      startPlayback();
      return;
    }
    update({ loadingAudio: true });
    try {
      player.current.store(text, await generateSpeech(text));
      update({ loadingAudio: false });
      startPlayback();
    } catch (err) {
      update({ loadingAudio: false });
      sayError(err);
    }
  }

  /* ---------------- feedback (field pilot metrics) ---------------- */
  function doSendFeedback(helpful, corrected) {
    const scanId = stateRef.current.scanId;
    update({ feedbackGiven: true });
    sendFeedback(scanId, helpful, corrected).catch(() => {}).finally(() => say((s) => s.thanks));
  }

  function feedback(helpful) {
    const s = stateRef.current;
    if (!helpful && s.text.trim() !== s.recognizedText.trim()) {
      update({ askCorrection: true });
      return;
    }
    doSendFeedback(helpful, null);
  }

  function answerCorrection(send) {
    update({ askCorrection: false });
    doSendFeedback(false, send ? stateRef.current.text.trim() : null);
  }

  async function copy() {
    try {
      await navigator.clipboard.writeText(stateRef.current.text);
      say((s) => s.copied);
    } catch {
      // clipboard blocked: nothing to do
    }
  }

  function saveServerUrl(url) {
    setServerUrl(url);
    update({ serverUrl: getServerUrl() });
    say((s) => s.saved);
    checkServer();
  }

  /* ---------------- snackbar ---------------- */
  useEffect(() => {
    if (!state.message) return;
    const timer = setTimeout(() => update({ message: null }), 3500);
    return () => clearTimeout(timer);
  }, [state.message, update]);

  return (
    <div className="phone" lang={state.language}>
      {state.screen === "camera" && (
        <CameraScreen
          s={strings}
          state={state}
          liveBusy={() => liveBusy.current}
          onLiveFrame={onLiveFrame}
          onToggleLive={setLiveMode}
          onRetryServer={() => checkServer()}
          onSettings={() => navigate("settings")}
          onCaptured={onImageSelected}
          onGallery={onImageSelected}
          onImageError={() => say((s) => s.imageError)}
          onStart={start}
          onStopSpeech={stopSpeech}
        />
      )}
      {state.screen === "result" && (
        <ResultScreen
          s={strings}
          state={state}
          onBack={back}
          onTextChange={onTextChanged}
          onSpeak={speak}
          onSlowSpeech={(v) => setPref("slowSpeech", v)}
          onNewPhoto={() => navigate("camera")}
          onFeedback={feedback}
          onCorrectionAnswer={answerCorrection}
          onCopy={copy}
        />
      )}
      {state.screen === "settings" && (
        <SettingsScreen
          s={strings}
          state={state}
          onBack={back}
          onLanguage={(v) => setPref("language", v)}
          onLiveMode={setLiveMode}
          onAutoRead={(v) => setPref("autoRead", v)}
          onSlowSpeech={(v) => setPref("slowSpeech", v)}
          onShowEngine={(v) => setPref("showEngine", v)}
          onSaveServer={saveServerUrl}
          onTestServer={(url) => checkServer(url)}
        />
      )}
      {state.message && (
        <div className="snackbar" key={state.message.id} role="status">
          {state.message.text(strings)}
        </div>
      )}
    </div>
  );
}
