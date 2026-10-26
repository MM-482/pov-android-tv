# Install and set up POV Standalone

For the **0.3.0 working update**, use [the new installation and service guide](INSTALL_0.3.0.md) and its [audit record](AUDIT_0.3.0.md). The instructions below describe the published 0.2.2 preview.

POV Standalone is a native **Android TV 8.0 or later** app. Kodi is not required. This guide covers `0.2.2-preview` (`versionCode 5`), a sideload preview rather than a Google Play release. The current revision passed all 227 unit tests with 0 failures, errors, or skipped tests; the debug APK assembled successfully and lint reported 0 errors/56 warnings. The combined Sony Android 8 public-sample playback audit passed its controller, track-choice, decoded-PCM, visible-motion, and fullscreen/mini handoff checks. The final offline source/file fixture passed seven fully visible rows in both D-pad directions, five middle-source openings, 25 middle-file selections, exact Back restoration, and unchanged local history. See the [audit record](AUDIT_0.2.2.md) for exact packaged-artifact checks and exercised device paths, and the [change notes](RELEASE_NOTES_0.2.2.md) for implementation details.

A separate Sony episode-navigation check passed eight sequential, fully visible rows, selected S1E9, and restored the same selection after Back; normal browsing was restored afterward. These checks cover their specific public-sample and navigation paths. They do not certify every TV, codec, HDMI route, or authenticated service workflow.

