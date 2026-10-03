"""On-the-fly synthetic word images for training (Hindi, English, mixed labels)."""
import io
import random
from pathlib import Path

import numpy as np
import torch
from PIL import Image, ImageFilter
from torch.utils.data import Dataset

from charset import encode, is_supported, normalize
from render import Font

IMG_H, IMG_W = 32, 256
MAX_LABEL = 25
DATA = Path(__file__).resolve().parent / "data"

# Words from the target domain: medicine labels, prescriptions, government notices.
DOMAIN_HI = """दवा गोली कैप्सूल सिरप खुराक सुबह दोपहर शाम रात भोजन पहले बाद खाली पेट दिन बार
एक दो तीन चार पांच आधा चम्मच पानी साथ लें न डॉक्टर सलाह अनुसार बच्चों पहुंच दूर रखें
ठंडी सूखी जगह धूप समाप्ति तिथि निर्माण मूल्य सहित केवल बाहरी उपयोग हेतु चेतावनी
सरकार योजना आवेदन पत्र अंतिम कार्यालय प्रवेश अनुमति कृपया सूचना आधार कार्ड राशन
पेंशन किसान स्वास्थ्य केंद्र अस्पताल टीकाकरण मुफ्त जांच शिविर ग्राम पंचायत विद्यालय
समय बजे से तक सोमवार मंगलवार बुधवार गुरुवार शुक्रवार शनिवार रविवार स्वच्छ भारत
स्वस्थ नमस्ते स्वागत है धन्यवाद महिला पुरुष शौचालय निकास खतरा बिजली सावधान""".split()
DOMAIN_EN = """tablet tablets capsule syrup dose daily morning afternoon evening night before
after food meals water take with doctor advice keep out of reach children store cool dry
place sunlight expiry date manufactured price inclusive taxes only external use warning
government scheme application form last office entry permission please notice aadhaar
ration pension farmer health centre hospital vaccination free camp village school time
paracetamol amoxicillin ibuprofen cetirizine metformin vitamin iron folic acid ORS
batch mfg exp MRP Rs mg ml IP USP Rx""".split()


def _load(name: str, fallback: list[str]) -> list[str]:
    path = DATA / name
    if path.exists():
        words = [w for w in path.read_text(encoding="utf-8").split() if w]
        if words:
            return words
    return fallback


def _number_token() -> str:
    r = random.random()
    if r < 0.3:
        return f"{random.choice([5, 10, 25, 50, 100, 250, 400, 500, 650, 1000])}{random.choice(['mg', 'ml', 'g', ' mg', ' ml'])}"
    if r < 0.5:
        return f"₹{random.randint(5, 999)}"
    if r < 0.7:
        return f"{random.randint(1, 28):02d}/{random.randint(1, 12):02d}/{random.randint(2024, 2030)}"
    if r < 0.85:
        return f"{random.randint(1, 12)}:{random.choice(['00', '15', '30', '45'])}"
    return str(random.randint(0, 9999))


