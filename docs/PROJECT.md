# GramText: Devanagari Sign & Prescription Reader for Digital Literacy in Rural Communities (SDG 3, 4, 10)

Approved project (projexa.ai, team 26040). Problem type: Community SDG.
**Scope decision:** this build follows the approved proposal **except the offline part**, which is deferred to Phase 2 by the team's decision. Our STR model is 0.85M parameters (3.4 MB ONNX), so it is already small enough for on-device TFLite later. Recognition
runs on our server, not on the phone, so TFLite and on-device inference are future scope.

## Problem description

In rural areas, low-literacy individuals cannot read medicine labels, prescriptions, or government
scheme notices printed in Devanagari. In partnership with an NGO working on health and literacy, this
project delivers a mobile tool that captures printed Devanagari (and English) text and reads it
aloud. It supports Good Health (SDG 3), Quality Education (SDG 4) and Reduced Inequalities (SDG 10).

## Objectives (from the proposal) and how this build meets them

| Objective | Implementation |
|---|---|
| Low-resource inference for rural connectivity *(offline-first excluded)* | Lightweight STR model (~0.85M parameters, 3.6 MB). The phone shrinks photos to ≤2048 px (~400 KB) before upload. Audio is cached so replays use no network |
| Engage with NGO stakeholders and field-test with target users | Collaboration letter template, community feedback form, in-app 👍/👎 feedback, anonymous device count, `/api/metrics` |
| Inclusive UX for low-literacy users | Hindi-first UI, large icon buttons, 24 sp text, auto read-aloud, slow-speech option, editable text, TalkBack labels |
| Align technical work with measurable SDG outcomes | Impact metrics: scans, success rate, helpful rate, corrections, latency, unique devices, plus model accuracy reports |

## Proposed solution (as delivered)

A deployed Android app with measurable impact: field pilot plus impact metrics, an NGO collaboration
letter, a community feedback report, and multilingual TTS output (Hindi + English).

## Technology stack

| Proposal | This build |
|---|---|
| Python | FastAPI backend, training and evaluation scripts |
| PyTorch | Model training (Colab GPU) and server inference (TorchScript, CPU) |
| Lightweight STR (MobileViT backbone) | `ml/model.py`: MobileViTv2-0.5 → BiLSTM → CTC, trained on synthetic + real Devanagari/English word images |
| TFLite | *Excluded with the offline part* (Phase 2). The server runs the same model as ONNX (onnxruntime) |
| Kotlin/Flutter | **Kotlin** + Jetpack Compose + CameraX (`android/`) |
| offline-first architecture | *Excluded*. Online client–server instead |
| gTTS | `backend/app/services/gtts_tts.py`. Hindi and English voices, mixed text split by script |
| NGO partnership | `docs/templates/` (letter, feedback form, impact report) |

Supporting components:
- **Text detector:** docTR DBNet (pretrained). It finds the words on a label before our STR model reads them.
- **Backup:** Google Gemini, used only when our model's confidence is low or the model is unavailable.

## Architecture

```text
 Kotlin app (Compose + CameraX)
   photo ──► resize ≤2048 px, fix rotation
      │  HTTPS / HTTP (multipart JPEG)
      ▼
 FastAPI backend
   1. validate image (real JPG/PNG/WEBP, size, EXIF)
   2. detect words           docTR DBNet
   3. recognise each word    GramText STR (MobileViT + CTC)  → text + confidence
   4. confidence < 0.85?     → Gemini backup
   5. /api/tts               gTTS (hi / en) → MP3
   6. SQLite metrics         scans · tts · feedback (no images, no scanned text)
      │
      ▼
 App shows editable text ──► "पढ़कर सुनाएँ" (Read aloud) ──► 👍/👎 feedback
```

## API

| Endpoint | Purpose |
|---|---|
| `POST /api/ocr` (multipart `file`) | Returns `text`, `language`, `engine` (`str` or `gemini`), `confidence`, `scan_id` |
| `POST /api/tts` `{"text"}` | Returns MP3 audio |
| `POST /api/feedback` `{"scan_id", "helpful", "corrected_text"?}` | Records field-pilot feedback |
| `GET /api/metrics` | Returns impact metrics and model accuracy |
| `GET /api/health` | Shows which engines are ready |

The optional `X-Client-Id` header carries an anonymous random ID, used only to count unique devices.

## Functional requirements

- **FR-01** Capture a label with the camera, or choose a photo from the gallery.
- **FR-02** Validate the image type and size, and fix its rotation.
- **FR-03** Detect and recognise printed Devanagari and English text.
- **FR-04** Preserve matras and conjuncts.
- **FR-05** Show the text and allow it to be edited.
- **FR-06** Read the text aloud in Hindi and English, including mixed labels.
- **FR-07** Play slowly on request, and replay without the network.
- **FR-08** Collect feedback, with optional corrections, for the field pilot.
- **FR-09** Report impact metrics.
- **FR-10** Show clear errors in the user's language. Never give medical advice.

