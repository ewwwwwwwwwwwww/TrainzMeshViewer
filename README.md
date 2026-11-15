# TrainzMeshViewer

A Kotlin Android application (minSdk 26) using Jetpack Compose and an OpenGL ES 3.0 `GLSurfaceView` to inspect Trainz indexed/progressive mesh files.

## Open in Android Studio

Open this directory as a Gradle project and run the `app` configuration on an Android 8.0+ device or emulator with GLES 3.0 support. The app does not request broad storage access: choose a mesh with the system file picker, or choose an asset folder to load a mesh together with sibling texture files.

## Implemented reader contract

`TrainzMeshParser` validates the `JIRF` header and `IMSH`/`PMSH` type, then reads 4-byte aligned chunks using little-endian sizes. The supported payload layout is the one specified for this project: `INFO` with two uint32 counts and min/max XYZ; `MATL` with a null-terminated name followed by ambient/diffuse/specular RGBA floats and opacity; `TEXS` with a uint32 count and null-terminated names; each `GEOM` containing `MATI`, `VERT` (count plus 8 float32 values per vertex), and `TRIS` (index width byte, count, indices); and `ATT ` with count, null-terminated names, position and 3x3 rotation.

The public Trainz documentation describes mesh contents conceptually but does not specify this complete binary layout. Consequently this implementation deliberately validates the stated chunk contract and rejects inconsistent/truncated chunks. It should be checked against representative `.im` and `.pm` files before treating it as a general-purpose decoder; exporters may use additional vertex attributes, nested chunks, or different material records. It does not implement PM progressive edge-collapse playback, skeletal animation, or Trainz proprietary `.texture` decoding. PM files are displayed from indexed geometry chunks when they use the supported layout.

Supported sidecar image formats are Android-decoded JPEG/PNG/BMP and a local TGA decoder for uncompressed and RLE true-color 24/32-bit images. Folder selection is the reliable way to give SAF access to sibling `.texture.txt` and image files. A single-file grant may not grant access to sibling documents on all Android document providers.
