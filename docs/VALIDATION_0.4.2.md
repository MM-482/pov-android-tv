# Cameo Android 0.4.2-preview validation

Verified on **2026-11-03** for the stable bottom-navigation and playback-controls update. Earlier [0.4.1 validation](VALIDATION_0.4.1.md) remains historical.

## Completed local checks

| Check | Result |
| --- | --- |
| JVM/Robolectric suite | **570 tests in 77 suites; 0 failures, 0 errors, 0 skipped** |
| Android builds | `:app:assembleDebug` and `:app:assemblePreview` succeeded |
| Android lint | **0 errors, 64 warnings** |
| Native previews | **15 renders; all geometry checks passed** |
| Signing | Both APKs pass APK Signature Scheme v2 with one signer |
| Archive integrity | ZIP CRC and duplicate-entry checks pass; 497 entries in the delivered APK |
| Native engines | 12 shared libraries across arm64-v8a, armeabi-v7a, x86, and x86_64 |
| Notices | Runtime NOTICE, Apache 2.0, GPL, LibVLC license, font notices, and both font OFLs match the source bytes |
| Concept artwork | Newly supplied/generated concept boards are absent from the production APK |

The final build used Java 17, Gradle 8.14.3, compile SDK 36, and an isolated output directory. The optional native-render fixture was enabled in the full local suite. Ordinary hosted CI does not require the private reference folder or enable that fixture.

## Reported-bug regression coverage

Playback tests dispatch Android MotionEvents through the actual fullscreen Activity on API 28 and API 35. They use a local Media3 player double and the real LibVLC adapter with a fake native driver, including touches inside measured video/subtitle surfaces. They verify repeated auto-hide/reveal cycles, a full fresh interaction interval, first-tap pause/play and Options actions, long and cancelled timeline drags, ordinary button holds across playback-state callbacks, timeout restoration on lifecycle changes, and retention of the same player and position. TV remote reveal and row/timeline focus routing are checked separately.

The fixture retains Android layout traversal with a short deterministic Robolectric frame interval and bridges actual decor focus into Robolectric's separate Activity focus field. Those adaptations belong only to the test harness. No decoder, paid service account, or physical remote is used by these tests.

All five installed themes are checked at **360, 600, 720, 840, and 1024dp**. The tests verify the exact Home / Browse / Anime / Library / Search order, callbacks, 48dp touch targets, bottom anchoring, real inset-listener handling, scroll clearance, and selected destinations. Activity resize/density tests preserve routes, the Back stack, season selection, scroll position, and the shared player, with the mini-player above the menu.

The 15 native renders cover five phone canvases, five 720dp unfolded layouts, and five TV canvases. The ten phone/TV PNGs are byte-identical to the 0.4.1 gallery; the five Fold PNGs reflect the new bottom menu. These are simulated native layouts using fictional content and optional text-free reference-art samples, not physical-device captures.

## APK identities

| Field | Regular debug APK | Delivered Cameo Preview APK |
| --- | --- | --- |
| Application ID | `dev.pov.tv` | `dev.pov.tv.cameopreview` |
| Version | `0.4.2-preview` | `0.4.2-preview-side-by-side` |
| Version code | 9 | 9 |
| Minimum / target API | 26 / 35 | 26 / 35 |
| Bytes | 242,877,748 | 242,877,772 |

Delivered `Cameo-Android-0.4.2-preview-side-by-side.apk` SHA-256:

```text
065e6d19966ac82745d8cb1b730cd654ab7127ac1310302cdacbae2459c5ea25
```

Regular debug APK SHA-256:

```text
a52420eef2382bcc77a57decfa968f386beed434b0574627a5b599d882fcd48b
```

Both builds retain the Preview development certificate, SHA-256:

```text
3f70a7eca28a5c0f0d9ab20b2b06a618ef6bbedb5c9b24d3f74bfa0ab174e866
```

The delivered package can update earlier Cameo Preview versions signed with that certificate. A first Preview installation uses separate app data alongside the regular application. The original regular-release signing key was not supplied and is not replaced by this development certificate.

## Source and artifact scope

This update follows commit `4ab1a0dcad29e6eac53e10f4bbb3e6a7bed81b2e` on the existing Cameo draft PR branch. The source revision is the commit containing this record.

All **139 production/resource/Gradle input files** stayed unchanged during the final build and verification. The aggregate below hashes each sorted relative path, a NUL separator, its file SHA-256, and a newline. Tests, documentation, generated files, local SDK paths, and the signing key are excluded.

```text
06cb323282455a0e2c8ed848de7e3f7b48af4400bf6ffc47e7fa2a62d2a0b0eb
```

The exact delivered APK and a root-relative archive of the corresponding public source are checked by the existing offline artifact scanner before handoff. Its scope is archive contents and detectable strings; it does not perform image OCR or certify the absence of every possible secret. The eight reviewed synthetic network-test classifications remain tied to their exact paths and source hashes.

The installed themes remain Glass, Cinema, Slate, Pearl, and Aurora. Obsidian, Linen, Juniper, Cobalt, and Ember are separate visual concepts, not selectable APK themes.

## Remaining acceptance limits

No physical phone, foldable, or TV was connected for this update. The APK has not been installed or exercised on the user's device in this session. Live paid-provider/server/tracker/subtitle acceptance and direct comparison with the unavailable Mac source remain outstanding. Existing service and download boundaries are retained in the [feature mapping](CAMEO_ANDROID_PARITY.md).
