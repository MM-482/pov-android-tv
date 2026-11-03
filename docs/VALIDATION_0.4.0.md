# Cameo Android 0.4.0-preview validation

Recorded **2026-11-03** for the frozen application/test source in this change. Documentation was completed after verification; application source was not changed between the final tests and fresh APK builds.

The Android baseline is `v0.3.0-preview`, commit `1ecffc5b68962ac84e4bd965ff36005ede2aa641`. The feature comparison used the supplied **Cameo Windows 0.2.4** source. See the [parity record](CAMEO_ANDROID_PARITY.md) for the implementation mapping and service-specific limits.

## Final results

| Check | Result |
| --- | --- |
| Local JVM/Robolectric tests | **541 tests, 76 suites; 0 failures, 0 errors, 0 skipped** |
| Fresh regular debug build | **Succeeded** |
| Fresh side-by-side preview build | **Succeeded** |
| Android lint, debug variant | **0 errors, 64 warnings** |
| APK signature verification | **Both verified with APK Signature Scheme v2** |
| APK ZIP integrity | **Both passed** |
| Compiled manifest and packaged notices | **Both inspected; checks below passed** |
| Native preview renders | **10 renders: five themes at 360dp and 720dp** |

The lint result is not warning-free. Warnings include target/dependency update suggestions, vector/banner sizing, retained unused resources, and translation/UI checks. No claim is made that every warning predates this change.

The final tests were run with `:app:testDebugUnitTest`, with `CAMEO_RENDER_PREVIEWS=1` for the opt-in preview fixture. APK packaging and lint were then run from the same frozen source in an empty, isolated build-output directory using Java 17, Gradle 8.14.3, and Android SDK build tools:

```text
gradle -PpovBuildRoot=<empty-build-output-directory> :app:assembleDebug :app:assemblePreview :app:lintDebug
```

