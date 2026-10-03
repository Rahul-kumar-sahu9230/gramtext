"""Our trained MobileViT-CTC STR model (exported by ml/export.py).

Prefers models/gramtext_str.onnx (onnxruntime, ~15 MB - fits Render's free plan) and
falls back to models/gramtext_str.pt (TorchScript, needs torch).
"""
import json
import threading
from pathlib import Path

import numpy as np
from PIL import Image

from app.config import STR_MODEL_PATH

_runner = None  # callable: (N,1,32,256) float32 array -> (N,T,C) logits array
_charset: list[str] = []
_meta: dict = {}
_lock = threading.Lock()
IMG_H, IMG_W = 32, 256  # must match ml/synth.py


def _candidates() -> list[Path]:
    path = Path(STR_MODEL_PATH)
    return [path.with_suffix(".onnx"), path.with_suffix(".pt")] if path.suffix in (".pt", ".onnx") else [path]


def _model_file() -> Path | None:
    for path in _candidates():
        if path.is_file() and (path.suffix != ".pt" or _torch_installed()):
            return path
    return None


def _torch_installed() -> bool:
    try:
        import torch  # noqa: F401
        return True
    except ImportError:
        return False


def is_available() -> bool:
    return _model_file() is not None


def _load():
    global _runner, _charset, _meta
    with _lock:
        if _runner is None:
            path = _model_file()
            if path.suffix == ".onnx":
                import onnxruntime as ort

                options = ort.SessionOptions()
                options.intra_op_num_threads = 2
                session = ort.InferenceSession(str(path), options, providers=["CPUExecutionProvider"])
                props = session.get_modelmeta().custom_metadata_map
                _charset = json.loads(props["charset"])
                _meta = json.loads(props.get("meta") or "{}")
                _runner = lambda batch: session.run(None, {"image": batch})[0]
            else:
                import torch

                extra = {"charset.json": "", "meta.json": ""}
                model = torch.jit.load(str(path), map_location="cpu", _extra_files=extra)
                model.eval()
                _charset = json.loads(extra["charset.json"])
                _meta = json.loads(extra["meta.json"] or "{}")

                def run(batch):
                    with torch.no_grad():
                        return model(torch.from_numpy(batch)).numpy()

                _runner = run
    return _runner


def model_info() -> dict:
    if not is_available():
        return {"loaded": False}
    _load()
    return {"loaded": True, "file": _model_file().name, "step": _meta.get("step"), "metrics": _meta.get("metrics")}


def _to_array(image: Image.Image) -> np.ndarray:
    """Same preprocessing as ml/synth.py:to_tensor."""
    image = image.convert("L")
    w = max(1, round(image.width * IMG_H / image.height))
    image = image.resize((min(w, IMG_W), IMG_H), Image.BILINEAR)
    arr = np.asarray(image, dtype=np.float32) / 255.0
    canvas = np.full((IMG_H, IMG_W), float(np.median(arr[:, -1])), dtype=np.float32)
    canvas[:, : arr.shape[1]] = arr
    return canvas * 2 - 1


def _softmax(x: np.ndarray) -> np.ndarray:
    x = x - x.max(axis=-1, keepdims=True)
    e = np.exp(x)
    return e / e.sum(axis=-1, keepdims=True)


def _decode(indices, probs) -> tuple[str, float]:
    out, confs, prev = [], [], 0
    for idx, p in zip(indices, probs):
        idx = int(idx)
        if idx != 0 and idx != prev:
            out.append(_charset[idx - 1])
            confs.append(float(p))
        prev = idx
    return "".join(out), (sum(confs) / len(confs) if confs else 0.0)


def recognize(crops: list[Image.Image], batch_size: int = 32) -> list[tuple[str, float]]:
    run = _load()
    results: list[tuple[str, float]] = []
    for i in range(0, len(crops), batch_size):
        batch = np.stack([_to_array(c) for c in crops[i : i + batch_size]])[:, None].astype(np.float32)
        probs = _softmax(run(batch))
        best_i, best_p = probs.argmax(-1), probs.max(-1)
        results += [_decode(ix, px) for ix, px in zip(best_i, best_p)]
    return results
