# Embedded LibVLC: license, source, and rebuilding

POV optionally embeds `org.videolan.android:libvlc-all:3.7.7`. This is the VideoLAN playback library, not the separate VLC Android application. POV's own interface and controls remain in this application's source. No changes to the upstream AAR, JNI bindings, or native libraries were made for this integration.

The [official Maven POM](https://repo.maven.apache.org/maven2/org/videolan/android/libvlc-all/3.7.7/libvlc-all-3.7.7.pom) identifies VideoLAN and declares LGPL version 2.1. The upstream Java/JNI source headers permit LGPL 2.1 or later. The complete upstream `libvlc/COPYING.LIB` text is packaged in the APK as [`LibVLC-LICENSE.txt`](../app/src/main/assets/LibVLC-LICENSE.txt); attribution is also in [`NOTICE.txt`](../app/src/main/assets/NOTICE.txt). Individual native codecs, support libraries, assets, and the C++ runtime retain their own licenses. The Maven POM's single license entry is not a complete inventory of those components. In particular, the pinned native build script defaults to a GPLv3 contrib configuration; POV's application source is GPL version 3.

## Artifact and source identity

The exact dependency is available from the [version-specific Maven directory](https://repo.maven.apache.org/maven2/org/videolan/android/libvlc-all/3.7.7/), including its AAR, Java source JAR, and POM. The AAR used in the local build has SHA-256:

```text
b48dab96e0e90e34cce3861963500c144a5aadb1b249d501d6a4b104d849e61b
```

The AAR's `res/values/values.xml` records `build_libvlc_revision=0b8dc65` and `build_vlc_revision=586dd059ac`. The matching official libvlcjni commit is [`0b8dc65efb203c86a0476bc337ddd99ecf1c0ef6`](https://code.videolan.org/videolan/libvlcjni/-/tree/0b8dc65efb203c86a0476bc337ddd99ecf1c0ef6); its `build.gradle` sets `libvlcVersion = '3.7.7'`. There is no verified `3.7.7` Git tag, so these instructions pin the commit instead of assuming a tag exists.

The native source recipe is [`buildsystem/get-vlc.sh` at that commit](https://code.videolan.org/videolan/libvlcjni/-/blob/0b8dc65efb203c86a0476bc337ddd99ecf1c0ef6/buildsystem/get-vlc.sh). It fetches the [official VLC repository](https://code.videolan.org/videolan/vlc), checks out [`84177b2273abc5c4300234e6e18b55a4d2dd5a03`](https://code.videolan.org/videolan/vlc/-/tree/84177b2273abc5c4300234e6e18b55a4d2dd5a03), and applies the 20 patches under [`libvlc/patches`](https://code.videolan.org/videolan/libvlcjni/-/tree/0b8dc65efb203c86a0476bc337ddd99ecf1c0ef6/libvlc/patches) with `git am --message-id`. Applying patches creates additional local Git commits, whose hashes can depend on build-time metadata. The AAR's native revision string is recorded above as binary evidence; it has not been established as a separately published, directly fetchable upstream commit. The inspected ARM64 library also contains the version string `3.0.25 Vetinari`; that string alone does not establish a full source correspondence or a reproducible build.

The Java source JAR alone is insufficient to rebuild the native engine. Corresponding native sources include VLC, these JNI patches and build scripts, the enabled contrib dependencies and their patches/licenses, and the relevant build configuration.

### Inspected pinned source archives

On 2026-10-26, these two exact official VideoLAN repository snapshots were downloaded and inspected. The SHA-256 values below identify the inspected archive bytes; they are locally computed integrity hashes, not upstream signed archive checksums.

| Source snapshot | Official archive | Bytes | SHA-256 |
| --- | --- | ---: | --- |
| JNI bindings and build recipe | [libvlcjni at the pinned commit](https://code.videolan.org/videolan/libvlcjni/-/archive/0b8dc65efb203c86a0476bc337ddd99ecf1c0ef6/libvlcjni-0b8dc65efb203c86a0476bc337ddd99ecf1c0ef6.tar.gz) | 260,901 | `bafba3f63830d7a049d753f8ffe6fecdcf0abcfed8e449355e87205da18854ec` |
| Native VLC base before the JNI patches | [VLC at the pinned base commit](https://code.videolan.org/videolan/vlc/-/archive/84177b2273abc5c4300234e6e18b55a4d2dd5a03/vlc-84177b2273abc5c4300234e6e18b55a4d2dd5a03.tar.gz) | 34,457,526 | `aeb337f3487011d5f826b360b47c32c28fa3dbca737ac638b91f8ce31147ad20` |

The JNI snapshot contains the declared 3.7.7 version, the pinned native source recipe, and all 20 patches. The VLC snapshot includes 117 contrib package recipes but **no external contrib source archives** under `contrib/tarballs/`. These two repository snapshots therefore do not establish a complete native/contrib source distribution for the AAR.

For source collection, the pinned contrib makefile's `fetch` target downloads and verifies sources for the selected package set; `fetch-all` covers all package recipes. Its `package` target archives installed contrib files rather than their source tarballs. Retain the required source archive union, patches, checksum records, licenses, and actual configuration for all four shipped ABIs. The selected contrib set and exact toolchain/publication configuration still need to be established against the distributed binary. No all-ABI native rebuild, offline replacement-library build, or complete corresponding-source bundle has been verified by this archive inspection.

## Native build starting point

Use a Linux environment with the prerequisites from the [pinned upstream README](https://code.videolan.org/videolan/libvlcjni/-/blob/0b8dc65efb203c86a0476bc337ddd99ecf1c0ef6/README.md). The real native entry point in this revision is `buildsystem/compile-libvlc.sh`; the README's older `compile.sh` example is not present in the pinned repository.

```sh
git clone https://code.videolan.org/videolan/libvlcjni.git
cd libvlcjni
git checkout 0b8dc65efb203c86a0476bc337ddd99ecf1c0ef6
./buildsystem/get-vlc.sh
# Set ANDROID_HOME and ANDROID_NDK to the selected SDK and NDK directories.
# Run one ABI at a time with the NDK appropriate to that ABI:
./buildsystem/compile-libvlc.sh -a arm64 --release
```

The script accepts `arm`, `arm64`, `x86`, and `x86_64`. Its checked NDK families are 21 for 32-bit builds and 27–29 for 64-bit builds. Upstream's [pinned CI configuration](https://code.videolan.org/videolan/libvlcjni/-/blob/0b8dc65efb203c86a0476bc337ddd99ecf1c0ef6/buildsystem/gitlab/.gitlab-ci.yml) names the build containers `registry.videolan.org/vlc-debian-android-3.0:20260701183656` for 32-bit and `registry.videolan.org/vlc-debian-android:20260611225331` for 64-bit. It also documents how prebuilt contribs are selected. For a source distribution/rebuild, preserve the corresponding contrib source archives and patches as well; a URL for a prebuilt contrib binary is not a substitute for those sources.

Generated native libraries are under each `vlc/build-android-<target>/ndk/libs/<ABI>/` directory. The JNI module's `libvlc/build.gradle` includes `jni/libs` plus the directory supplied by `GRADLE_VLC_SRC_DIRS`, so rebuilt native ABI folders can be staged there when assembling a replacement AAR. Use the pinned upstream Gradle/build configuration and its required tool versions when rebuilding the Java/JNI AAR; POV's Android Gradle version is independent of upstream's. These steps document the upstream build recipe. This Windows integration has not rebuilt all native dependencies or demonstrated a byte-identical replacement AAR.

## Replacing/relinking the library in POV

POV includes dynamically loaded `.so` files from the AAR. A user can modify the library sources, rebuild the required ABI libraries/AAR, then replace this application's Maven dependency with their local AAR, for example:

```kotlin
// In app/build.gradle.kts, replace the org.videolan.android dependency:
implementation(files("libs/libvlc-modified.aar"))
```

Build POV from its matching application source with `scripts/build.ps1`, which creates a debug-signed sideload APK. A replacement APK must be signed with a key controlled by its builder. An installed copy signed by another key may need to be removed before installing that replacement; Android's signing requirement is separate from access to the library sources. This preview does not add DRM or an application check intended to block a modified library. See the [POV application source repository](https://github.com/MM-482/pov-android-tv), the corresponding source snapshot supplied with the particular APK, and [`LICENSE`](../LICENSE).

## Redistribution boundary

For a distributed binary, retain the exact AAR and matching source/build provenance, the GPL application source, this license text and notices, and the complete corresponding sources and license notices for the enabled native components. Provide recipients a working source/relinking route appropriate to the applicable licenses, including any required installation information. Hosting a generic VideoLAN link, copying only the Java source JAR, or including this document does not by itself prove that every redistribution obligation has been fulfilled. Completing and checking that native source/contrib bundle and replacement-library build remains release work before broad publication of this new VLC-enabled build.