The project PowerShell build workflow and its tooling setup are described in the [README](../README.md#building-and-installing-an-update). The table above records local verification. Completed hosted checks are recorded separately below.

## Hosted CI and packaging follow-up

Both hosted Windows checks subsequently completed successfully for application source commit `27dccdba268745bee35b1837ca885e23fa7c3cb1`:

- [Branch build](https://github.com/MM-482/pov-android-tv/actions/runs/41580273993): built the source commit directly.
- [Pull-request build](https://github.com/MM-482/pov-android-tv/actions/runs/41580308019): built GitHub's merge ref against the unchanged 0.3.0 baseline.

Each ran `assembleDebug`, `testDebugUnitTest`, and `lintDebug`, then uploaded the regular debug APK and test/lint reports. The hosted logs confirm task success but do not print a total test count; **541 tests / 76 suites** remains the explicitly counted local result. The workflow does not build the side-by-side preview or import its retained development signing key. Use the separately delivered, verified **Cameo Preview** APK for the side-by-side test installation.

The additional offline packaging scan checked **497 APK entries and 52,720 DEX strings** with no findings in the delivered APK. Its source scan initially identified 43 network-address literals in eight JVM test files. Review confirmed that these are deliberately synthetic/mock endpoint values or loopback test servers, with no user-device network evidence.

The checker now classifies those reviewed fixtures by **exact source path and LF-normalized SHA-256**. Only Windows CRLF-to-LF conversion is tolerated; other content changes require review again. This classification applies only to the network-address rule in source archives. All credential, private-key, archive-path, and runtime-file checks remain active. The report records both the actual file hash and normalized review hash. The completed APK/source scan has no unresolved findings.

All **18 checker self-tests** pass, including changed-content rejection, wrong-path/APK rejection, CRLF handling, and continued detection of secret material in a reviewed fixture. This packaging follow-up changes the checker and documentation only; Android application source, Kotlin tests, and the delivered APK remain the versions verified above. The scan is a heuristic review and does not replace live service or physical-device acceptance.

## APK identities

| Field | Regular debug build | Side-by-side test build |
| --- | --- | --- |
| Launcher label | Cameo | Cameo Preview |
| Application ID | `dev.pov.tv` | `dev.pov.tv.cameopreview` |
| Version name | `0.4.0-preview` | `0.4.0-preview-side-by-side` |
| Version code | `7` | `7` |
| Exact file size | 242,844,980 bytes | 242,845,000 bytes |
| Minimum / target / compile API | 26 / 35 / 36 | 26 / 35 / 36 |

Regular APK SHA-256:

```text
e993df0d89d03b3b1ee20acba93906f6f443d079a23e9d4ab2d39b17513d048c
```

Side-by-side APK SHA-256 (`Cameo-Android-0.4.0-preview-side-by-side.apk`):

```text
054fb715d102ff0659f83b105332eb73a2d528ab84f8d81cefe905c01cf08e97
```

Both APKs use the same newly generated development signing certificate. Its SHA-256 fingerprint is:

```text
3f70a7eca28a5c0f0d9ab20b2b06a618ef6bbedb5c9b24d3f74bfa0ab174e866
```

The original installed/release app's signing key was not supplied. **Use Cameo Preview for initial testing alongside the original app.** It has separate, fresh app data and does not migrate accounts or history automatically. The regular build preserves the original application ID, but an in-place update still needs the original signing certificate/private key. Do not remove the existing installation to work around a signing mismatch. Private signing material is excluded from the repository and source archive.

## What the checks establish

- **Adaptive presentation:** API 28 and API 35 mobile tests cover narrow and wider touch layouts, all five appearances, independent icon selection, and configuration changes. The resize tests include 360dp to 720dp, a real resource-density change from 1x to 2x, and return to the cover layout. They check route, Back stack, selected season, scroll, and the shared playback session. These tests exercise Android resource/view behavior through Robolectric; they are not Fold hardware tests.
- **TV regression coverage:** the existing remote-navigation fixtures run explicitly in television mode and cover source/file choice, Back restoration, mini-player focus, playback launch guards, and account-bound Up Next.
- **Library and playback:** tests cover exact saved identities, playlists and ordering, protected media headers, download failure/cancellation behavior, player speed/sleep/caption options, and foreground/queue behavior.
- **Services:** tests use controlled responses to check independent account routing, service parsers, source preparation, bounded readiness checks, add-on catalogs/metadata/streams/subtitles, manual tracker actions, public metadata caching, and credential/redirect boundaries. The NZB/yEnc suite includes 11 checks. They do not prove that a particular live account, subscription, file, or server will work.
- **Native previews:** the fixture rendered Glass, Cinema, Slate, Pearl, and Aurora at 360dp and 720dp. The supplied gallery is labelled **SIMULATED NATIVE LAYOUT** and uses synthetic titles and artwork. Visual review checked text/control legibility and layout; content below the viewport is scrollable. These are not original concept images or device screenshots.

## Package inspection

Both compiled APK manifests contain five Cameo launcher aliases, with only Blue initially enabled. Every alias supports the Android launcher and TV Leanback launcher. The aliases target the correct Main activity in both application-ID variants. All ten icon/banner resources are present.

Main and Player are resizable, with no manifest landscape lock. Main's compiled configuration flags include density and font scale (`0x40001fb0`). TV landscape behavior remains a runtime choice. Both packages include 12 native library entries across `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`.

The complete Apache 2.0 license, runtime NOTICE, LibVLC license, font attribution, and both font OFLs match the source assets byte-for-byte. The OkHttp Public Suffix List notice is packaged. The repository GPL license, LibVLC license, and original font license texts remain unchanged from the baseline.

## Remaining acceptance work

This candidate has not been installed or exercised on a physical **Galaxy Z Fold 8** or **Android TV**. Physical fold/unfold behavior, OEM launcher refresh, codec/audio routes, remote controls, and device-specific document providers still need device acceptance testing.

No live paid-account, media-server, tracker-write, or online subtitle acceptance tests were performed. No credentials are included in the handoff. The [parity record](CAMEO_ANDROID_PARITY.md) documents service and platform limits, including foreground-process downloads, no HLS/DASH offline packaging or RAR/PAR2 repair, stored-file media-server playback, and manual anime-tracker actions.

The actual Mac source and the original November 2 concept images were unavailable. Complete Mac parity and pixel matching to those images are therefore unverified. Historical Sony captures and 0.3.0 audit results apply only to their recorded candidates.
