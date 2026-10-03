"""Export a trained checkpoint for the FastAPI backend.

    python export.py --ckpt runs/str/best.pt                 # writes BOTH formats:
        ../backend/models/gramtext_str.onnx   -> onnxruntime (~15 MB), fits Render's free 512 MB plan
        ../backend/models/gramtext_str.pt     -> TorchScript, if the server has torch installed
The backend prefers the .onnx file. Neither needs timm or the training code.
"""
import argparse
import json
from pathlib import Path

import torch

from charset import CHARS
from model import GramTextSTR
from synth import IMG_H, IMG_W

MODELS = Path(__file__).resolve().parent.parent / "backend" / "models"


def export_torchscript(model, out: Path, meta: dict) -> None:
    example = torch.randn(2, 1, IMG_H, IMG_W)
    with torch.no_grad():
        scripted = torch.jit.trace(model, example)
        for batch in (1, 5):  # traced graph must work for any batch size
            x = torch.randn(batch, 1, IMG_H, IMG_W)
            assert torch.allclose(model(x), scripted(x), atol=1e-4), f"trace mismatch at batch {batch}"
    torch.jit.save(scripted, str(out), _extra_files={
        "charset.json": json.dumps(CHARS, ensure_ascii=False),
        "meta.json": json.dumps(meta, ensure_ascii=False),
    })
    print(f"saved {out} ({out.stat().st_size / 1e6:.1f} MB)")


def export_onnx(model, out: Path, meta: dict) -> None:
    import numpy as np
    import onnx
    import onnxruntime as ort

    example = torch.randn(2, 1, IMG_H, IMG_W)
    torch.onnx.export(
        model, (example,), str(out), input_names=["image"], output_names=["logits"],
        dynamic_axes={"image": {0: "batch"}, "logits": {0: "batch"}}, opset_version=17, dynamo=False,
    )
    # Store the charset and metadata inside the .onnx file, so one file is all the server needs.
    proto = onnx.load(str(out))
    for key, value in (("charset", json.dumps(CHARS, ensure_ascii=False)), ("meta", json.dumps(meta, ensure_ascii=False))):
        entry = proto.metadata_props.add()
        entry.key, entry.value = key, value
    onnx.save(proto, str(out))

    session = ort.InferenceSession(str(out), providers=["CPUExecutionProvider"])
    with torch.no_grad():
        for batch in (1, 5):
            x = torch.randn(batch, 1, IMG_H, IMG_W)
            got = session.run(None, {"image": x.numpy()})[0]
            assert np.allclose(model(x).numpy(), got, atol=1e-3), f"onnx mismatch at batch {batch}"
    print(f"saved {out} ({out.stat().st_size / 1e6:.1f} MB)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ckpt", required=True)
    ap.add_argument("--out-dir", default=str(MODELS))
    ap.add_argument("--format", choices=["both", "onnx", "torchscript"], default="both")
    args = ap.parse_args()

    ckpt = torch.load(args.ckpt, map_location="cpu")
    model = GramTextSTR()
    model.load_state_dict(ckpt["model"])
    model.eval()

    meta = {"img_h": IMG_H, "img_w": IMG_W, "step": ckpt.get("step"), "metrics": ckpt.get("metrics")}
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    if args.format in ("both", "onnx"):
        export_onnx(model, out_dir / "gramtext_str.onnx", meta)
    if args.format in ("both", "torchscript"):
        export_torchscript(model, out_dir / "gramtext_str.pt", meta)


if __name__ == "__main__":
    main()
