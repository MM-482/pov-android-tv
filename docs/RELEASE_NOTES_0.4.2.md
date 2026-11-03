# Cameo Android 0.4.2-preview

This update fixes two reported phone behaviors: navigation moving to the side when a foldable opens, and playback controls disappearing again immediately after a tap.

## Stable bottom navigation

Home, Browse, Anime, Library, and Search remain at the bottom of every touch window. Unfolding, rotating, or widening the window adjusts the content without relocating those destinations. Movies and Shows remain available through Browse and the category tabs; Settings stays in the header.

The mobile rail and its 88dp width reservation have been removed. Wider heroes and shelf columns use the reclaimed space. Safe system insets and scroll clearance remain in place, and an active mini-player stays above the menu. Android TV retains its existing theme-specific navigation.

## Playback controls return on tap

The shared player controller had an app-managed hide deadline in addition to Media3's native controller timer. After auto-hide, Media3 could reveal the controller from a video tap while the app immediately applied its expired deadline. The visible transition now starts a fresh interval.

Fullscreen touch dispatch also records the start and end of a gesture without consuming it or toggling the controller a second time. Both hide timers are suspended during a gesture, and completing or cancelling it refreshes the normal interaction interval. Native timeline scrubbing and play/pause actions retain ownership of their input.

Touch-mode focus is limited to the TV path so a phone button's first tap activates its action. Both Media3 and embedded LibVLC use the corrected controller. TV remote reveal and directional focus remain covered by regression tests.

## Version and appearance

The version is **0.4.2-preview**, `versionCode 9`. The regular package remains `dev.pov.tv`. The delivered **Cameo Preview** package is `dev.pov.tv.cameopreview`, version `0.4.2-preview-side-by-side`, signed with the retained Preview development key for an in-place update of earlier Cameo Preview builds.

The installed appearances remain **Glass, Cinema, Slate, Pearl, and Aurora**. The separately generated Obsidian, Linen, Juniper, Cobalt, and Ember boards are design explorations; they are not included as selectable themes in this APK.

## Verification scope

See [0.4.2 validation](VALIDATION_0.4.2.md) for completed checks and exact APK identities. Regression tests dispatch real Android touch events through the fullscreen Activity with local player doubles, including a LibVLC adapter with a fake driver and subtitle-surface overlay. They verify repeated hide/reveal cycles, first-tap actions, long and cancelled scrubs, session continuity, and TV remote behavior.

Layout checks cover every installed theme at 360, 600, 720, 840, and 1024dp, plus configuration and density changes. The native preview fixture checks both phone and unfolded bottom navigation. These are automated tests and simulated native renders; this candidate has not been exercised on a connected physical phone or TV or with live paid-provider accounts.
