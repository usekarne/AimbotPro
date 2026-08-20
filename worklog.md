# Worklog

---
Task ID: 1
Agent: Super Z (main)
Task: Drop real YOLOv8n TFLite model + configure release keystore + rebuild setup

Work Log:
- Extracted AimbotPro-v4.0.0-src.zip → /home/z/my-project/AimbotPro/AimbotProV3/
- Analyzed project structure: Engine.kt, ModelManager.kt, YoloDetector.kt, build.gradle
- Confirmed assets/models/ had only README.md (DEMO MODE)
- Downloaded yolov8n.onnx from ultralytics/assets v8.4.0 release (13 MB)
- Installed onnx2tf in isolated venv (/home/z/my-project/.cvtenv)
- Wrote conversion script: transpose [1,84,8400] → [1,8400,84] + sigmoid on class scores
- Converted ONNX → TFLite (float16, 6.2 MB) → placed at app/src/main/assets/models/yolov8n.tflite
- Verified YoloDetector.kt handles [1,8400,84] via existing m>=5 (YOLOv5) path + DetectionProcessor.nms()
- Generated 4096-bit RSA release keystore at keystore/release.jks
- Created ~/.gradle/init.d/aimbotpro-signing.gradle.kts to bridge AIMBOT_KEYSTORE_* env vars
- Updated build_apk.sh with env var validation, model asset check, v4.0.0 version

Stage Summary:
- TFLite model dropped: app/src/main/assets/models/yolov8n.tflite (6.2 MB, float16)
- Output shape: [1, 8400, 84] — first 4 = box coords, next 80 = sigmoid class scores
- App auto-detects format via m>=5 path, NMS runs in Kotlin (DetectionProcessor.nms)
- Release keystore: keystore/release.jks (alias=aimbotpro, RSA-4096, 10000 days)
- Env var bridge: set AIMBOT_KEYSTORE_FILE/PASSWORD/ALIAS/KEY_PASSWORD → auto-signs release
- Build: `export AIMBOT_KEYSTORE_FILE=keystore/release.jks AIMBOT_KEYSTORE_PASSWORD=aimbotpro2024 AIMBOT_KEY_ALIAS=aimbotpro AIMBOT_KEY_PASSWORD=aimbotpro2024 && bash build_apk.sh release`
- Note: Android SDK not present in this env — build must run on machine with SDK