Publication of this preview has been approved for [MM-482/pov-android-tv](https://github.com/MM-482/pov-android-tv) and the [v0.2.2-preview release](https://github.com/MM-482/pov-android-tv/releases/tag/v0.2.2-preview). It remains a debug-signed sideload preview with the validation limits described above.

## Before installing

You need an Android TV/Google TV device with a working remote, network access, and enough storage for the app. Movie/show and Anime browsing can be explored without connecting accounts.

Playback through TorBox requires **your own TorBox account** with the permissions and availability needed for the file. Trakt features are optional and require **your own Trakt account plus a registered Trakt application Client ID** in this preview. No TorBox account, Trakt account, application Client ID, password, API key, or access token is bundled.

Use the APK from the [0.2.2 preview release](https://github.com/MM-482/pov-android-tv/releases/tag/v0.2.2-preview), or build it using [the README instructions](../README.md#build). Use the APK and checksum from the same release. A source ZIP is for developers; it is not the TV installer.

On Windows, compare the APK's SHA-256 with its published checksum:

```powershell
Get-FileHash -LiteralPath '.\POV-Android-TV-0.2.2-preview.apk' -Algorithm SHA256
```

## Install with a TV file browser

1. Copy the APK to a USB drive or transfer it to the TV using a method you already trust.
2. Open the APK in a file browser on the TV.
3. If Android prompts for installation permission, open the permission screen and allow **Install unknown apps** for that file browser. The exact settings label varies by TV manufacturer.
4. Choose **Install**. If POV is already installed, use the update path and keep the existing app installed.
5. Open **POV** from the TV's apps list. Use the D-pad to move focus and **OK/Select** to open an item. **Back** returns to the previous screen.
6. You may turn the file browser's installation permission off again after installing.

If the TV reports a signing conflict, stop and read **Updating without losing data** below before changing anything.

## Install with ADB from Windows

ADB is optional. Android's [official ADB documentation](https://developer.android.com/tools/adb) covers device authorization, USB connections, wireless pairing, and troubleshooting.

Enable the TV's developer options and its supported debugging option. For USB debugging, accept this computer's authorization prompt on the TV. For wireless debugging, follow the TV's pairing screen. Keep the PC and TV on the same trusted network.

After running this project's bootstrap script, ADB is available under `.tooling/android-sdk/platform-tools/`. From the project directory:

```powershell
$adb = (Resolve-Path '.\.tooling\android-sdk\platform-tools\adb.exe').Path
$apk = (Resolve-Path '.\dist\POV-Android-TV-0.2.2-preview.apk').Path
& $adb devices
```

Adjust `$apk` if your downloaded APK is elsewhere. The placeholders below must be replaced with values shown on **your own TV**; they are not a saved device address.

On a TV that provides modern wireless pairing:

```powershell
& $adb pair 'TV_IP:PAIRING_PORT'
& $adb connect 'TV_IP:ADB_PORT'
& $adb devices
& $adb -s 'TV_IP:ADB_PORT' install -r $apk
```

Enter the TV's pairing code only when ADB requests it. The pairing port and connection port can differ.

On an older TV that explicitly provides network debugging on port 5555:

```powershell
& $adb connect 'TV_IP:5555'
& $adb devices
& $adb -s 'TV_IP:5555' install -r $apk
```

Network debugging is not available by default on every older TV; some devices require an initial USB setup. Use the manufacturer's supported method or the file-browser installation above. For a USB connection, replace the target after `-s` with the device identifier reported by `adb devices`. Do not run install commands against an unverified target when multiple devices are connected.

## Updating without losing data

**Keep the installed app and install the update over it.** The file-browser update flow and `adb install -r` preserve app data when Android accepts a same-package, compatible-signature update with an allowed version code. This preserves the app's device preferences, local playback state, and account connections, subject to any migration noted by the release.

Do not uninstall the current app or choose **Clear storage/data** as a routine update step. Those actions remove local settings and account connections. Disconnecting an account also removes its local connection intentionally.

The package name is `dev.pov.tv`. Debug APKs built on different computers or fresh CI runners can use different debug signing keys. A release APK signed with a new production key can also conflict with a debug installation. If you see `INSTALL_FAILED_UPDATE_INCOMPATIBLE` or a signature warning, obtain a build signed with the existing key or ask the project owner for the supported migration path. Uninstalling is a last resort that loses local data, not the recommended update method.

## Connect TorBox

1. Open **Settings → Connect TorBox**.
2. Leave the TV's authorization dialog open. On your phone/computer, visit the address shown by that dialog, sign in to your own TorBox account, and approve the current code.
3. Wait for the TV to show the connection result.
4. Open **My TorBox** to browse your torrent, Usenet, or web-download library, then choose the appropriate file.

Alternatively, use **Settings → Use TorBox API key** and enter your own API key on the TV. Keep it private; do not put it in a GitHub issue, screenshot, source file, or shared log.

A connected badge confirms a saved account connection. It does not prove that a particular source is ready, included in your account, or compatible with the TV. A transfer still being prepared can be retried from your TorBox library later.

Source and cloud-file lists use explicit sequential selection so middle rows can be reached with Up/Down and restored on Back. When a source has multiple eligible videos, **Choose a video version** lists them individually; select the correct edition before playback. An exact filename can receive initial focus without suppressing other editions. A sole eligible file can proceed directly. Samples and explicit episode mismatches are excluded. If an episode pack cannot be matched safely, open TorBox Cloud and identify the correct file manually. For movie collections, choose the file belonging to the requested film rather than another movie in the pack.

## Connect Trakt

**Current preview limitation:** each user must register their own Trakt application and enter its **Client ID** before the TV can request an activation code. A Trakt username/password or activation code is not an application Client ID. The preview does not ship a shared project registration or a client secret.

1. Sign in to Trakt on your own phone/computer. Open [Trakt's developer site](https://developer.trakt.tv/apps) and register an application. The official [authentication guide](https://developer.trakt.tv/docs/authentication-oauth) explains application registration and the device-code flow.
2. Keep the application's Client ID and the exact callback/redirect URI you registered. Do not paste a client secret into POV.
3. On the TV, open **Settings → Trakt registered callback URI** and enter that registered value. The preview's default is `urn:ietf:wg:oauth:2.0:oob`; use it only when it matches your registration.
4. Open **Settings → Trakt app Client ID** and enter your application's Client ID. Saving a valid value requests a device code immediately. You can also choose **Connect Trakt** afterward.
5. On your phone/computer, open the activation address shown on the TV, sign in to your own account, and approve the code before it expires.
6. Return to the TV and wait for the connection result. Open **My Trakt** to use lists and progress features.

The callback URI is used during token refresh; it is not needed to generate the initial device code. If registration is unavailable to you or Trakt rejects the Client ID, TorBox and public browsing remain usable while Trakt features stay disconnected.

Tokens are stored on the device using the app's encrypted Android Keystore-backed storage. They are not added to the source or copied between installations. The manifest disables backup, and explicit legacy/cloud/device-transfer rules exclude all nine supported storage domains from standard Android backup. See [Privacy](PRIVACY.md) for what remains on the device, third-party service requests, deletion, and platform limitations. Changing the Trakt Client ID or disconnecting clears pending Trakt updates to avoid sending a different account's playback history.

For raw TorBox files, use **Identify movie for Trakt** or **Identify episode for Trakt** when you want tracking. The app does not silently assign watched history from a guessed filename.

## Choose a theme and browse Anime

Open **Settings → Appearance → Themes** and choose Cinematic, Editorial, Neon, Cinema, or Dashboard. The choice is saved on the device; it does not reconnect accounts or reset playback history.

Open **Anime** from navigation to browse or search anime. The default is series; movies and specials/OVAs have separate filters. Discovery uses Kitsu, separate from the general Movies and Shows catalogs.

Select a season or part to see only that entry's verified episodes. The displayed episode number belongs to the selected anime entry. A supplied overall episode number may also be shown, while source searches and Trakt retain the original IMDb season/episode identity. For example, Attack on Titan season 3 part 2 begins with its local episode 1, overall episode 50, mapped to the show's S3E13. The app does not substitute the full parent show's episode list.

Catalog-linked related titles are grouped as main-story seasons/parts, movies, special extras/OVAs, and spinoff shows. Attack on Titan's two Final Chapters are main-story long presentations mapped to S4E29 and S4E30; compilation films remain movies. An incomplete or conflicting mapping produces a visible message. Use manual matching when necessary and choose the actual movie/show and episode yourself.

General TV details show one selected season at a time. The initial selection prefers a regular season; open Specials explicitly when you want season-zero extras.

## Browse while a video plays

Press **Back** in fullscreen playback to return to POV's browsing screens. The same playback session continues in the mini view, retaining the current catalog and scroll position. Select that mini view or **Now playing** to return directly to fullscreen at the current position without reloading the catalog or starting another stream. Video-surface transition animations are disabled during the handoff. The incoming view waits until it is attached, visible, and has a valid video surface; old attachment callbacks are canceled when the view or session changes. Ten regression cases cover that coordination. The Sony public-sample audit verified visible motion and decoder progress in mini/fullscreen, retained the browsing root, and reused the existing mini view. Paused handoffs kept the same player with 0 ms position change in both views.

Use **Right** to move through the remaining cards in the current horizontal row, including cards that scroll into view. At the last card, **Right** can move into the mini-player. **Left** or **Up** from the mini-player returns to the prior content action when it is still available. Two additional regression cases cover middle-card scrolling and the last-card mini-player boundary.

The browsing screen reserves extra content space while the mini view is present and reveals a focused poster/action above that overlay when needed. The Sony audit passed Right-edge access to the mini, MENU and hold-Select options, and Select returning to fullscreen. Explicit Stop released the session, and the public-sample audit left local history unchanged.

While browsing with an active session, **MENU** offers **Return to video** and **Stop playback**. If the TV reserves MENU for system settings, **hold Select on the mini view** to open those same choices. Selecting the mini view normally, or choosing **Now playing** in navigation, returns directly to fullscreen. In the player, MENU opens the full playback-options menu, including **Browse while playing** and **Stop playback**. Stop saves local progress and releases the player. At POV's root screen, Back asks whether to **Stop and leave** when playback is active.

Pressing the TV's Home button, switching to another app, or otherwise moving every POV screen out of the foreground pauses playback. Returning resumes it only if it was playing before leaving. POV does not provide a background playback service or system picture-in-picture mode in this preview.

## Audio, subtitles, and volume boost

If playback pauses with **Choose a compatible video**, read the displayed codec and resolution and choose **Choose another source**. A 1080p H.264 (AVC) version is a useful alternative; other formats can work when supported by the TV. The guard uses the player's reported video support rather than banning HEVC or 4K. It waits through initial track loading, then pauses unsupported/excess-capability video or ready media with no selected video. Choosing another source releases the blocked session, and blocked media does not create new history/progress or Trakt scrobbles.

The unit suite includes 18 video-compatibility cases. A Sony Android 8 decoder report identified one 8K HEVC copy at 7680×4320 as exceeding its capabilities during a reported frozen-picture/continuing-audio problem. The post-update error UI has not been physically replayed with that private copy. This finding does not explain every freeze; the separate supported public sample passed its visible-motion handoff checks.

Open **Audio**, **Subtitles**, or **Volume boost** from the bottom playback controls or MENU. These use a shared option-sheet style with a visible selection and Done action. Done or Back closes the sheet before leaving fullscreen playback. Final D-pad behavior will be recorded after device verification.

Audio and Subtitles list every track exposed by the current file, including unknown-language and unsupported tracks. Unsupported entries carry an explanation; being listed does not mean the TV can decode them. **Automatic** is a separate selection mode from the indication of which track is actually playing. A subtitle-off action is available. External subtitle downloads are not included, and the app cannot create language tracks absent from the file.

Volume boost offers **Off**, **+3**, **+6**, **+9**, and **+12 dB** using software gain on decoded PCM audio. Off restores the original PCM samples; ending or stopping playback resets the choice. The status waits for actual audio samples to be processed with the selected gain, so selecting a new value while paused can show a waiting message until playback resumes. Sample values are limited to the signed 16-bit bounds to prevent wraparound.

Compressed passthrough playback remains supported, but its boost status is **Unavailable** because the encoded audio bypasses PCM processing. Hardware audio offload is disabled. The normal TV volume controls are unchanged. This software implementation removes the older native loudness-effect setup that crashed during two Sony Android 8 audits. Its replacement processed 5,120 PCM frames with +3 dB selected and reset to Off on the exercised decoded-audio route. That verifies software processing rather than audible HDMI gain or universal route support.

The title, loading status, timeline, transport buttons, and option actions share a compact 124 dp bottom controller and hide together after inactivity during playback. Merely focusing Audio, Subtitles, or Boost does not pin the controller open. An opened option sheet remains available until Done or Back, and paused playback can keep its controls visible. The Sony audit verified controller geometry, complete hiding, and the rendered loading fixture. The sample exposed 10 audio tracks and four subtitle tracks; all 11 audio-menu rows were fully visible, and manual/Automatic choices plus subtitle Off were exercised.

## Troubleshooting

| Symptom | What to check |
| --- | --- |
| APK will not install | Confirm Android TV 8.0+, storage space, the complete APK/checksum, and installation permission for the file browser. Check signature compatibility before considering any uninstall. |
| ADB shows `unauthorized` | Accept the computer's debugging prompt on the TV. Reconnect and run `adb devices` again. |
| ADB cannot connect | Verify the TV address/port on its current screen, that both devices share a reachable network, and that debugging is enabled. Some networks isolate Wi-Fi clients. |
| Trakt says a Client ID is missing/rejected | Enter the Client ID from your own registered Trakt application, not a username, password, client secret, or the activation code. |
| Activation code expired | Choose Retry or reconnect, then approve the new code displayed by the TV. Old codes cannot be reused. |
| TorBox connects but there are no files | Check the appropriate torrent/Usenet/web-download library and your account's actual contents. |
| No sources or a transfer is pending | Check the configured provider/network and retry later. A source listing does not guarantee TorBox readiness. Multi-file packs can require manual file choice. |
| A source contains several movie editions or episode versions | Open **Choose a video version** and use Up/Down through all eligible rows. An exact filename suggests initial focus; it does not automatically choose among multiple versions. Select the file belonging to the requested title. |
| Anime title cannot map or an episode is absent | Only explicit per-video mappings that agree with canonical show episodes are offered. Missing/conflicting records are reported; use manual matching or search the actual title in Shows/Movies. Do not assume a cour's local episode 1 means IMDb S1E1. |
| Anime film or OVA appears in the wrong place | Check the selected filter. Series, movies, and specials/OVAs are separate. Related main-story long specials can appear with story parts while retaining their correct show identity. Report the title and category if the provider's metadata is inconsistent. |
| Back leaves the video screen | Back is intended to return to browsing with a mini view. Select the mini view or MENU → Return to video. Use Stop playback to end the session. |
| Video pauses after pressing Home | Playback pauses when POV leaves the foreground; background playback is not included. Returning resumes only a session that was previously playing. |
| Volume boost has no audible effect | Resume playback if the status is waiting for processed audio. Compressed passthrough shows boost as unavailable; choose a decodable audio track or use the normal TV volume controls. The app disables hardware audio offload. |
| Picture freezes or only audio continues | If the app displays a video-compatibility message, choose a copy within the TV's supported codec/profile/resolution, such as 1080p H.264. One Sony case involved 8K HEVC at 7680×4320 exceeding decoder capabilities. A freeze can have other causes, so include a redacted format/error description when reporting one. |
| Video/audio/subtitles do not behave as expected | Try another compatible source or track. Device-specific codec, HDR, audio-passthrough, subtitle, and lifecycle behavior still needs wider testing. |
| Trakt progress does not update immediately | Confirm the media was explicitly identified and the account is connected. Pending updates retry at the next app launch; this preview does not run a permanent background service. |

When reporting a problem, provide the app version, Android version, TV model, steps, and a redacted error message. Do not include passwords, API keys, Client IDs, activation codes, tokens, private device addresses, account screenshots, or raw logs containing private data.

