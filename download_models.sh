#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
A=app/src/main/assets
mkdir -p "$A"
B=https://storage.googleapis.com/mediapipe-models
curl -L --fail -o "$A/pose_landmarker_lite.task" "$B/pose_landmarker/pose_landmarker_lite/float16/latest/pose_landmarker_lite.task"
curl -L --fail -o "$A/face_landmarker.task" "$B/face_landmarker/face_landmarker/float16/latest/face_landmarker.task"
curl -L --fail -o "$A/selfie_multiclass_256x256.tflite" "$B/image_segmenter/selfie_multiclass_256x256/float32/latest/selfie_multiclass_256x256.tflite"
ls -lh "$A"
