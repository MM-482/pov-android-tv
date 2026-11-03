# Cameo Android 0.4.1-preview

The November 2 designs were supplied after the initial 0.4.0 port. This update rebuilds the five phone and TV appearances from those references and corrects their navigation and featured-title actions. The implementation uses native Android views with live content.

## Visual corrections

| Appearance | Phone | Android TV |
| --- | --- | --- |
| Glass | Immersive cool artwork, translucent category pills and rounded bottom navigation. | Full-bleed artwork and a translucent rounded left rail. |
| Cinema | Black and champagne, condensed display titles, underline tabs and fine shelf rules. | Horizontal top navigation, cinematic title treatment and ruled shelves. |
| Slate | Graphite and emerald, category pills, framed artwork above the feature copy. | Permanent left rail and a compact framed feature. |
| Pearl | Ivory and blue, serif display headings, framed art and a light bottom bar. | Horizontal navigation and a split text-left, art-right feature. |
| Aurora | Violet and cyan, immersive artwork, capsule category and bottom navigation. | Floating navigation capsule and a rounded panoramic feature. |

The five themes use the defining palette values in the supplied assets.json. Header marks are drawn natively and remain independent of the chosen launcher icon. Body text and controls keep readable sans-serif typography; Pearl uses serif display headings and Cinema uses condensed display titles.

## Navigation and behavior

- Narrow phones expose **Home, Browse, Anime, Library, Search**. Category tabs retain direct access to **For you, Movies, Shows**, with Settings in the header.
- **Explore** opens the featured catalog title's details. **+ Library** saves that title to the watchlist and is safe to repeat: it never toggles the title back out of the library.
- Continue Watching cards retain the exact saved playback identity and their resume/options actions. Their progress is shown over the artwork.
- Home shows two visible Continue Watching cards and two portrait discovery posters on a phone. TV shows three Continue Watching cards and five Discover cards, with later entries reachable by scrolling and remote focus.
- An unfolded 720dp window uses the navigation rail and a wider feature arrangement. Mobile dimensions use dp/sp and the current window; the TV canvas remains separate.
- Stored theme IDs, legacy Studio behavior, theme/icon independence, navigation restoration, and the shared fullscreen/mini-player session remain supported.

The local-library, account, add-on, download, and playback features introduced in [0.4.0](RELEASE_NOTES_0.4.0.md) remain part of this version. Their service-specific boundaries are recorded in the [feature mapping](CAMEO_ANDROID_PARITY.md).

## Build and installation

The version is **0.4.1-preview**, `versionCode 8`. The regular application ID remains `dev.pov.tv`. The separate **Cameo Preview** package is `dev.pov.tv.cameopreview`, version `0.4.1-preview-side-by-side`.

The delivered Preview candidate uses the retained Preview development signing key, allowing an update of the earlier Cameo Preview package. It cannot replace the original POV/Cameo installation signed with a different key. A first Preview installation starts with separate app data. No uninstall or data clearing is required for the separate Preview package.

## Verification boundaries

See [0.4.1 validation](VALIDATION_0.4.1.md) for the completed automated checks, native render scope, and exact APK identities. Preview images are simulated renders of the real native view tree. The optional reference artwork and fictional media data exist only in the test fixture; the original concept PNGs are not added to the production APK.

This candidate has not been exercised on a physical Galaxy Z Fold 8 or TV, or against live paid-provider accounts. The actual Mac source was not supplied. These limitations remain separate from the reference-based visual changes.
