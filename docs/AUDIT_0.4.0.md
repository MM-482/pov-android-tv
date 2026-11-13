# Cameo Android 0.4.0-preview validation

Local candidate reviewed November 13, 2026 (America/New_York). This record distinguishes code/controlled tests, packaged artifacts, visual references and physical-device evidence.

## Exact application candidate

- Launcher/application label: **Cameo**.
- Android package `dev.pov.tv`, version `0.4.0-preview`, code **7**, minimum API **26**, target API **35**. Universal native libraries include arm64-v8a, armeabi-v7a, x86 and x86_64.
- Main APK SHA-256: `cadfdec6ffef08700a7e582eff8a0fb7d7e70a4cc61db0db26e4f72016e7e9eb`.
- Standalone signature verification passed with v2 signing. The development certificate matches the retained earlier Sony application APK. The candidate remains a debug-signed sideload preview; no private signing key is included in the source distribution.

## Combined build and tests

The final shared run of `testDebugUnitTest`, `lintDebug`, `assembleDebug` and `assembleDebugAndroidTest` succeeded. Optional native capture properties were forwarded only for this local test run.

| Check | Result |
|---|---|
| Automated behavioral tests | **688 tests / 88 suites; 0 failures, 0 errors, 0 skipped** |
| Android lint | **0 errors / 76 warnings** |
| Application APK | Assembled and independently signature checked |
| Device-audit APK | Assembled; exact device-run scopes recorded below |
| Public-artifact checker self-tests | **13 passed** |
| Source-path packaging policy | **15 checks passed**, including controlled redirect/private-file rejection; no upload performed |
| Native appearance captures | **5 mobile + 5 TV**, controlled public fixtures |

Warnings include localization/hardcoded text, drawing allocations, view construction, resources, API/dependency notices and platform configuration. The run does not claim a warning-free production release. No lint baseline was added to hide errors.

Coverage includes saved library metadata/order, exact custom title/episode provenance across providers, named-account switching and stale-result guards, service response contracts, redirect/header policy, download validation/cancellation/no-overwrite, corrupted-state preservation, caption selection/retention, player health and route ownership, native remote source ordering and form-draft preservation across rebuilds. API fixtures use synthetic accounts and controlled responses; they do not make private service requests or create real cloud transfers.

Physical phone review exposed first-touch focus consumption, mobile navigation collapse during mini playback, expired touch-reveal timing and an oversized theme picker. An actual owner-operated fold also exposed lost system-bar spacing and a caret moved by a TV row-focus fallback. The current candidate repairs these paths. Added regressions exercise actual MotionEvent gestures, exact native reveal/scrub deadlines, all five mobile navigation layouts, touch scroll restoration, proportionate picker geometry, window-data fallback and keyboard-mode input focus. The mobile window explicitly owns its bar padding, with current WindowManager bounds as a bounded fallback when view-root insets are zero. Padding changes are coalesced and committed after the current layout pass, followed by a requested traversal; tests assert actual header/content/navigation child positions as well as padding values. Geometry-only debug logging contains no input values, account data or title text.

## Appearance review

[Ten native previews with separate concepts](previews/0.4.0/index.html) use the real native renderer, unchanged public artwork from the supplied desktop source and synthetic progress. All ten images were inspected. Two concrete defects were fixed and recaptured: a clipped mobile play glyph and an extra TV poster sliver.

These are app-canvas renders produced by Robolectric native graphics at provisional logical viewports. They exclude phone status/gesture bars and are **not physical Z Fold 8 or Sony screenshots**. Mobile geometry covers compact, tall, tablet, narrow-cover and unfolded viewport cases. Runtime dimensions drive the interface; no Z Fold 8 hardware specification is inferred from those cases.

Pixel equivalence is **not certified**. Live artwork/titles differ from fictional reference art, Pearl's bundled serif differs in shape, and software-canvas image-outline behavior needs hardware review. Native controls and live content are separate from picker preview PNGs.

## Device/receiver boundary

Both devices were authorized by their owner. Updates use the existing package and signing certificate, without uninstalling or clearing the main app. Installed APK hashes are checked independently. Sony's encrypted account-file fingerprint remained unchanged across the updates; no account contents were exported. Phone test preferences are backed up and restored solely inside its app-private storage.

