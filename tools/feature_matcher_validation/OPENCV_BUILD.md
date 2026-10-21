# Official OpenCV 4.12.0 Linux JNI capability

This is an optional cloud HOST prerequisite, not a production Android dependency change. The validation helper never downloads or builds it. Keep source/build/output outside the app repository and do not install system-wide.

## Provenance

- Official repository: https://github.com/opencv/opencv
- Tag `4.12.0`: annotated object `cbee6841638edb6fbc8110df7cd52bb8e3d66211`, resolved commit `49486f61fb25722cbcf586b7f4320921d46fb38e`.
- Immutable archive: https://github.com/opencv/opencv/archive/49486f61fb25722cbcf586b7f4320921d46fb38e.tar.gz
- Observed archive SHA256: `8f00b42869ab2836be36090f6631ae4c38ba59171e22a69a8e0f92f8ef1771d4`. This pins the fetched artifact, not an independently signed publisher attestation.
- Preserve upstream `LICENSE` (Apache-2.0) and applicable bundled notices.

The successful capability used CMake3.22.1, GCC14.2, JDK17 and Ninja. Official4.12 supports Java SDK build mode `JAVA` without Ant: [jar build](https://github.com/opencv/opencv/blob/4.12.0/modules/java/jar/CMakeLists.txt), [Java prerequisites](https://github.com/opencv/opencv/blob/4.12.0/modules/java/CMakeLists.txt). [Module selection](https://docs.opencv.org/4.12.0/db/d05/tutorial_config_reference.html) resolves dependencies; inspect the summary rather than assuming the requested list succeeded.

## Configuration recipe

Set `SOURCE`, `BUILD`, `HOOKS`, `CMAKE`, `NINJA`, `CC`, `CXX`, and `JAVA_HOME` to explicit local paths. Add `$JAVA_HOME/bin` to this command's PATH only. Create `$HOOKS/OPENCV_DOWNLOAD_PRE.cmake` containing:

```cmake
message(FATAL_ERROR "Dependency download forbidden: ${DL_ID} ${DL_FILENAME}")
```

Configure using existing tools:

```sh
"$CMAKE" -S "$SOURCE" -B "$BUILD" -G Ninja \
 -DCMAKE_MAKE_PROGRAM="$NINJA" -DCMAKE_C_COMPILER="$CC" -DCMAKE_CXX_COMPILER="$CXX" \
 -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$BUILD/not-installed" \
 -DBUILD_LIST=core,imgproc,features2d,java -DBUILD_SHARED_LIBS=OFF -DBUILD_FAT_JAVA_LIB=ON \
 -DBUILD_JAVA=ON -DOPENCV_JAVA_IGNORE_ANT=ON -DOPENCV_JAVA_SDK_BUILD_TYPE=JAVA \
 -DOPENCV_CMAKE_HOOKS_DIR="$HOOKS" -DOPENCV_DOWNLOAD_TRIES_LIST= \
 -DBUILD_TESTS=OFF -DBUILD_PERF_TESTS=OFF -DBUILD_EXAMPLES=OFF -DBUILD_DOCS=OFF \
 -DBUILD_opencv_apps=OFF -DBUILD_opencv_world=OFF -DBUILD_opencv_gapi=OFF -DWITH_ADE=OFF \
 -DBUILD_opencv_python2=OFF -DBUILD_opencv_python3=OFF \
 -DWITH_IPP=OFF -DWITH_ITT=OFF -DWITH_OPENCL=OFF -DWITH_CUDA=OFF -DWITH_LAPACK=OFF \
 -DWITH_EIGEN=OFF -DWITH_TBB=OFF -DWITH_OPENMP=OFF -DWITH_V4L=OFF -DWITH_FFMPEG=OFF \
 -DWITH_GSTREAMER=OFF -DWITH_GTK=OFF -DWITH_QT=OFF -DWITH_OPENGL=OFF -DWITH_PROTOBUF=OFF \
 -DWITH_JPEG=OFF -DWITH_PNG=OFF -DWITH_TIFF=OFF -DWITH_WEBP=OFF -DWITH_OPENJPEG=OFF \
 -DWITH_OPENEXR=OFF -DWITH_AVIF=OFF -DWITH_VTK=OFF -DWITH_VA=OFF \
 -DCPU_BASELINE=SSE3 -DCPU_DISPATCH= -DENABLE_LTO=OFF \
 -DPYTHON_DEFAULT_EXECUTABLE="$PYTHON"
```

Require exactly `core features2d imgproc java`, Java wrappers `YES (JAVA)`, no downloaded dependencies. GAPI must be explicitly disabled: its initialization can request ADE before module pruning; the fail-closed hook caught this during the observed first configuration. Preserve failed and successful configure logs.

Build only `opencv_java` with `--parallel 2`; cap at2700seconds. Require4GiB free before starting and monitor free workspace space every5seconds, terminating the entire build process group below2GiB. Do not use a memory-backed temporary directory. Never invoke install. The observed build succeeded in275.030seconds; that is one build observation, not a portable performance promise.

## AAR linkage and receipt

Do not replace the app's OpenCV Java classes with the generated Linux JAR. Extract `classes.jar` from the exact existing4.12 AAR, hash both, and use that extraction on the runtime classpath. Linux normally produces `libopencv_java4120.so`, Android uses `opencv_java4`; load the Linux path explicitly, not by renaming artifacts. [Official naming](https://github.com/opencv/opencv/blob/4.12.0/modules/java/common.cmake)

Before accepting a capability, compare `javap -s -p` native declarations of Core, Mat, Algorithm, MatOfDMatch, BFMatcher and DescriptorMatcher against the generated JAR; verify matching exported JNI symbols. Observed result:361 native declarations matched. Inspect `ldd`/ELF dependencies; reject missing or unexpected OpenCV dependencies. Smoke-test actual Mat get/put/release and BFMatcher against AAR classes, requiring both Java/native version4.12.0 and exact `/proc/self/maps` library path. Generated JAR is only comparison evidence, never the production matcher runtime.

`validate.py` consumes a reviewed capability directory with:

- `build/lib/libopencv_java4120.so`;
- `smoke/aar-input.json`: `aar_sha256` and `classes_jar_sha256`;
- `validation.json`: schema1, classification `HOST_OPENCV_JAVA_JNI_CAPABILITY_ONLY`, `official_source.commit` as above, successful `build.exit_code=0`, `linkage.native_version="4.12.0"`, and `sha256` mapping relative evidence paths to file hashes.

Include the native binary and AAR-input record in that mapping, together with source archive/license/manifest, configuration, build commands/logs and linkage/smoke evidence. Paths must stay inside the capability directory. Generate hashes with a SHA256 implementation; do not relabel arbitrary prebuilt binaries as source-built. The helper verifies the supplied receipt's pinned files; it cannot independently attest the truth of arbitrary build-history claims. Current-source and output hashes are separately checked before/after matcher compilation/execution. Preserve original source files; record any generated additions (the observed generator added one Python bytecode-cache file).

This Linux GCC/SSE3 build disables IPP/OpenCL and is not equivalent to Android binaries, GPU behavior or device performance. Full Android Bitmap conversion/decoding remains outside this gate.
