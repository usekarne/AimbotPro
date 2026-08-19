# YOLO model assets

Place your trained YOLO TFLite model here:

```
yolov8n.tflite       # YOLOv8n / YOLOv5n quantized or float16
coco_labels.txt      # one label per line (class indices 0..N-1)
```

**Default file**: `yolov8n.tflite` (set in `Constants.kt` — `DEFAULT_MODEL_NAME`)

If no model is present, the app runs in **DEMO MODE** — it generates
simulated detections so you can still test the mod menu, overlay UI, and
input injection pipeline. Once you supply a real TFLite YOLO model,
inference switches automatically.

**Recommended models** (open-source, train on your own game dataset):
- yolov8n.tflite  — fastest (640x640 input, ~8ms on SD88 Gen1 GPU)
- yolov5n.tflite  — equivalent speed
- yolov8s.tflite  — more accurate, ~3x slower

**Convert custom YOLO → TFLite**:
```bash
yolo export model=yolov8n.pt format=tflite imgsz=640 int8=True
```