class SynthWords(Dataset):
    """Each __getitem__ renders a new random sample, so the dataset is effectively infinite."""

    def __init__(self, length: int = 1_000_000, seed: int | None = None, font_dir: Path | None = None):
        self.length = length
        self.seed = seed
        self.words_hi = _load("words_hi.txt", DOMAIN_HI) + DOMAIN_HI * 20
        self.words_en = _load("words_en.txt", DOMAIN_EN) + DOMAIN_EN * 20
        font_dir = font_dir or DATA / "fonts"
        paths = sorted(font_dir.glob("*.tt[fc]"))
        if not paths:
            raise FileNotFoundError(f"No fonts in {font_dir}. Run: python prepare_data.py")
        self.fonts = [Font(str(p)) for p in paths]

    def __len__(self):
        return self.length

    def sample_text(self, rng: random.Random) -> str:
        r = rng.random()
        if r < 0.45:
            words = [rng.choice(self.words_hi) for _ in range(rng.choice([1, 1, 1, 2, 3]))]
        elif r < 0.80:
            words = [rng.choice(self.words_en) for _ in range(rng.choice([1, 1, 1, 2]))]
            case = rng.random()
            words = [w.upper() for w in words] if case < 0.25 else [w.capitalize() for w in words] if case < 0.5 else words
        elif r < 0.92:
            words = [_number_token()]
            if rng.random() < 0.5:
                words.insert(0, rng.choice(self.words_en + self.words_hi))
        else:
            words = [rng.choice(self.words_hi), rng.choice(self.words_en)]
            rng.shuffle(words)
        text = normalize(" ".join(words))
        if rng.random() < 0.1:
            text += rng.choice([".", ",", ":", "।"])
        return text[:MAX_LABEL]

    def render(self, text: str, rng: random.Random) -> Image.Image:
        fonts = [f for f in self.fonts if f.covers(text)]
        font = rng.choice(fonts or self.fonts)
        mask = font.render(text, rng.randint(28, 64)).astype(np.float32) / 255.0

        pad_y, pad_x = rng.randint(2, 10), rng.randint(2, 16)
        mask = np.pad(mask, ((pad_y, pad_y), (pad_x, pad_x)))
        bg, fg = rng.uniform(150, 255), rng.uniform(0, 110)
        if rng.random() < 0.2:  # light text on dark background (signboards)
            bg, fg = fg, bg
        h, w = mask.shape
        gradient = np.linspace(0, rng.uniform(-40, 40), w)[None, :]
        background = bg + gradient + np.random.normal(0, rng.uniform(0, 12), (h, w))
        img = background * (1 - mask) + fg * mask
        image = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "L")

        if rng.random() < 0.5:
            image = image.rotate(rng.uniform(-4, 4), expand=True, fillcolor=int(bg))
        if rng.random() < 0.3:
            sx = rng.uniform(-0.25, 0.25)
            image = image.transform(image.size, Image.AFFINE, (1, sx, -sx * image.height / 2, 0, 1, 0), fillcolor=int(bg))
        if rng.random() < 0.4:
            image = image.filter(ImageFilter.GaussianBlur(rng.uniform(0.3, 1.4)))
        if rng.random() < 0.4:  # phone camera / JPEG artefacts
            small = image.resize((max(8, int(image.width * rng.uniform(0.4, 0.9))), max(8, int(image.height * rng.uniform(0.4, 0.9)))))
            image = small.resize(image.size)
        if rng.random() < 0.4:
            buf = io.BytesIO()
            image.save(buf, "JPEG", quality=rng.randint(20, 80))
            image = Image.open(io.BytesIO(buf.getvalue())).convert("L")
        return image

    def __getitem__(self, idx):
        rng = random.Random(None if self.seed is None else self.seed + idx)
        while True:
            text = self.sample_text(rng)
            if is_supported(text):
                break
        image = self.render(text, rng)
        return to_tensor(image), torch.tensor(encode(text), dtype=torch.long), text


def to_tensor(image: Image.Image) -> torch.Tensor:
    """Resize to 32 px high keeping aspect, pad/squash to 256 wide, scale to [-1, 1]."""
    image = image.convert("L")
    w = max(1, round(image.width * IMG_H / image.height))
    image = image.resize((min(w, IMG_W), IMG_H), Image.BILINEAR)
    arr = np.asarray(image, dtype=np.float32) / 255.0
    canvas = np.full((IMG_H, IMG_W), float(np.median(arr[:, -1])), dtype=np.float32)
    canvas[:, : arr.shape[1]] = arr
    return torch.from_numpy(canvas).unsqueeze(0) * 2 - 1


def collate(batch):
    images, targets, texts = zip(*batch)
    lengths = torch.tensor([len(t) for t in targets], dtype=torch.long)
    return torch.stack(images), torch.cat(targets), lengths, list(texts)
