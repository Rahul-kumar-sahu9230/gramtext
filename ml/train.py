"""Train the GramText STR model with CTC loss.

Colab (T4 GPU), full run:
    python train.py --pretrained --steps 60000 --batch 128 --workers 2 --out /content/drive/MyDrive/gramtext_str
Local sanity check (CPU, proves the pipeline learns):
    python train.py --overfit 64 --steps 300 --batch 32 --workers 0 --val-every 100
"""
import argparse
import json
import time
from pathlib import Path

import torch
from torch import nn
from torch.utils.data import ConcatDataset, DataLoader, Subset

from charset import decode
from metrics import score
from model import GramTextSTR
from synth import SynthWords, collate


def evaluate(model, loader, device, limit=None):
    model.eval()
    preds, labels = [], []
    with torch.no_grad():
        for images, _, _, texts in loader:
            probs = model(images.to(device)).float().softmax(-1).cpu()
            best_p, best_i = probs.max(-1)
            preds += [decode(i, p)[0] for i, p in zip(best_i, best_p)]
            labels += texts
            if limit and len(labels) >= limit:
                break
    model.train()
    return score(preds, labels), list(zip(preds[:8], labels[:8]))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--steps", type=int, default=60_000)
    ap.add_argument("--batch", type=int, default=128)
    ap.add_argument("--lr", type=float, default=1e-3)
    ap.add_argument("--workers", type=int, default=2)
    ap.add_argument("--pretrained", action="store_true", help="ImageNet MobileViT weights")
    ap.add_argument("--real-train", help="folder with labels.tsv mixed into training")
    ap.add_argument("--real-val", help="folder with labels.tsv for validation")
    ap.add_argument("--val-every", type=int, default=2000)
    ap.add_argument("--val-size", type=int, default=1000)
    ap.add_argument("--overfit", type=int, default=0, help="train on N fixed samples (debug)")
    ap.add_argument("--out", default="runs/str")
    ap.add_argument("--resume", action="store_true")
    args = ap.parse_args()

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    print("device:", device)

    if args.overfit:
        fixed = SynthWords(length=args.overfit, seed=1)
        cache = [fixed[i] for i in range(args.overfit)]
        train_ds = [cache[i % args.overfit] for i in range(args.steps * args.batch // 4 or 1)]
        val_ds = cache
    else:
        train_ds = SynthWords(length=args.steps * args.batch)
        if args.real_train:
            from realdata import RealCrops
            real = RealCrops(args.real_train)
            repeats = max(1, len(train_ds) // (4 * len(real)))  # ~20% real samples
            train_ds = ConcatDataset([train_ds] + [real] * repeats)
        if args.real_val:
            from realdata import RealCrops
            val_ds = RealCrops(args.real_val)
        else:
            val_ds = SynthWords(length=args.val_size, seed=12345)
        if not args.real_val:
            val_ds = Subset(val_ds, range(min(args.val_size, len(val_ds))))

    loader = DataLoader(
        train_ds, batch_size=args.batch, shuffle=True, num_workers=args.workers,
        collate_fn=collate, drop_last=True, persistent_workers=args.workers > 0,
        pin_memory=device.type == "cuda",
    )
    val_loader = DataLoader(val_ds, batch_size=args.batch, num_workers=args.workers, collate_fn=collate)

    model = GramTextSTR(pretrained=args.pretrained).to(device)
    print(f"params: {sum(p.numel() for p in model.parameters()) / 1e6:.2f}M")
    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=0.01)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=args.lr, total_steps=args.steps, pct_start=0.05)
    scaler = torch.amp.GradScaler("cuda", enabled=device.type == "cuda")
    ctc = nn.CTCLoss(blank=0, zero_infinity=True)
    step, best = 0, -1.0

    last = out / "last.pt"
    if args.resume and last.exists():
        ckpt = torch.load(last, map_location=device)
        model.load_state_dict(ckpt["model"])
        opt.load_state_dict(ckpt["opt"])
        sched.load_state_dict(ckpt["sched"])
        step, best = ckpt["step"], ckpt["best"]
        print("resumed at step", step)

    model.train()
    started = time.time()
    while step < args.steps:
        for images, targets, lengths, _ in loader:
            images = images.to(device, non_blocking=True)
            with torch.autocast(device.type, enabled=device.type == "cuda"):
                logits = model(images)
            log_probs = logits.float().log_softmax(-1).transpose(0, 1)  # (T, B, C)
            input_lengths = torch.full((images.size(0),), log_probs.size(0), dtype=torch.long)
            loss = ctc(log_probs, targets, input_lengths, lengths)

            opt.zero_grad(set_to_none=True)
            scaler.scale(loss).backward()
            scaler.unscale_(opt)
            nn.utils.clip_grad_norm_(model.parameters(), 5.0)
            scaler.step(opt)
            scaler.update()
            sched.step()
            step += 1

            if step % 50 == 0:
                print(f"step {step}/{args.steps}  loss {loss.item():.4f}  lr {sched.get_last_lr()[0]:.2e}  {time.time() - started:.0f}s", flush=True)

            if step % args.val_every == 0 or step == args.steps:
                metrics, examples = evaluate(model, val_loader, device)
                acc = metrics["all"]["char_accuracy"]
                print("val:", json.dumps(metrics, ensure_ascii=False))
                for p, t in examples:
                    print(f"   {t!r:28} -> {p!r}")
                state = {"model": model.state_dict(), "opt": opt.state_dict(), "sched": sched.state_dict(),
                         "step": step, "best": max(best, acc), "metrics": metrics}
                torch.save(state, last)
                if acc > best:
                    best = acc
                    torch.save({"model": model.state_dict(), "step": step, "metrics": metrics}, out / "best.pt")
                    (out / "best_metrics.json").write_text(json.dumps(metrics, indent=2, ensure_ascii=False), encoding="utf-8")
                    print(f"   new best char accuracy {best:.4f} -> {out / 'best.pt'}")
            if step >= args.steps:
                break


if __name__ == "__main__":
    main()
