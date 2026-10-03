// Web twin of android/.../ui/ResultScreen.kt.
import { ArrowLeft, Camera, Copy, LoaderCircle, Square, ThumbsDown, ThumbsUp, Volume2 } from "lucide-react";

export default function ResultScreen({
  s, state, onBack, onTextChange, onSpeak, onSlowSpeech, onNewPhoto, onFeedback, onCorrectionAnswer, onCopy,
}) {
  const engine = state.engine === "str" ? s.engineModel : s.engineGemini;
  const conf = state.confidence != null ? ` · ${Math.round(state.confidence * 100)}%` : "";

  return (
    <div className="screen result">
      <div className="result-image">
        {state.preview && <img src={state.preview} alt="" />}
        <button type="button" className="round-btn over" onClick={onBack} aria-label={s.back}>
          <ArrowLeft size={24} />
        </button>
      </div>

      <div className="result-body">
        {state.scanning && !state.text ? (
          <>
            <h2 className="title-lg">{s.reading}</h2>
            <div className="progress"><span /></div>
          </>
        ) : (
          <>
            <div className="row">
              <h3 className="title-md grow">{s.detectedText}</h3>
              {state.text.trim() && (
                <button type="button" className="text-btn" onClick={onCopy}>
                  <Copy size={20} /> {s.copy}
                </button>
              )}
            </div>
            <textarea
              lang="hi"
              value={state.text}
              placeholder={s.editHint}
              onChange={(e) => onTextChange(e.target.value)}
              aria-label={s.detectedText}
            />
            <p className="hint">{s.editHint}</p>

            {state.showEngine && state.engine && (
              <p className="muted">{s.readBy}: {engine}{conf}</p>
            )}

            <button
              type="button"
              className={`btn big ${state.playing ? "danger" : "primary"}`}
              onClick={onSpeak}
              disabled={!state.text.trim() || state.loadingAudio}
            >
              {state.loadingAudio ? (
                <><LoaderCircle size={28} className="spin" /> {s.preparingAudio}</>
              ) : state.playing ? (
                <><Square size={30} /> {s.stop}</>
              ) : (
                <><Volume2 size={32} /> {s.readAloud}</>
              )}
            </button>

            <div className="row gap">
              <span className="body-lg">{s.speed}</span>
              <button type="button" className={`chip ${state.slowSpeech ? "sel" : ""}`} onClick={() => onSlowSpeech(true)}>{s.slow}</button>
              <button type="button" className={`chip ${!state.slowSpeech ? "sel" : ""}`} onClick={() => onSlowSpeech(false)}>{s.normal}</button>
            </div>

            <button type="button" className="btn outline block" onClick={onNewPhoto}>
              <Camera size={22} /> {s.scanAgain}
            </button>

            {state.text.trim() && !state.feedbackGiven && (
              <div className="feedback">
                <h3 className="title-md">{s.wasCorrect}</h3>
                <div className="row gap">
                  <button type="button" className="btn success grow" onClick={() => onFeedback(true)}>
                    <ThumbsUp size={22} /> {s.yes}
                  </button>
                  <button type="button" className="btn danger grow" onClick={() => onFeedback(false)}>
                    <ThumbsDown size={22} /> {s.no}
                  </button>
                </div>
              </div>
            )}

            <p className="muted">{s.disclaimer}</p>
          </>
        )}
      </div>

      {state.askCorrection && (
        <div className="dialog-backdrop" onClick={() => onCorrectionAnswer(false)}>
          <div className="dialog" role="dialog" onClick={(e) => e.stopPropagation()}>
            <h3 className="title-lg">{s.sendCorrection}</h3>
            <p>{s.sendCorrectionBody}</p>
            <div className="row end gap">
              <button type="button" className="text-btn" onClick={() => onCorrectionAnswer(false)}>{s.dontSend}</button>
              <button type="button" className="btn primary" onClick={() => onCorrectionAnswer(true)}>{s.send}</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