The current `cadfdec6…7e9eb` APK passed all four Sony API 26 public/owned-fixture playback/navigation audits below, after installed-byte verification. These are exact device results, not a claim that every file, account or device was tested.

| Sony checkpoint | Observed result |
|---|---|
| Source/file navigation | Two complete native D-pad round trips through 70 sources, five middle sources and 25 middle files; all focused rows visible; Back restored the exact source; history unchanged |
| Continue Watching | Removal, refresh and fresh-store persistence passed; recent history, hidden flags and pending tracking restored |
| Embedded VLC | Progressive multitrack sample decoded changing pictures in fullscreen, mini-player and returned fullscreen; two audio and eight subtitle tracks; local SRT selection, Menu/hold actions and Stop passed; preferences/history restored |
| Media3 adaptive fallback | Actual Media3 playback and a visible fallback notice while preserving VLC preference; 10 audio/four subtitle tracks, subtitle Off, PCM boost, fully hidden controls and same-session moving mini/fullscreen handoffs passed; preferences/history restored |
| Appearance | The current APK's live Cameo wordmark and exactly five current picker cards were independently observed and visually inspected; no legacy POV card/icon was offered. This final check made zero theme-choice changes, and the appearance-file hash stayed unchanged. At the earlier `4bfb7418…18858` checkpoint, Glass and Cinema selection survived actual restarts; the other three restart paths were not physically repeated. |

The phone requested by the owner is a Z Fold 8; its actual connected model identifies as SM-F971U/API 37. The current `cadfdec6…7e9eb` APK passed an actual owner-operated transition from unfolded 2448×1848 to cover 1248×1972. The same process retained all five unsaved synthetic form fields and Password focus. Actual header and navigation child rectangles remained inside the status/gesture boundaries in both positions. A coalesced posted padding commit fixes the case where correct padding values had not caused a child layout. The controlled pre-fold caret was at position three; post-fold focus was observed, but the exact post-fold selection was not read or insertion-tested.

On this current phone build, Aurora applied with one touch and persisted after a visibly confirmed application and restart. The public Media3 sample decoded pictures and subtitles; one mini-player touch returned to fullscreen, all five mobile navigation entries remained available, and one Stop touch ended the sample. The three audit preferences were restored and verified entirely on the phone, all five exact audit-backup directories were cleaned, account storage was untouched, and normal Cameo was confirmed in the foreground afterward. No normal account screen was captured.

Earlier `4bfb7418…18858` phone checks covered one-touch application of all five themes and embedded VLC's progressive sample/two-track audio menu; those full paths were not repeated on the current phone build. Exact physical dimensions were observed rather than inferred from the model name. Landscape keyboard testing showed Android's fullscreen extracted editor with a visible masked input, not a proven inline-field occlusion.

One current visual limitation remains: in a landscape miniature preview, embedded subtitle lines can overlap the title/footer. The observed fullscreen captions work; miniature caption/footer spacing remains listed for refinement. This is separate from the corrected mini-player first-touch and navigation behavior.

Local/USB/network document-provider variations, system PiP, private service accounts and longer playback remain separate verification boundaries. VLC Chromecast code and native capability provenance are present, with ownership/stale-selection fixtures; **no receiver was discovered, selected or confirmed to show video** during this validation.

Prior Sony 0.3.0 results in [the historical audit](AUDIT_0.3.0.md) do not establish physical success for this APK.

## Release privacy and scope

The package procedure uses an explicit validated file set. Runtime credentials, preferences/databases, private endpoints, captured personal media, machine configuration, signing keys, tooling and inspection folders are excluded. Public preview images show only controlled film fixtures. Final APK/source scan results and their exact ZIP hashes are retained alongside the local release bundle rather than embedding a source archive's own hash inside itself.

Static scans and controlled tests cannot establish all private accounts, subscription plans, device decoders, network providers, receivers or long-term playback. Remaining platform/source boundaries are listed in [the parity ledger](CAMEO_DESKTOP_ANDROID_PARITY.md) and [release notes](RELEASE_NOTES_0.4.0.md). No GitHub or Reddit publication is part of this candidate preparation.
