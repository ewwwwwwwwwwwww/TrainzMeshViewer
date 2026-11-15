# AGENTS.md — TrainzMeshViewer (Android Native)

## 1. Project Overview
**TrainzMeshViewer** is a native Android port of the Windows PC utility *Trainz Mesh Viewer* (originally distributed via NSIS installer as `Mesh_Viewer.exe`). The application parses and renders Trainz Simulator 3D mesh assets directly on Android devices using OpenGL ES 3.0 and Jetpack Compose.

- **Package / Namespace:** `id.trainz.meshviewer`
- **Target Assets:** Trainz Indexed Meshes (`.im`), Progressive Meshes (`.pm`), texture descriptor files (`.texture.txt`), and associated image textures (`.tga`, `.png`, `.jpg`).
- **Development Workflow:** Code is edited via GitHub / AI agents and built automatically in the cloud via GitHub Actions (no local Android Studio required).

---

## 2. Tech Stack & Build Environment
- **Language:** Kotlin
- **UI Toolkit:** Jetpack Compose (Material 3)
- **Graphics Engine:** OpenGL ES 3.0 (`GLSurfaceView`, custom GLSL shaders)
- **Build System:** Gradle `8.9` (Kotlin DSL `build.gradle.kts`), JDK `17` (Temurin)
- **CI/CD Pipeline (`.github/workflows/build.yml`):**
  - Runs on `ubuntu-latest` on every push to `main` (and manual `workflow_dispatch`).
  - Compiles `app-debug.apk` via `gradle assembleDebug --stacktrace`.
  - Uploads build artifacts and publishes `app-debug.apk` directly to GitHub Releases under tag `latest-build` using `softprops/action-gh-release@v2`.

---

## 3. Completed Milestones
1. **Binary Format Reverse Engineering & Parser (`TrainzMeshParser.kt`):**
   - Implemented chunk-based binary reader for Trainz `JIRF` container files.
   - Added support for modern and legacy mesh identifiers: `IDXM`, `IMSH`, `PRGM`, and `PMSH`.
   - Added chunk structure handling (`CHNK` / `GEOM` versioned headers and `uint16`/`uint32` triangle index arrays).
   - Added explicit validation and user-friendly Indonesian error messages when users mistakenly select animation files (`.kin` / `ANIM` header) or unsupported files.
2. **Cloud Build & Automated APK Releases (`.github/workflows/build.yml`):**
   - Configured Gradle `8.9` compatibility for Android Gradle Plugin.
   - Added automated GitHub Release publishing so APKs can be downloaded directly on mobile browsers without unzipping workflow artifacts.
3. **Responsive, Touch-Friendly Mobile UI (`MainScreen.kt`):**
   - Redesigned UI around Material 3 ergonomics with minimum `48.dp` touch targets for mobile screens.
   - Implemented orientation-aware layouts (`LocalConfiguration.current.orientation`):
     - **Portrait:** Compact top bar with file name + large `48.dp` action buttons (Open File, Reset Camera), horizontal scrollable `LazyRow` toggle chips (Texture, Wireframe, Bounding Box, Axes, Attachments), and a collapsible `BottomSheetScaffold` for mesh statistics, texture statuses, and attachment points.
     - **Landscape:** Full-screen 3D viewport with floating side controls.
   - Added a prominent Empty State card with a large primary action button when no mesh is loaded.
   - Fixed `BottomSheetScaffold` compilation error by removing the unsupported `contentWindowInsets` parameter and applying `statusBarsPadding()` / `navigationBarsPadding()` properly.

---

## 4. Current Status & Next Steps (TODO)
The codebase is ready for device verification on the latest `latest-build` APK from GitHub Releases. In the next session, focus on:
1. **On-Device Asset Testing:**
   - Test loading modern `.im` (`IDXM`) and legacy `.im`/`.pm` (`IMSH`/`PMSH`) meshes alongside `.texture.txt` and `.tga`/`.png`/`.jpg` textures.
2. **Rendering & Coordinate Calibration (If Issues Appear):**
   - Verify coordinate system transformation from Trainz **Z-up** to OpenGL **Y-up** so models stand upright without 90-degree tilt.
   - Verify UV texture coordinate vertical orientation (flip V-axis if textures render upside-down).
   - Verify `.texture.txt` parsing (`Primary=...`, `Alpha=...`, case-insensitive file matching in Android storage/SAF).
   - Verify attachment point (`a.*`) 3D marker placement and camera auto-framing distance based on bounding box extents.
3. **Parser Edge Cases:**
   - Handle any remaining sub-chunk variations encountered in specific Trainz eras (TRS2004/2006 vs. TS12/TANE/TRS19/TRS22).

---

## 5. Coding & Architecture Guidelines for Agents
- **Touch Accessibility:** Always enforce `Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)` on interactive buttons, icons, and chips.
- **Compose Material 3 APIs:** Do not pass `contentWindowInsets` to `BottomSheetScaffold` (not supported in current M3 version); manage insets via `Modifier.statusBarsPadding()` and `Modifier.navigationBarsPadding()`.
- **User-Facing Strings:** Keep UI labels and error messages clear and consistent with existing Indonesian/English terminology in `MainScreen.kt` and `TrainzMeshParser.kt`.
- **Build Stability:** Keep Gradle at `8.9`+ in `.github/workflows/build.yml` and avoid adding heavy third-party native dependencies that increase APK size or break headless Linux CI builds.
