"""Real cropped word/line images, for fine-tuning and honest evaluation.

Folder layout:
    my_crops/
        labels.tsv      # one line per image:  relative/path.jpg<TAB>label text
        img_0001.jpg ...

Sources: IIIT-ILST Hindi (cvit.iiit.ac.in), IndicSTR12, or crops the team photographs
during the field pilot and labels by hand.
"""
from pathlib import Path

import torch
from PIL import Image
from torch.utils.data import Dataset

from charset import encode, is_supported, normalize
from synth import MAX_LABEL, to_tensor


class RealCrops(Dataset):
    def __init__(self, folder: str):
        self.root = Path(folder)
        self.items = []
        for line in (self.root / "labels.tsv").read_text(encoding="utf-8").splitlines():
            if "\t" not in line:
                continue
            path, label = line.split("\t", 1)
            label = normalize(label)
            if is_supported(label) and len(label) <= MAX_LABEL and (self.root / path).exists():
                self.items.append((path, label))
        if not self.items:
            raise ValueError(f"No usable samples in {self.root / 'labels.tsv'}")

    def __len__(self):
        return len(self.items)

    def __getitem__(self, idx):
        path, label = self.items[idx]
        image = Image.open(self.root / path).convert("L")
        return to_tensor(image), torch.tensor(encode(label), dtype=torch.long), label
