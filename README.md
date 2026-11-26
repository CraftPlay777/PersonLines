# PersonLines

App Android que dibuja trazos sobre partes del cuerpo (piernas, brazos, torso, cabeza, ojos, cabello) en la cámara o en un video.

- Gradle + Kotlin, CameraX, MediaPipe Tasks (pose, cara y segmentación de cabello)
- Los modelos no están en el repo: `download_models.sh` los baja (el workflow lo hace solo)
- Compila con GitHub Actions (`.github/workflows/build.yml`), el APK sale en Artifacts
