# Cameo Android 0.4.1-preview validation

Verified on **2026-11-03** for the November 2 reference-based phone and TV correction. This is a new candidate; [0.4.0 validation](VALIDATION_0.4.0.md) remains historical.

## Completed local checks

| Check | Result |
| --- | --- |
| JVM/Robolectric suite | **553 tests in 76 suites; 0 failures, 0 errors, 0 skipped** |
| Android builds | `:app:assembleDebug` and `:app:assemblePreview` succeeded |
| Android lint | **0 errors, 64 warnings** |
| Native previews | **15 renders; all geometry checks passed** |
| Signing | Both APKs pass APK Signature Scheme v2 with one signer |
| Archive integrity | Both APKs pass ZIP CRC and duplicate-entry checks; 497 entries each |
| Native engines | 12 shared libraries across arm64-v8a, armeabi-v7a, x86, and x86_64 |
| Notices | Runtime NOTICE, Apache 2.0, GPL, LibVLC license, font notices, and both font OFLs match the source bytes |
| Concept artwork | The newly supplied concept PNGs are absent from the production APK |

The final verification run used Java 17, Gradle 8.14.3, compile SDK 36, and an isolated build-output directory. The optional native-render fixture was enabled during the full suite, so the reported local result has no skipped preview test. Ordinary CI does not enable that fixture or require the private reference folder.

Lint retains 64 warnings, including localization, pinned dependency/update advisories, and existing custom-view/layout warnings. The new brand renderer caches its paths, gradients and font metrics. All 15 rendered PNGs were byte-identical before and after that allocation-only cleanup.

## APK identities

| Field | Regular debug APK | Delivered Cameo Preview APK |
| --- | --- | --- |
| Application ID | `dev.pov.tv` | `dev.pov.tv.cameopreview` |
| Launcher label | Cameo | Cameo Preview |
| Version | `0.4.1-preview` | `0.4.1-preview-side-by-side` |
| Version code | 8 | 8 |
| Minimum / target API | 26 / 35 | 26 / 35 |
| Bytes | 243,446,036 | 243,446,074 |

Regular debug APK SHA-256:

```text
bb4249b80bb959671c407635f150d7746b22c1fdf8fb404c807cbf8123df96c5
```

Delivered `Cameo-Android-0.4.1-preview-side-by-side.apk` SHA-256:

```text
596f64eda6e44ff06328640f0d313e51751928bd791601dc1845be364d7cdbd4
```

Both builds use the retained Preview development certificate, SHA-256:

```text
3f70a7eca28a5c0f0d9ab20b2b06a618ef6bbedb5c9b24d3f74bfa0ab174e866
```

The delivered package can update the earlier **Cameo Preview** package signed with that certificate. Its first installation creates separate app data alongside the original `dev.pov.tv` installation. The original release signing key was not supplied; this debug certificate cannot replace a differently signed original app in place. Do not clear or uninstall the original app to work around that mismatch. Private signing material is excluded from source control.

## Native visual and interaction verification

All ten supplied November 2 concepts were inspected. The native fixture renders each of Glass, Cinema, Slate, Pearl and Aurora in three form factors:

| Form factor | App viewport | Measurement |
| --- | --- | --- |
| Glass phone | 852 × 1846 px | Density 2; 426 × 923 dp |
| Other four phones | 830 × 1896 px | Density 2; 415 × 948 dp |
| Unfolded window | 720 × 800 px | Density 1; 720 × 800 dp |
| Android TV | 1672 × 941 px | TV renderer's 960 × 540 logical canvas |

Phone and unfolded fixtures reserve representative system-bar insets. The clock, status icons and gesture bar are not fabricated. Each PNG adds an explanatory caption outside the measured viewport. `native-preview-metadata.json` records dimensions, density, reference image hashes, native bounds, and each geometry result.

The artwork helper samples only bounded, text-free artwork regions inside test Drawables. Branding, labels, buttons, progress bars, navigation and card layouts are production native views. Fictional titles and reference artwork are test data; live application content comes from its catalogs, services and saved entries.

The automated checks and visual review cover:

- Complete CAMEO wordmarks, native font weights, theme-specific hero compositions and rounded artwork.
- Five pinned phone destinations: Home, Browse, Anime, Library and Search; Movies and Shows category selection remains accessible.
- Catalog-first Explore and + Library callbacks with the correct title; repeated positive library saves never remove or duplicate a title.
- Exact saved resume and long-press identities, invalid/finished progress filtering, and saved-only fallback behavior.
- Two visible phone Continue Watching cards and two Discover columns; three TV Continue Watching cards and five Discover cards.
- DPAD access to offscreen shelf entries, long title bounds, 800 × 450 TV geometry, and legacy Studio behavior.
- Wider unfolded artwork/copy, route-specific insets, unique shelf focus tags, preserved mini-player scroll clearance, and readable Glass navigation while scrolling.
- Existing configuration-change tests retain route, Back stack, selected season, scroll and shared playback through width and density changes on API 28 and API 35.

These are **simulated native Android renders**, not physical-device captures or pixel-match certification. Explicit production artwork clipping is captured; GPU composition and elevation shadows can differ from software Canvas output.

## Source and packaging record

The source follows application commit `27dccdba268745bee35b1837ca885e23fa7c3cb1` and the artifact-checker follow-up `fd7d5ef934c693bd79e9a0b28153c8468386b831`. The current source is the commit containing this record, on the existing Cameo draft PR branch.

The final build used 139 production source/resource and Gradle input files. Their contents remained unchanged during build and verification. The aggregate SHA-256 below hashes each sorted relative path, a NUL separator, its file SHA-256, and a newline; tests, docs, generated files, local SDK paths and the signing key are excluded:

```text
727d2dd52b5872e887c7d97390998d4c7cb3f818299381665fee152474d56249
```

The offline artifact checker is run against the exact delivered APK and a root-relative archive of the corresponding public source before handoff. Its report covers archive contents and detectable strings, with no network requests. The eight previously reviewed synthetic network tests retain their exact source hashes; their narrow network-address classification remains in place while secret checks continue to run. This bounded check does not certify every possible secret absent or perform image OCR.

## Remaining verification limits

No physical Galaxy Z Fold 8, other Android phone, or Android TV was connected to this build session. The new APK was not installed or exercised on a physical device. Live paid-provider, server, tracker-write, subtitle-account and Easynews acceptance remain untested with user accounts.

The actual Mac source is still unavailable. The original November 2 concepts are now available and were used for this correction. Runtime catalog art, system bars and content length will differ from the illustrative references. Historical Sony device audits apply only to their recorded 0.3.0 builds.

The app's existing download, service and playback boundaries are documented in [the feature mapping](CAMEO_ANDROID_PARITY.md). This validation does not extend them or claim that every device, source, codec or service route has been certified.
