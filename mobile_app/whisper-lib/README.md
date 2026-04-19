# whisper-lib

This module vendors the upstream `whisper.cpp` Android sample and the minimal
native sources needed to build it inside this repo.

Pinned upstream:

- repo: `https://github.com/ggml-org/whisper.cpp`
- tag: `v1.7.6`
- commit: `a8d002cfd879315632a579e73f0148d06959de36`

Layout:

- `src/main/java/com/whispercpp/whisper/` contains the Kotlin-facing wrapper
  used by the app module.
- `src/main/jni/whisper/jni.c` and `src/main/jni/whisper/CMakeLists.txt` come
  from `examples/whisper.android/lib` with local path/build tweaks.
- `src/main/jni/whisper/whisper.cpp/` contains vendored `include/`, `src/`,
  and `ggml/` directories from the upstream repo root.

Refresh procedure:

1. Clone the pinned upstream tag locally.
2. Copy `examples/whisper.android/lib/` into `mobile_app/whisper-lib/`.
3. Copy upstream `include/`, `src/`, and `ggml/` into
   `mobile_app/whisper-lib/src/main/jni/whisper/whisper.cpp/`.
4. Re-apply local edits:
   - `build.gradle.kts`
   - `src/main/jni/whisper/CMakeLists.txt`
   - `src/main/java/com/whispercpp/whisper/WhisperLib.kt`
   - `src/main/java/com/whispercpp/whisper/WhisperContext.kt`
5. Rebuild with `./gradlew :whisper-lib:assembleDebug`.
