# GitHub source and release checklist

This checklist records technical publication requirements for the approved POV Standalone 0.2.2 preview. The owner has approved publication to [MM-482/pov-android-tv](https://github.com/MM-482/pov-android-tv) and the [v0.2.2-preview release](https://github.com/MM-482/pov-android-tv/releases/tag/v0.2.2-preview). These checks preserve the reviewed source, artifact identity, credits, privacy safeguards, and validation limits; they are not an additional owner-approval step.

The source checkout can contain a newer working preview than this published release. The 0.3.0 working changes have separate [setup instructions](INSTALL_0.3.0.md), [change notes](RELEASE_NOTES_0.3.0.md), and an [audit record](AUDIT_0.3.0.md). This document's 0.2.2 test/device results do not validate those newer changes or authorize publishing them.

## Working 0.3.0 source-list checkpoint

- [x] Build the current `bd45a3…69e0` APK, version 0.3.0-preview / code 6. The final unit run passed 418 tests across 54 suites with no failures/errors/skips; latest production-code lint reports 0 errors / 57 warnings. The later change was unit-test coverage only.
- [x] Verify the long-list fix on Sony Android 8: 70 rows/two complete round trips, ordered header entry/exit, five middle sources/25 middle files, fully visible focused cards, exact Back restoration, and unchanged history. The baseline jumped to index 59 of 70; the earlier seven-row fixture did not expose it. This is offline source-choice evidence, not authenticated transfer/playback.
- [x] Verify current bd45 v2 signing with one signer and the retained certificate, installed bytes matching the build, and cleanup against a fresh encrypted-account fingerprint captured immediately before this update. The temporary audit package was removed and normal POV launched; no account/history contents were exported. This does not compare account bytes with an older pre-0.3 session.
- [x] Retain exact evidence scopes: full Media3/VLC/Continue Watching checks belong to `7abc58…ee15`; the six reviewed theme captures belong to `582cec…6273`. Full player paths have not been rerun on bd45.
- [x] Record the completed historical archive scan: `7abc58…ee15` APK / `56aecc…05a0` source ZIP, 484 APK entries, 44,263 DEX strings, 221 allowlisted source entries, zero detector findings. It predates the source-list fix.
- [x] Package the bd45 APK with its refreshed matching source ZIP and external checksums, then scan those exact artifacts: 484 APK entries, 44,283 DEX strings, 222 allowlisted source entries, zero detector findings. This is a bounded static check without image OCR; selected images were reviewed separately. The historical archive scan remains separately scoped.

The [0.3.0 audit](AUDIT_0.3.0.md) records the full APK digests and remaining validation boundaries. This checkpoint does not publish a release. The native LibVLC source/contrib work below remains open.

## Working 0.3.0 LibVLC source checkpoint

- [x] Identify and inspect the exact official JNI and native-base repository archives. [LIBVLC.md](LIBVLC.md#inspected-pinned-source-archives) records their direct source locations, inspected byte counts, SHA-256 hashes, and the JNI recipe's 20-patch set.
- [ ] Establish and retain the complete external contrib source set, relevant patches/checksums/licenses, and actual configuration corresponding to all four native ABIs in the distributed AAR. The inspected VLC archive contains 117 contrib recipes and zero external contrib source archives; the two pinned repository archives alone do not complete this item.
- [ ] Verify a usable native rebuild and replacement/relinking route with the retained source inputs, and record the actual result for the binary being distributed.

Broad publication of the new VLC-enabled build remains subject to completing and checking this native source/contrib work. The archive inspection is source-provenance evidence; it does not establish complete source correspondence or a legal conclusion about redistribution.

## Published 0.2.2 checkpoint

The published checkpoint is **0.2.2-preview** (`versionCode 5`). The software-gain, player-handoff, and video-compatibility revision passed **227 unit tests**, with **0 failures, 0 errors, and 0 skipped**. The debug APK assembled successfully and lint reported **0 errors / 56 warnings**. That source includes a compact 124 dp integrated bottom player controller, every exposed audio/subtitle track with unknown/unsupported explanations, separate Automatic mode and actual selected state, and explicit source/multi-video file choices with stable IDs.

The combined Sony Android 8 public-sample playback audit passed on the 227-test build. The final offline source/file fixture also passed seven rows in both D-pad directions with every focused row fully visible, five middle-source openings, 25 middle-file selections, exact Back restoration, and unchanged local history. The fixture uses synthetic choices and does not prove authenticated transfer or playback. Record exact artifact identities, scans, and exercised device results in [AUDIT_0.2.2.md](AUDIT_0.2.2.md); [RELEASE_NOTES_0.2.2.md](RELEASE_NOTES_0.2.2.md) is the current change-note file. Do not generalize this TV/sample to every device, codec, HDR, HDMI route, or account workflow.

Two Sony Android 8 audit starts exposed a native crash during platform loudness-effect setup. The replacement removes that native-effect path and applies software gain to decoded PCM: Off/+3/+6/+9/+12 dB, bounded sample clipping, actual-processing status, and reset on playback end/stop. Compressed passthrough playback remains available but reports boost unavailable, hardware offload is disabled, and normal TV volume controls are unchanged. The final public-sample audit processed 5,120 PCM frames with +3 dB selected and then reset to Off. This verifies the exercised software path, not audible HDMI gain or every route. Repeat relevant checks after further source changes.

The latest player handoff retains the browsing root, catalog, scroll position, and mini view, uses local **Now playing** navigation, and disables Activity video-surface transition animations. The incoming `PlayerView` waits for attached/shown state, a visible window, and a valid `SurfaceHolder`; the outgoing target remains attached until readiness when its lifecycle permits. One pending observer is reused for repeated requests. Session/view generations reject stale callbacks, and detach/stop/replacement cancels pending observers. Ownership is committed before a single state notification refreshes controller behavior; already-attached same-view reentry does not notify recursively. Ten new unit cases cover this coordination. Outgoing `onStop` still detaches as required by its lifecycle. The Sony audit verified retained root/reused mini, visible motion with decoder progress, and exact paused-player/position continuity.

The mini-player focus router now treats the nearest horizontal row as the owner of Right navigation: middle cards keep native scrolling to their remaining row items; at the final card Right can enter the mini even when global focus search finds an offscreen card in another row. Two additional unit cases cover those regressions. The final Sony audit passed Right-edge focus, MENU, hold-Select, and Select returning to fullscreen.

| Exercised Sony playback check | Observed result |
| --- | --- |
| Embedded tracks | 10 audio and four subtitle tracks; all 11 audio-menu rows fully visible; manual/Automatic choices and subtitle Off exercised. |
| Compact controls | 124 dp geometry, complete hide behavior, and rendered loading fixture passed. |
| Decoded PCM | 5,120 processed frames at +3 dB, followed by Off reset. |
| Combined-run mini motion | 26 sampled video cells changed with 41 rendered decoder buffers. |
| Combined-run returned fullscreen motion | 156 sampled video cells changed with 171 rendered decoder buffers. |
| Earlier mini-only reused-view motion | 79 sampled video cells changed with 31 rendered decoder buffers. |
| Paused handoffs | Same player and 0 ms position change in both views. |
| Stop/history | Explicit Stop released the session; audit history remained unchanged. |

The full 227-test suite includes 18 video-compatibility guard cases. Loaded Media3 capability reports pause unsupported/excess-capability video with a fixed codec label, numeric resolution, and **Choose another source**; supported HEVC/4K and handled adaptive selections remain allowed by reported support. The blocked copy cannot restart through Play/Retry/resume, creates no new history/progress or Trakt scrobbles, and is released when leaving its fullscreen error. During one reported frozen-picture/continuing-audio case, Sony Android 8 decoder reports identified an 8K HEVC stream at 7680×4320 as exceeding capabilities. The post-update error UI has not been physically replayed with that private copy. Do not claim this explains or fixes every freeze or certifies every codec/HDMI route.

A separate Sony episode-navigation check passed eight sequential, fully visible rows, selection of S1E9, and exact selection restoration after Back; normal browsing was restored afterward. Exact packaged APK/source ZIP privacy checks, hashes, and artifact identities belong in the audit record and apply only to the files named there.

## Source contents

Use a deliberate source allowlist instead of archiving or staging the entire workspace. Both `scripts/package.ps1` and `scripts/prepare-github.ps1` use `Get-PovPublicSourceFiles` from `scripts/public-source-files.ps1`. It validates the same source set before exporting, rejects private/generated files and unsafe redirects, and checks resolved paths under the project root. Microsoft Cloud Files placeholders used by this OneDrive checkout are distinguished from symbolic links and junctions. A failed guard requires inspecting the offending source input; do not bypass it by copying the whole workspace.

Include Android source/resources under `app/src/`, Gradle build/settings files, `gradle.properties`, project build scripts, `.gitignore`, `.github/workflows/android.yml`, `README.md`, `LICENSE`, and this checklist. Include design assets only when useful for the public project and after reviewing them; they are not required to build the APK.

Preserve these runtime license/notice files in the source and APK:

- `app/src/main/assets/NOTICE.txt`
- `app/src/main/assets/GPL-3.0.txt`
- `app/src/main/assets/APACHE-2.0.txt`

Exclude these files from both Git staging and source ZIPs:

| Category | Exclude |
| --- | --- |
| Downloaded build tools | `.tooling/`, including archives, Android SDK, SDK licenses/state, JDK, and Gradle distribution |
| Build and IDE state | `.gradle/`, `.kotlin/`, all `build/` directories, `.idea/`, `*.iml`, and `local.properties` |
| Packaged outputs | `dist/`, APKs, generated source ZIPs/checksum lists; attach only reviewed artifacts separately |
| Signing material | `*.jks`, `*.keystore`, private keys, signing passwords, and secret signing-properties files |
| Account data | TorBox keys, Trakt access/refresh tokens, activation codes, user-provided Client IDs/callbacks, app preferences/database backups, and `.env`/credential files |
| Local evidence | `verification/`, runtime/logcat logs, account/activation screenshots, TV addresses/serials, and raw device dumps |
| Local inspection material | The entire `inspection/` directory, including assessment files, downloaded packages, extracted source, and reference bundles |
| Temporary files | Editor swap/backups, temporary build caches, environment dumps, and ad-hoc test captures |

Ignore rules are a convenience, not proof that an export is clean. Check staged files and the archive contents; ignored files can remain in existing ZIPs or Git history. Public documentation must not expose the TV's local address or account screen captures.

After packaging, run the offline checker against the exact APK and corresponding source ZIP:

```powershell
python scripts/verify-public-artifacts.py `
  --apk 'dist/POV-Android-TV-0.2.2-preview.apk' `
  --source 'dist/POV-Android-TV-0.2.2-source.zip' `
  --report 'verification/public-artifact-audit.json'
```

This scanner is a separate local review tool, not an Android app or build dependency. Its report must pass for those exact artifacts. It checks detectable strings and archive entries without printing matched secret values, but does not prove every secret absent, perform image OCR, verify APK/source equivalence, or replace manual review. Keep the report in local `verification/`, outside public source exports.

Before approving contents:

- Review `git status --short` and `git diff --cached --name-only`.
- Inspect the staged diff for credentials, activation codes, private device details, and unexpected binaries.
- Inspect the exact source ZIP file list against the same exclusions.
- Keep the GPL license and upstream attribution with corresponding source. Pair each distributed APK with its source revision and build instructions.
- If a secret entered Git history, rotate it and clean the affected history before publication; removing its current file alone is insufficient.

## Attribution

Use this credit consistently:

> Standalone app made by Mark Mateo. Based on the original POV Kodi add-on by kodifitzwell.

The inspected upstream `addon.xml` identifies `provider-name="kodifitzwell"`, version `6.10.06`, and GNU GPL version 3. Reference repository: [kodifitzwell/repo](https://github.com/kodifitzwell/repo), inspected revision `2ea2eb7f330988ade16d388d3832b8e4b60c6b24`. The original developer is not affiliated with the standalone Android app.

Do not add a Reddit username or another personal identity without a confirmed primary source. The updated launcher artwork is a generated concept based on POV's original visual identity; retain that explanation in the notice.

## Build and verification

Run the project scripts from a clean checkout on Windows:

```powershell
.\scripts\bootstrap.ps1
.\scripts\build.ps1
```

Record version name/code, source commit, SHA-256 of the exact APK, unit-test totals, lint error/warning counts, and the TV/device checks that actually passed. Check all five appearances, saved theme selection after restart, Anime browsing/episodes, remote focus/Back navigation, account dialogs, and visible creator credit.

For 0.2.2, retain the passing public-sample controller geometry/hiding/loading, track selection, PCM, visible-motion, mini navigation, paused-position, and Stop/history results. Preserve the final offline source/file fixture separately from future authenticated ready/pending-transfer and chosen-file playback checks. Repeat relevant tests whenever the source or packaged APK changes.

A previous version's passing tests/screenshots do not validate this version. Keep authenticated TorBox playback and Trakt synchronization marked unverified until exercised with an authorized account. Redact private data from any screenshots selected for publication.

Approved actual preview scope is `docs/previews/player-controls.png`, `player-audio.png`, and `player-hidden.png`, reviewed from the final combined public-sample run, plus `docs/previews/home-cinematic.png`, the actual Cinematic guest home with intentional gray account indicators. Keep the reviewed images intact. Actual Editorial, Neon, Cinema, and Dashboard home captures have not yet been taken; do not present generated concepts as those missing captures. The five original `design/01-cinematic.png` through `05-dashboard.png` images are generated reference concepts and must keep that label. Theme references do not establish the running app's state. Do not include connected account pages, private playback links, activation codes, or personal viewing history.

The prepared [Android workflow](../.github/workflows/android.yml) runs the same scripts on `windows-latest`, with read-only repository permissions and no publishing step. It uploads only the debug APK and unit-test/lint reports. Its first successful hosted run remains a separate validation item after an approved source upload. The workflow uses the current major versions documented by [actions/checkout](https://github.com/actions/checkout) and [actions/upload-artifact](https://github.com/actions/upload-artifact); review them when updating CI.

## Debug APK versus production release

The standard script-driven build produces `$env:POV_BUILD_OUTPUT/outputs/apk/debug/app-debug.apk`, signed with an Android debug key. The script sets `POV_BUILD_OUTPUT` to the temporary `pov-android-build-01a15a6b/app-build/` directory outside OneDrive; `scripts/package.ps1` resolves that location when preparing artifacts. Label the APK clearly as a preview/debug build for sideload testing.

Production releases need an owner-controlled release signing key and explicit signing configuration. Neither is provided by the default workflow. Running `assembleRelease` alone does not create an owner-signed production APK. Retain and back up the release key privately; keep its password/key material out of source, ZIPs, artifacts, logs, and screenshots.

An installed copy can update only with a compatible signing identity and permitted version code. A CI-created debug key normally differs from the local developer's debug key. Do not promise that a CI debug artifact can update the existing test installation. Switching keys can require uninstalling the old app, clearing local data and account connections.

## Approved preview publication

Owner approval has been received. For the approved preview publication:

1. Use the approved repository, [MM-482/pov-android-tv](https://github.com/MM-482/pov-android-tv), and upload only the reviewed source set with its license, credits, README, and documentation.
2. Pair the [v0.2.2-preview release](https://github.com/MM-482/pov-android-tv/releases/tag/v0.2.2-preview) with the exact reviewed APK, matching source ZIP, SHA-256 checksums, debug-signing label, source revision, and validation results.
3. Preserve known limitations and distinguish actual screenshots from generated concepts. In standalone release text, use an absolute repository/tag URL for documentation links rather than a source-relative path.
4. Run the prepared GitHub workflow after upload and record its actual result. Local build success does not establish hosted CI success.
5. Use verified repository/release/privacy links in the Reddit announcement and follow the selected community's rules. Keep credentials, private playback links, and account/device evidence out of public copy.

Approved publication does not turn this preview into a production-signed release or expand the validation coverage. Record upload, release, and hosted-workflow outcomes only after they have completed; prepared links alone are not completion evidence.

