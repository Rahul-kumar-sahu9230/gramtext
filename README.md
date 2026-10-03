# GramText

**Devanagari Sign & Prescription Reader for Digital Literacy in Rural Communities (SDG 3, 4, 10).**
An Android app photographs a Hindi or English label. Our own MobileViT STR model reads it, and gTTS
speaks it aloud. Online version: recognition runs on the server.

```
android/    Kotlin + Jetpack Compose + CameraX app
backend/    FastAPI: detector + our STR model (+ Gemini backup), gTTS, metrics
ml/         PyTorch training: synthetic data, MobileViT-CTC model, Colab notebook, eval, export
frontend/   React web app of the same API (deployed on Vercel)
docs/       project report material, NGO letter, feedback form, impact report templates
```

## 1. Backend
```powershell
cd backend
python -m venv venv; venv\Scripts\activate
pip install -r requirements.txt   # no PyTorch needed: our model runs as ONNX
copy .env.example .env        # add GEMINI_API_KEY (backup OCR)
uvicorn app.main:app --host 0.0.0.0 --port 8000
```
- **No model file yet:** until `backend/models/gramtext_str.onnx` exists, OCR uses the Gemini backup.
- **Impact metrics:** `http://localhost:8000/api/metrics`.

## 2. Train the STR model (free Colab GPU)
1. Upload `ml/ml.zip`, or the `ml` folder to Google Drive at `MyDrive/gramtext/ml`.
2. Open `ml/train_colab.ipynb` in Colab (Runtime → T4 GPU) and run all cells.
3. Copy the downloaded `gramtext_str.onnx` to `backend/models/` (commit it too, so Render gets it) and restart the backend.
   `/api/health` then shows `str_model_ready: true`, and scans report `engine: "str"`.

**Local CPU sanity check:**
```
cd ml
python prepare_data.py
python train.py --overfit 64 --steps 1000 --batch 32 --workers 0
```

## 3. Android app
1. Open `android/` in Android Studio and let Gradle sync.
2. Run on the emulator. It reaches the laptop backend at `http://10.0.2.2:8000`.
3. On a real phone on the same Wi-Fi, open **Settings** in the app and set the server to `http://<laptop-IP>:8000`.

## 4. Deploy: backend on Render, the APK is the app
**a) Push to GitHub** (from the `gramtext` folder). `.env` files, `venv`, `node_modules`, builds and the APK are git-ignored.
```powershell
git init; git add .; git commit -m "GramText"
git branch -M main; git remote add origin https://github.com/<you>/gramtext.git; git push -u origin main
```

**b) Backend on Render**
1. **New + → Blueprint** → choose the repo. It reads `render.yaml` (root dir `backend`, Python 3.12.8).
2. Enter **GEMINI_API_KEY** when asked (or under Environment). Deploy.
3. Open `https://<service>.onrender.com/api/health`. It should show `"status":"healthy"`.
4. The APK uses `https://gramtext.onrender.com` by default. If Render gave your service a different URL, rebuild the APK with it (section 5).
- **Free plan:** 512 MB RAM, which is enough for Gemini + gTTS. It sleeps after 15 min idle, so the first request then takes about 50 s (the app shows "checking" and keeps retrying meanwhile). Optional: a free pinger such as cron-job.org calling `/api/health` every 10 min keeps it awake; the free 750 h/month covers one service.
- **Metrics:** Render's disk is temporary, so `/api/metrics` resets on every deploy or restart.
- **Our STR model:** it runs as ONNX (onnxruntime + onnxtr, no PyTorch), using about 280 MB, so it fits the free plan.
- **Why not the backend on Vercel:** its serverless functions can't keep the warm connection pool that gives ~2 s speech, and the ML packages exceed their size limit.

**c) Optional, not needed: web app on Vercel**
1. **Add New → Project** → import the repo. Set **Root Directory** to `frontend` (Vite is detected; `frontend/vercel.json` has the build settings).
2. Name the project **gramtext** (or anything starting with `gramtext`). `render.yaml` allows CORS from `https://gramtext*.vercel.app`.
3. Add the environment variable **VITE_API_BASE_URL** = `https://<service>.onrender.com` (no trailing slash). Deploy.
- Using a different project name or a custom domain: add that URL to **FRONTEND_ORIGINS** in Render (comma-separated) and redeploy the backend.
- Changing `VITE_API_BASE_URL` later needs a Vercel **Redeploy**, because Vite bakes it in at build time.

## 5. Build the Android APK
```powershell
cd android
$env:JAVA_HOME = "$HOME\.jdks\jdk-17.0.20.1+1"     # or Android Studio's bundled JDK
.\gradlew.bat assembleDebug                                          # default: https://gramtext.onrender.com
.\gradlew.bat assembleDebug -PapiBaseUrl=https://<service>.onrender.com  # if Render gave another URL
.\gradlew.bat assembleDebug -PapiBaseUrl=http://<laptop-IP>:8000        # local backend for testing
```
The APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`. Copy it to the phone and install it (allow "install unknown apps"). To share it, attach it to a **GitHub Release** (APKs are git-ignored) or send it by Drive/WhatsApp.
You can also change the server address inside the app (⚙ Settings → Server address) without rebuilding.

## Notes
- **Scope:** online only. The offline/TFLite part of the proposal is excluded from this phase.
- **Privacy:** no photos or scanned text are stored. Only anonymous counts and voluntary corrections are kept.
- **Medical:** the app reads text aloud. It gives no medical advice.
