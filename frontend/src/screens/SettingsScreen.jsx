// Web twin of android/.../ui/SettingsScreen.kt.
import { useState } from "react";
import { ArrowLeft } from "lucide-react";

function Toggle({ title, hint, checked, onChange }) {
  return (
    <label className="toggle-row">
      <span className="grow">
        <span className="body-lg">{title}</span>
        {hint && <span className="muted block">{hint}</span>}
      </span>
      <input type="checkbox" className="switch" checked={checked} onChange={(e) => onChange(e.target.checked)} />
    </label>
  );
}

export default function SettingsScreen({
  s, state, onBack, onLanguage, onLiveMode, onAutoRead, onSlowSpeech, onShowEngine, onSaveServer, onTestServer,
}) {
  const [url, setUrl] = useState(state.serverUrl);
  const serverLabel = { online: s.serverOnline, offline: s.serverOffline, checking: s.serverChecking }[state.server];

  return (
    <div className="screen settings">
      <div className="row">
        <button type="button" className="icon-btn" onClick={onBack} aria-label={s.back}>
          <ArrowLeft size={26} />
        </button>
        <h1 className="headline">{s.settings}</h1>
      </div>

      <h3 className="title-md">{s.language}</h3>
      <div className="row gap">
        <button type="button" className={`chip ${state.language === "hi" ? "sel" : ""}`} onClick={() => onLanguage("hi")}>हिंदी</button>
        <button type="button" className={`chip ${state.language === "en" ? "sel" : ""}`} onClick={() => onLanguage("en")}>English</button>
      </div>
      <hr />

      <Toggle title={s.liveSetting} hint={s.liveSettingHint} checked={state.liveMode} onChange={onLiveMode} />
      <Toggle title={s.autoRead} hint={s.autoReadHint} checked={state.autoRead} onChange={onAutoRead} />
      <Toggle title={`${s.speed}: ${s.slow}`} checked={state.slowSpeech} onChange={onSlowSpeech} />
      <Toggle title={s.showEngine} checked={state.showEngine} onChange={onShowEngine} />
      <hr />

      <h3 className="title-md">{s.serverAddress}</h3>
      <input
        className="field"
        type="url"
        value={url}
        placeholder={window.location.origin}
        onChange={(e) => setUrl(e.target.value)}
      />
      <p className="muted">{serverLabel}</p>
      <div className="row gap">
        <button type="button" className="btn outline grow" onClick={() => onTestServer(url)}>{s.testConnection}</button>
        <button type="button" className="btn primary grow" onClick={() => onSaveServer(url)}>{s.save}</button>
      </div>

      <p className="muted">{s.disclaimer}</p>
    </div>
  );
}
