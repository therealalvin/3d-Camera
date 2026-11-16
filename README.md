# 3d Camera

**3d Camera** is a native Android spatial photography app built with Kotlin, Jetpack Compose, ARCore, CameraX, and OpenGL ES 2.0. It turns a brief handheld multi-angle wave capture into interactive 3D parallax photos saved as standard **Google GDepth XMP JPEGs**—viewing normally in Google Photos while coming alive in 3D inside the app or exported as Side-by-Side (SBS) VR photos for Meta Quest.

---

## Features

- **Guided 3×3 Wave Capture**:
  - Intuitive 9-tile chalkboard-erase overlay tracks your up/down/left/right camera sweep and captures intermediate frames in between positions for smooth multi-view depth reconstruction.
  - Gimbal-lock-free 3D orientation tracking prevents inverted axes or vertical `-90°` pitch flips.
- **Live 2-Axis Spirit-Level Tilt Meter**:
  - Sensitive `±3.2°` sweet-spot bubble level keeps your phone flat and parallel during capture, automatically pausing tile clearing and rejecting skewed frames (`>3.6°`) before they can distort the 3D result.
- **Dual Depth Pipeline (ARCore + CameraX Fallback)**:
  - **ARCore Hardware Depth Boost**: Uses `Depth16` metric depth fused across steady, well-aligned frames with LUT-accelerated joint bilateral refinement and hole-filling.
  - **CameraX + IMU Stereo Fallback**: Works on devices without ARCore using axis-aligned stereo disparity matching, `3×3` median despiking, and texture-contrast gating.
- **Zero-Skew Multi-Frame Denoising**:
  - Aligns nearby sweep frames onto the sharpest centered hero frame with strict block-SAD and per-pixel consistency checks to clean grain on stationary subjects without ghosting.
- **Interactive 3D Viewer & Review**:
  - OpenGL ES 2.0 parallax mesh renderer (`RENDERMODE_WHEN_DIRTY`) driven smoothly by device tilt or touch drag, with border overscan and edge tapering to eliminate corner warping.
- **Universal Compatibility & VR Export**:
  - Embeds standard **Google GDepth XMP** metadata (`RangeInverse`) directly inside JPEG `APP1` / Extended XMP segments.
  - Opens any existing GDepth JPEG via Android's zero-permission Photo Picker or `ACTION_VIEW` intent.
  - One-tap **Save 3D for VR** exports high-resolution Side-by-Side (SBS) stereo JPEGs to `Pictures/3d Camera/VR Export`.
- **Battery-Efficient Design**:
  - Motion sensors activate only during active capture or 3D viewing and unregister immediately on `ON_PAUSE`.
  - Display stays awake (`keepScreenOn`) strictly during sweeping/processing and releases immediately afterward.
  - OpenGL renderer redraws on demand (`RENDERMODE_WHEN_DIRTY`) instead of continuously spinning the GPU when resting still.

---

## Tech Stack

- **Language**: Kotlin 2.2
- **UI**: Jetpack Compose + Material 3 (Edge-to-Edge, Adaptive Portrait & Landscape layouts)
- **Navigation**: Type-safe Navigation Compose (`@Serializable` routes)
- **Camera & Spatial Tracking**: ARCore (`com.google.ar:core`), CameraX (`camera-camera2`, `camera-lifecycle`, `camera-view`), Android `SensorManager` (`TYPE_GAME_ROTATION_VECTOR` + `TYPE_GYROSCOPE`)
- **3D Rendering & Imaging**: OpenGL ES 2.0 (`GLSurfaceView`), custom multi-frame depth/RGB fusion (`DynamicDepthCodec`), `androidx.exifinterface`
- **Persistence**: Room (local photo index), DataStore Preferences (Depth Boost toggle), Android MediaStore / Photo Picker
- **Testing**: JUnit 4 + Robolectric

---

## Project Structure

```text
app/src/main/java/com/example/
├── MainActivity.kt                     # Edge-to-edge host & Jetpack Navigation setup
├── capture/
│   ├── CameraPreviewHost.kt            # ARCore Depth16 + CameraX fallback pipeline
│   ├── CaptureViewModel.kt             # 3x3 sweep state, tilt gating, frame pool & fusion
│   ├── MotionSensorMonitor.kt          # Gimbal-lock-free 3D basis & spirit-level sensor math
│   ├── SmartCaptureHelpers.kt          # Quaternion deltas, depth density, sharpness scoring
│   └── YuvBitmapConverter.kt           # Fast YUV_420_888 to upright Bitmap conversion
├── imaging/
│   └── DynamicDepthCodec.kt            # Multi-frame denoising, depth fusion, GDepth XMP, SBS VR export
├── viewer/
│   └── SpatialParallaxSurfaceView.kt   # Battery-efficient OpenGL ES 2.0 3D parallax mesh renderer
├── data/
│   ├── AppDatabase.kt                  # Room database
│   ├── CameraPreferences.kt            # DataStore settings (Pixel Depth Boost)
│   ├── SpatialMediaRepository.kt       # MediaStore saving, Photo Picker import & VR export
│   ├── SpatialPhotoDao.kt              # Room DAO
│   └── SpatialPhotoEntity.kt           # Local metadata entity
└── ui/
    ├── CaptureCameraScreen.kt          # 3x3 capture grid, live tilt meter, shutter controls
    ├── CaptureReviewScreen.kt          # Pre-save 3D preview & retake/save flow
    ├── SpatialGalleryScreen.kt         # Saved 3D photo grid & Photo Picker entry
    ├── SpatialViewerScreen.kt          # Fullscreen tilt/drag 3D viewer, VR export & delete
    └── theme/                          # Material 3 colors, shapes & typography
```

---

## Permissions & Privacy

- **`android.permission.CAMERA`**: Required solely to capture photos and spatial sweep frames.
- Zero broad storage permissions (`READ_EXTERNAL_STORAGE` / `READ_MEDIA_IMAGES` are not requested); media selection uses the zero-permission Android Photo Picker and saving uses scoped `MediaStore`.
- All depth estimation, image fusion, and GDepth encoding run 100% on-device with no network or cloud uploads.

---

## Building & Testing

1. **Prerequisites**:
   - Android Studio (AGP 9.1+, JDK 11+, Android SDK 36)
   - Physical Android device (Pixel or ARCore-capable device recommended for hardware `Depth16`; CameraX fallback works on standard devices)
2. **Build Debug APK**:
   ```bash
   ./gradlew :app:assembleDebug
   ```
3. **Run Unit & Robolectric Tests**:
   ```bash
   ./gradlew :app:testDebugUnitTest
   ```
4. **Release Signing**:
   Release builds read keystore credentials from environment variables (`KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD`) configured in `app/build.gradle.kts`.
