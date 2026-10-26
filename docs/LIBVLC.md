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

The JNI snapshot contains the declared 3.7.7 version, the pinned native source recipe, and all 20 patches. The VLC snapshot includes 117 contrib package recipes but **no external contrib source archives** under `contrib/tarballs/`. That initial two-archive inspection was incomplete; the separate companion distribution below now retains the required external sources and validation materials.

For source collection, the pinned contrib makefile's `fetch` target downloads and verifies sources for the selected package set; `fetch-all` covers all package recipes. Its `package` target archives installed contrib files rather than their source tarballs. The companion retains the conservative required source union, patches, checksums, notices and recorded configurations across all four shipped ABIs. This source-input checkpoint is distinct from compiling the native libraries.

### Prepared 0.3.0 companion source distribution

The separate [LibVLC 3.7.7 corresponding-source companion](https://github.com/MM-482/pov-android-tv/releases/download/v0.3.0-preview/POV-Android-TV-0.3.0-LibVLC-3.7.7-corresponding-sources.zip) is prepared alongside the binary and [POV application-source ZIP](https://github.com/MM-482/pov-android-tv/releases/download/v0.3.0-preview/POV-Android-TV-0.3.0-source.zip). Versioned assets become available when their [0.3.0 release](https://github.com/MM-482/pov-android-tv/releases/tag/v0.3.0-preview) is published; consult [public releases](https://github.com/MM-482/pov-android-tv/releases) for download availability.

| Companion identity | Value |
| --- | --- |
| Filename | `POV-Android-TV-0.3.0-LibVLC-3.7.7-corresponding-sources.zip` |
| Bytes | 380,536,553 |
| SHA-256 | `14482948d028d4b5ee66ca2166321ad2cb8ab418825e51bf60463a1b857b4bfe` |
| ZIP entries | 421 relative entries |

The companion contains the two pinned JNI/VLC snapshots plus **65 required contrib source archives for 66 logical packages**; Lua and its host compiler share an archive. It includes source URLs, upstream checksum records and Git sidecars, the 20 native patches, recorded configuration flags from all four unchanged binaries, 292 extracted upstream license/notice texts, and NDK/C++ runtime and cpu-features source/notice/provenance materials. The original source archives retain their own complete upstream notice files. `SOURCE-INVENTORY.json`, `LICENSE-INVENTORY.json`, `BINARY-CONFIGURATIONS.json`, `FILE-SHA256SUMS.txt` and `VALIDATION.json` identify the inputs and checks inside the companion.

The completed local checkpoint verified:

- Linux assembly of the pinned VLC base with all 20 JNI patches, producing content tree `fde51f9c24338db6342c5c4f815a29fb9fd667e9`. This is a tree identity, not a claim to reproduce upstream's temporary Git commit hashes.
- The original contrib `make fetch` checksum/Git-hash targets for all four ABI configurations with compiler and network commands disabled. System pkg-config dependencies were treated as unavailable to retain sources conservatively.
- Retained upstream/NDK notices, native configuration records, archive CRC, safe relative entries, and absence of private host-path material in the reviewed companion.
- Offline assembly of the POV consumer app with the unchanged pinned AAR supplied through a local-file dependency. This exercises the library replacement entry point and consumer packaging.

**No native compilation, modified-library device run, or byte-identical native/AAR rebuild was performed.** Source coverage follows the exact pinned recipe and conservative Android dependency union. This checkpoint records source availability, provenance, notices and the exercised consumer route; it is not an independent legal-compliance opinion or proof of native binary reproducibility.

## Native build starting point

Use a Linux environment with the prerequisites from the [pinned upstream README](https://code.videolan.org/videolan/libvlcjni/-/blob/0b8dc65efb203c86a0476bc337ddd99ecf1c0ef6/README.md). The companion's README and `prepare_sources.py` verify the retained archive hashes and assemble the already-patched source in a new working directory without spaces. Do not run `get-vlc.sh` again on that assembled tree. The real native entry point in this revision is `buildsystem/compile-libvlc.sh`; the README's older `compile.sh` example is not present in the pinned repository.

```sh
# Run from the extracted companion directory; choose a new source-work path.
python3 prepare_sources.py --destination /your/new/libvlc-source-work
cd /your/new/libvlc-source-work/libvlcjni
# Set ANDROID_HOME and ANDROID_NDK first, using the recorded ABI toolchain.
./buildsystem/compile-libvlc.sh -a arm64 --license g
```

The script accepts `arm`, `arm64`, `x86`, and `x86_64`. The recorded binaries use NDK 21.4.7075529 for 32-bit and NDK 29.0.14206865 for 64-bit builds. Their configure records include debug support, so the example omits `--release` and retains the GPLv3 contrib configuration. Upstream's [pinned CI configuration](https://code.videolan.org/videolan/libvlcjni/-/blob/0b8dc65efb203c86a0476bc337ddd99ecf1c0ef6/buildsystem/gitlab/.gitlab-ci.yml) names the build containers `registry.videolan.org/vlc-debian-android-3.0:20260701183656` for 32-bit and `registry.videolan.org/vlc-debian-android:20260611225331` for 64-bit. Build changed native libraries from the retained sources rather than selecting prebuilt contrib binaries. Ordinary Linux tools and Android SDK/NDKs remain prerequisites; they are not bundled tool installations.

Generated native libraries are under each `vlc/build-android-<target>/ndk/libs/<ABI>/` directory. The companion's `replace_native_in_aar.py` checks the original AAR hash and replacement ELF architectures when inserting interface-compatible native changes. For Java/JNI interface changes, rebuild the included binding module and matching native libraries with upstream's required tools; POV's Android Gradle version is independent of upstream's. These are supplied build/replacement instructions, not a claim that native compilation or a byte-identical AAR rebuild has been completed.

## Replacing/relinking the library in POV

POV includes dynamically loaded `.so` files from the AAR. A user can modify the library sources, rebuild the required ABI libraries/AAR, then replace this application's Maven dependency with their local AAR, for example:

```kotlin
// In app/build.gradle.kts, replace the org.videolan.android dependency:
implementation(files("libs/libvlc-modified.aar"))
```

Build POV from its matching application source with `scripts/build.ps1`, which creates a debug-signed sideload APK. The local-file consumer route passed an offline app assembly using the original pinned AAR; a modified native engine was not compiled or exercised on a device. A replacement APK must be signed with a key controlled by its builder. An installed copy signed by another key may need to be removed before installing that replacement; Android's signing requirement is separate from access to the library sources. This preview does not add DRM or an application check intended to block a modified library. See the [POV application source repository](https://github.com/MM-482/pov-android-tv), the corresponding source snapshot supplied with the particular APK, and [`LICENSE`](../LICENSE).

## Redistribution boundary

The release materials pair the unchanged pinned AAR identity and GPL application source with the separate native-source companion, its build/configuration/checksum records, upstream notices and library replacement instructions. The required archive union is retained and its four-ABI offline fetch checks passed. Native compilation, modified-runtime testing and byte-identical reproduction remain unperformed. Retain the applicable upstream licenses and notices when redistributing or modifying these materials; the verified source-input checkpoint is not an independent legal conclusion about every redistribution obligation.
