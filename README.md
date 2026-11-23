# 3d Camera

**3d Camera** is a native Android spatial photography app built with Kotlin, Jetpack Compose, ARCore, CameraX, and OpenGL ES 2.0. It turns a handheld multi-angle camera sweep into interactive 3D parallax photos saved as standard **Google GDepth XMP JPEGs**—viewing normally in Google Photos while letting you explore them in 3D inside the app or export Side-by-Side (SBS) VR photos for Meta Quest.

---

## Features

- **Adaptive Sweep, Per-Area Detail Map & Red → Green Quality Meter**:
  - Captures frames continuously as you move smoothly around your subject—no rigid box-by-box completion required.
  - A live color-coded **3×3 Detail Coverage Overlay** (`Red` low/missing, `Amber` fair, `Green` crisp) highlights the weakest region (`Low detail: <Region> — move there`) so you know exactly where to move next.
  - A live **Image Quality** indicator transitions smoothly from **Poor (Red)** through **Fair** and **Good** to **Excellent (Green)** based on frame count, horizontal/vertical baseline coverage, optical sharpness, steadiness, and depth density.
  - Explicit **Accept** and **Cancel** buttons let you decide when quality looks good enough to build the 3D photo.
- **Live Better/Worse Feedback & Best-Patch Multi-Frame Stacking**:
  - Every candidate shot during capture is scored in real time for optical sharpness, gyro steadiness, levelness, and `Depth16` coverage, displaying live **Better than before** / **Worse than before** feedback as you move.
  - Moving over an area automatically upgrades weaker frames while protecting top per-region patch donors.
  - During 3D synthesis, overlapping `24×24` tent-feathered patches across up to 6 pose-rectified frames are stacked (`preferSharpestPatches`) so the sharpest local regions from each capture combine into the final photo.
- **Live Spirit-Level Tilt Meter**:
  - Real-time 2-axis bubble level and gimbal-lock-free 3D orientation tracking help you keep tilt steady while sweeping, while small accidental roll/pitch offsets are mathematically compensated during alignment.
- **Pose-Guided Rectification & Distortion-Free Fusion**:
  - Computes 3D relative pose (`yaw`, `pitch`, `roll`) between each sweep frame and the sharpest centered hero frame, applies single-pass projective de-rotation plus coarse-to-fine edge alignment (`edgeMatchScore`), and fuses aligned `RGB` + `Depth16` observations to minimize edge stretching, bottom-edge skew, and ghosting.
- **Dual Depth Pipeline (ARCore + CameraX Fallback)**:
  - **ARCore Hardware Depth Boost**: Fuses warped metric `Depth16` maps across aligned frames with joint bilateral refinement and hole-filling.
  - **CameraX + IMU Stereo Fallback**: Works on devices without ARCore using rectified stereo disparity matching, `3×3` median despiking, and texture-contrast gating.
- **True 3D Perspective Tilt, Twist Rotation & Pinch-to-Zoom (Preview & Viewer)**:
  - Drag smoothly with one finger to tilt the photo in true 3D perspective space (pitch & yaw) rather than stretching 2D pixels—holding your inspected perspective without requiring phone tilting.
  - Two-finger **twist to rotate** around the 3D Z-axis and **pinch to zoom in (`1.0×`–`4.0×`)** anchored at the pinch centroid, with bounded panning and **double-tap** to reset view.
- **Universal Compatibility & VR Export**:
  - Embeds standard **Google GDepth XMP** metadata (`RangeInverse`) inside JPEG `APP1` / Extended XMP segments.
  - Opens any existing GDepth JPEG via Android's zero-permission Photo Picker or `ACTION_VIEW` intent.
  - One-tap **Save 3D for VR** exports high-resolution Side-by-Side (SBS) stereo JPEGs to `Pictures/3d Camera/VR Export`.
- **Battery-Efficient Design**:
  - Motion sensors run only on the camera capture screen and unregister immediately on `ON_PAUSE`; preview and 3D viewing require zero sensor overhead.
  - Display stays awake (`keepScreenOn`) strictly during active sweeping/processing and releases immediately afterward.
  - OpenGL ES 2.0 renderer (`RENDERMODE_WHEN_DIRTY`) redraws on demand only when textures or touch/zoom targets are actively animating.

---

## Tech Stack

