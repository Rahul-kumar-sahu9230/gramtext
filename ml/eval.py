"""Evaluate a checkpoint or exported model; writes a JSON report for the impact metrics.

    python eval.py --model ../backend/models/gramtext_str.pt --real path/to/crops
    python eval.py --ckpt runs/str/best.pt                # synthetic held-out set
"""
import argparse
import json
from pathlib import Path

import torch
from torch.utils.data import DataLoader

from charset import decode
from metrics import score
from synth import SynthWords, collate


def main():
    ap = argparse.ArgumentParser()
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument("--ckpt", help="training checkpoint (best.pt)")
    src.add_argument("--model", help="exported TorchScript file")
    ap.add_argument("--real", help="folder with labels.tsv (real crops)")
    ap.add_argument("--synthetic", type=int, default=2000)
    ap.add_argument("--report", default="eval_report.json")
    args = ap.parse_args()

    if args.model:
        model = torch.jit.load(args.model, map_location="cpu")
    else:
        from model import GramTextSTR
        model = GramTextSTR()
        model.load_state_dict(torch.load(args.ckpt, map_location="cpu")["model"])
    model.eval()

    if args.real:
        from realdata import RealCrops
        dataset, name = RealCrops(args.real), f"real:{args.real}"
    else:
        dataset, name = SynthWords(length=args.synthetic, seed=999), "synthetic"

    preds, labels = [], []
    with torch.no_grad():
        for images, _, _, texts in DataLoader(dataset, batch_size=64, collate_fn=collate):
            probs = model(images).softmax(-1)
            best_p, best_i = probs.max(-1)
            preds += [decode(i, p)[0] for i, p in zip(best_i, best_p)]
            labels += texts

    report = {"dataset": name, "metrics": score(preds, labels),
              "errors": [{"label": t, "pred": p} for p, t in zip(preds, labels) if p != t][:50]}
    Path(args.report).write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps(report["metrics"], indent=2, ensure_ascii=False))
    print("report ->", args.report)


if __name__ == "__main__":
    main()