## Model training and evaluation

1. **Data.** Synthetic word images (`ml/synth.py`) built from:
   - 14 Devanagari and Latin fonts, shaped correctly with HarfBuzz
   - 20k Hindi and 47k English frequent words
   - medicine and notice vocabulary, doses, prices and dates
   - blur, noise, perspective, JPEG and low-resolution augmentation

   Optionally mixed with real crops (IIIT-ILST Hindi, IndicSTR12, or photos from the field pilot).
2. **Model.** MobileViTv2-0.5 backbone (ImageNet-pretrained). The transformer stage uses a stride of (2,1), so a 32×256 crop keeps 64 time steps. A 2-layer BiLSTM feeds a CTC head over 213 characters plus the blank.
3. **Training.** `ml/train_colab.ipynb` on a free Colab T4: 40k steps, batch 128, AdamW + OneCycle, mixed precision. It resumes after disconnects.
4. **Evaluation.** `ml/eval.py` reports character and word accuracy, overall and for Hindi and English separately. The report goes in the impact report.
5. **Sanity check.** On a CPU overfit test (64 samples), character accuracy reached 87% within 1,000 steps. This shows the pipeline learns.

## Limitations

- **Online:** needs mobile data or Wi-Fi.
- **Accuracy:** our model is trained mostly on synthetic data. Real-photo accuracy improves as field-pilot corrections are added. Gemini covers low-confidence cases.
- **Privacy:** photos go to our server, and to Gemini only when the backup is used. Nothing is stored except anonymous counts and voluntary corrections. Use demo labels in presentations.
- **gTTS:** uses Google Translate's public voice service, with no SLA and possible rate limits.
- **Medical:** the app reads text only. It gives no diagnosis or advice.

## SDG connection

- **SDG 3 (Good Health):** people can hear dosage instructions and warnings on medicine labels.
- **SDG 4 (Quality Education):** builds digital literacy by turning printed text into speech.
- **SDG 10 (Reduced Inequalities):** gives low-literacy users access to government scheme notices.

## Demo script

1. Start the backend and open the app. The home screen shows "सर्वर जुड़ा है" (server connected).
2. Tap **फ़ोटो खींचें** (take photo) and photograph a demo medicine label.
3. The text appears and is read aloud automatically. Turn on "Show technical details" to show **GramText मॉडल · 9x%** (GramText model and confidence).
4. Tap **पढ़कर सुनाएँ** (read aloud) again. Replay is instant because the audio is cached.
5. Correct a word, tap 👎, then **सुधार भेजें** (send correction). Explain how this feeds the field pilot and future training.
6. Open `http://<server>:8000/api/metrics` and show the impact metrics.

## Viva questions

1. **What does GramText do?** It photographs printed Hindi/English labels and notices, recognises the text with our own STR model, and reads it aloud.
2. **Why MobileViT?** It combines CNN locality with transformer global context in under 1M parameters, so it is fast on a low-cost server and could later run on phones.
3. **Why CTC?** Word images have no character-position labels. CTC learns the alignment itself and decodes greedily in one pass.
4. **How do you handle matras and conjuncts?** Recognition works at Unicode code-point level. Training images are shaped with HarfBuzz, so conjuncts render exactly as in print.
5. **Where does training data come from?** Synthetic rendering of real Hindi and English word lists in 14 fonts with camera-like distortions, plus optional real crops and field-pilot corrections.
6. **What does Gemini do, then?** It is a backup when our model's confidence is below 0.85. The response shows which engine answered, and the metrics track the split.
7. **Why not offline?** It was excluded from this phase's scope. TFLite export and offline TTS are future work.
8. **How is impact measured?** Scans, success rate, the 👍 rate, corrections, latency and unique devices from `/api/metrics`, plus model accuracy from `eval.py` and the community feedback report.
9. **Privacy?** No images or scanned text are stored. Feedback corrections are sent only with the user's consent.
10. **Medical safety?** The app only reads the text aloud, and a disclaimer is on every screen.

## Future scope

- **Offline-first (the excluded part):** TFLite or ONNX on the phone, and an offline Hindi TTS voice.
- **More languages:** Marathi, Bengali, Tamil, Telugu, Gujarati and Punjabi.
- **Detection:** fine-tune a detector on Devanagari, and add auto-crop and perspective correction.
- **Model improvement:** continuous improvement from field-pilot corrections, with a larger real dataset.