- **Language**: Kotlin 2.2
- **UI**: Jetpack Compose + Material 3 (Edge-to-Edge, Adaptive Portrait & Landscape layouts)
- **Navigation**: Type-safe Navigation Compose (`@Serializable` routes)
- **Camera & Spatial Tracking**: ARCore (`com.google.ar:core`), CameraX (`camera-camera2`, `camera-lifecycle`, `camera-view`), Android `SensorManager` (`TYPE_GAME_ROTATION_VECTOR` + `TYPE_GYROSCOPE`)
- **3D Rendering & Imaging**: OpenGL ES 2.0 (`GLSurfaceView`), pose-guided multi-frame rectification & depth/RGB fusion (`DynamicDepthCodec`), `androidx.exifinterface`
- **Persistence**: Room (local photo index), DataStore Preferences (Depth Boost toggle), Android MediaStore / Photo Picker
- **Testing**: JUnit 4 + Robolectric

---

## Project Structure

```text
app/src/main/java/com/example/
├── MainActivity.kt                     # Edge-to-edge host & Navigation Compose setup
├── capture/
│   ├── ArCoreBackgroundRenderer.kt     # ARCore camera pass-through GL background renderer
│   ├── CameraPreviewHost.kt            # ARCore Depth16 + CameraX fallback pipeline
│   ├── CaptureViewModel.kt             # Adaptive sweep, 3x3 detail scores, Better/Worse feedback & best-frame pool
│   ├── MotionSensorMonitor.kt          # Gimbal-lock-free 3D basis & spirit-level sensor math
│   ├── SmartCaptureHelpers.kt          # Relative 3D pose, 3x3 grid patch sharpness & depth density scoring
│   └── YuvBitmapConverter.kt           # Fast YUV_420_888 to upright Bitmap conversion
├── imaging/
│   └── DynamicDepthCodec.kt            # Projective rectification, best-patch stacking, GDepth XMP & SBS VR export
├── viewer/
│   ├── SpatialParallaxSurfaceView.kt   # True 3D perspective camera OpenGL ES 2.0 parallax, rotation & zoom renderer
│   ├── SpatialViewGestures.kt          # 3D tilt drag, two-finger twist rotation, pinch-to-zoom & double-tap reset
│   └── ViewerViewModel.kt              # Photo loading, Quest SBS VR export & delete state
├── data/
│   ├── AppDatabase.kt                  # Room database
│   ├── CameraPreferences.kt            # DataStore settings (Pixel Depth Boost)
│   ├── SpatialMediaRepository.kt       # MediaStore saving, Photo Picker import & VR export
│   ├── SpatialPhotoDao.kt              # Room DAO
│   └── SpatialPhotoEntity.kt           # Local metadata entity
└── ui/
    ├── CaptureCameraScreen.kt          # Adaptive viewfinder, 3x3 detail map, Red->Green meter & Accept/Cancel
    ├── ReviewPhotoScreen.kt            # Post-capture 3D tilt/rotate/pinch preview with Save or Retake actions
    ├── SimpleGalleryScreen.kt          # Saved 3D photo grid & zero-permission Photo Picker entry
    ├── CleanViewer3DScreen.kt          # Fullscreen 3D tilt/rotate/pinch viewer, VR export & delete
    └── theme/                          # Material 3 colors, shapes & typography
```

---

## Permissions & Privacy

- **`android.permission.CAMERA`**: Required solely to capture photos and spatial sweep frames.
- Zero broad storage permissions (`READ_EXTERNAL_STORAGE` / `READ_MEDIA_IMAGES` are not requested); media selection uses the zero-permission Android Photo Picker and saving uses scoped `MediaStore`.
- All depth estimation, rectification, image fusion, and GDepth encoding run 100% on-device with no network or cloud uploads.

---

## Building & Testing

1. **Prerequisites**:
   - Android Studio (AGP 9.1+, JDK 11+, Android SDK 36)
   - Physical Android device (Pixel or ARCore-capable device recommended for hardware `Depth16`; CameraX fallback works on standard devices)
2. **Build Debug APK**:
   ```bash
   ./gradlew :app:assembleDebug
   ```
   A pre-built debug package is also included at `apk/3dCamera-v13-debug.apk` (`versionCode 13`).
3. **Run Unit & Robolectric Tests**:
   ```bash
   ./gradlew :app:testDebugUnitTest
   ```
4. **Release Signing**:
   Release builds read keystore credentials from environment variables (`KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD`) configured in `app/build.gradle.kts`.
