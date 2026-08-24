# CameraApp

Minimal Android app that requests CAMERA permission and saves photos to the app's external files directory.

Build & run:

1. Open the project in Android Studio: open the folder `/Users/chanchal.chauhan/Documents/android-camera-app`.
2. Let Android Studio sync Gradle and install required SDK components.
3. Run on a device or emulator with a camera.

Notes:
- Runtime permission for CAMERA is requested.
- Images are stored under the app's external files directory (Documents-like, accessible via `getExternalFilesDir`).
