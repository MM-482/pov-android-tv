# Six approved POV home screens — native implementation specifications

The six original PNGs in this directory are the visual references. This specification was made by viewing every original at full resolution and reading pixel colors; the images were not edited or cropped. Their geometry and presentation differ substantially, so they require six compositions rather than one layout with six palettes.

## Coordinate and typography contract

- Every reference is **1672 × 941 px**. A rectangle below is **`(left, top, width, height)` in reference pixels**. Visual edge measurements have an approximately ±3 px tolerance; soft glows extend beyond their measured hard bounds.
- Normalize a rectangle as `(left/1672, top/941, width/1672, height/941)`. For the app's 960 × 540 design coordinate system, use `x × 0.574163` and `y × 0.573858`, including width and height respectively. Scale from the actual available full-screen viewport once; avoid adding another content inset around these coordinates.
- Example: Glass's `(28,29,209,878)` rail becomes normalized `(0.01675,0.03082,0.12500,0.93305)`, or approximately `(16.1,16.6,120.0,503.9)` on the 960 × 540 design canvas.
- Font sizes below are **reference-pixel nominal sizes**, not Android `sp`. Multiply by approximately 0.574 for the nominal 960 × 540 native size. Text includes the usual font metric variation; match cap height and baseline against the reference after rendering.
- Raster references do not identify licensed font files. Android 8 system-family matches are specified below: Roboto through `sans-serif`, `sans-serif-light`, and `sans-serif-medium`; Premiere uses a Roman `serif` face for feature/card titles. These are implementation matches, not claims about the generator's font.
- Preserve each theme's own title, metadata, description, and button slots. Longer real titles may shrink within their slot, then wrap at the smaller size or ellipsize; they must not push the buttons or the next section down.

## 01 — Glass

Reference: `01-glass-apple-tv.png`.

**Composition:** Edge-to-edge cinematic artwork, a visibly inset floating glass rail, an airy left feature description, and a four-card landscape row. Card titles and progress sit **below** the pictures. The floating rail continues almost to the bottom; this distinguishes it from Essential and Premiere.

| Element | Reference rectangle / placement |
|---|---|
| Background / hero artwork | Full viewport; important art is exposed from x237 through the right edge, with the readable hero extending to about y600 |
| Floating rail | `(28,29,209,878)`, corner radius approximately 32; no hard full-height division at x237 |
| POV logo | `(79,80,100,57)` |
| Active Home row | `(40,177,185,66)`, radius18; icon near `(68,196,28,28)`, label starts x122 |
| Remaining navigation | Icons start x69, labels x122; row centers y292,375,458,542,625,713; use the reference's generous vertical rhythm |
| Hero title slot | `(304,177,690,83)` |
| Metadata | `(304,273,470,27)` |
| Description | `(304,325,560,53)`, two lines |
| Resume button | `(301,409,247,70)`, radius18 |
| More info button | `(562,410,212,67)`, radius17; gap14 |
| Continue Watching heading | `(273,565,650,40)` — 31 px left of the hero text column |
| Picture cards | `(275,620,329,175)`, `(618,620,328,175)`, `(965,620,329,175)`, `(1311,620,327,175)`; radius15 |
| First-card focus hard outline | Approximately `(268,613,336,187)`; white border3–4, broad soft white/blue halo |
| Titles below pictures | x277/623/971/1317, top808, height27 |
| Progress / remaining line | First bar `(277,845,227,7)`; remaining label starts x527 at y840; apply the equivalent local offsets to each card |
| Footer | `(1420,902,218,18)`, right aligned |

Typography: light sans hero76–80, letter spacing approximately16–20 px; metadata21 with wider tracking; description21–22, line height27; primary label24 medium; secondary22; section32 medium; card titles20 medium; remaining17; nav20; footer12.

Palette sampled from the PNG: canvas **`#030710`** at(830,925); rail **`#0A0C19`** at(45,510); shaded cyan side of selected nav **`#2B5675`** at(53,204); primary white **`#FCFDFC`** at(330,450); secondary glass **`#232633`** at(575,450); progress **`#36E7FA`** at(330,848); track **`#292E40`** at(485,848); hard picture focus **`#FFFFFF`** at(268,700). Rail edges blend cyan at the upper left and restrained purple at the lower/right edge; those colors are a faint border/bloom, not a solid purple rail.

Focus: retain the active navigation row's subdued cyan-to-purple border. Actual card focus is a clean white outline/halo and a small apparent lift. The white primary button is its normal theme treatment; distinguish actual remote focus by its outer ring/elevation, without turning every control cyan.

## 02 — Essential

Reference: `02-essential-classic.png`.

**Composition:** A flush, opaque left navigation slab and a direct, familiar hero/row structure. The landscape cards are taller than Glass's and contain their labels and progress inside a dark lower overlay. No floating glass rail and no serif headlines.

| Element | Reference rectangle / placement |
|---|---|
| Navigation slab | `(0,0,233,941)`, no floating outer radius |
| POV logo | Approximately `(54,57,100,46)` |
| Active Home row | `(18,155,204,73)`, radius14, cyan outline3; icon x45, label x103 |
| Remaining navigation | Icon x46 and label x103; centers approximately y271,350,429,509,589,669 |
| Hero artwork | Full background to right of the slab; readable hero/gradient extends to about y550 |
| Hero title slot | `(302,155,650,89)` |
| Metadata | `(302,259,470,27)` |
| Description | `(303,306,560,48)`, two lines |
| Resume button | `(302,392,250,68)`, radius13 |
| More info button | `(568,392,194,67)`, radius13; gap16 |
| Continue Watching heading | `(265,551,650,35)` |
| Card bodies | `(267,597,335,242)`, `(616,597,334,242)`, `(964,597,334,242)`, `(1312,597,334,242)`; radius13 |
| First-card focus outline | Approximately `(261,591,346,251)`, cyan3–4; small halo only |
| Card titles | Local x20, top174; uppercase, widely tracked, one line; lower vignette behind text |
| Progress / remaining | First bar `(286,814,203,6)`, remaining label around `(506,809,80,21)`; equivalent offsets on other cards |
| Footer | Approximately `(1376,888,255,19)` |

Typography: light sans hero80–84; metadata23; description22, line height29; primary26 medium; secondary24 medium; section29 medium; card titles20 with roughly5 px tracking; remaining15; navigation20–21; footer14.

Palette: canvas **`#080F1D`** at(830,925); rail **`#070D19`** at(8,500); primary **`#00EBFE`** at(325,420); secondary **`#263143`** at(580,415); picture focus **`#01FAFF`** at(262,700); progress **`#05EDF6`** at(330,817); track **`#394557`** at(460,817). Text is cool off-white, with muted blue-gray metadata; avoid introducing purple gradients.

Focus: thin cyan outlines on the active nav row and the focused card. Keep card corners crisp and halo restrained. Primary action is a flat cyan fill with dark text; this must remain visually different from Glass's white action and Horizon's two-color action.

## 03 — Premiere

Reference: `03-premiere-cinematic.png`.

**Composition:** Near-black cinematic presentation, warm ivory/gold accents, a very large tracked **serif** title, and a compact frameless navigation stack. The Continue Watching section intentionally spans almost the whole screen **below the navigation stack**, starting at x80 rather than staying in the hero's x267 content column.

| Element | Reference rectangle / placement |
|---|---|
| Background / hero art | Full viewport, with black gradient strongest over the entire left third and bottom |
| Navigation zone | Approximately `(32,40,196,540)`; no opaque full-height boxed rail |
| POV logo | `(49,54,100,47)` |
| Active Home marker | `(36,160,4,32)`, rounded warm-gold vertical line; icon around(58,164), label starts x106 |
| Navigation centers | Home177, Movies240, TV Shows304, Anime367, Library432, Search496, Settings559 |
| Hero title slot | `(267,221,900,92)` |
| Metadata | `(267,328,450,28)` |
| Description | `(267,371,625,49)`, two lines |
| Resume button | `(267,452,255,74)`, radius21 |
| More info button | `(543,452,256,74)`, radius21; gap21 |
| Continue Watching heading | `(80,620,650,36)` |
| Card bodies | `(78,662,370,207)`, `(467,662,368,206)`, `(851,662,362,206)`, `(1232,662,367,206)`; radius13 |
| First-card focus | Approximately `(72,657,379,214)`, warm ivory outline2–3 and a quiet amber halo |
| Card title area | Local x20, top139; tracked serif capitals; last title can use two short centered lines as pictured |
| Progress / remaining | First bar around `(98,845,253,6)`, remaining `(370,839,65,19)`; title/bar both inside image gradient |
| Footer | Approximately `(1368,905,271,14)`, small and widely tracked |

Typography: serif hero approximately96–100, thin/regular Roman, tracking20–23 px; metadata20 with5 px tracking; description22, line height29; action labels25 medium sans; section28 medium sans; card titles20–22 serif with5–7 px tracking; nav20–21; footer10–11 with2–3 px tracking. Body/navigation remain sans-serif; do not apply serif globally.

Palette: canvas **`#000102`** at(830,925); left background **`#000000`** at(10,400); active nav gold **`#F4DFAB`** at(37,176); primary top **`#FCDB96`** at(292,460), bottom **`#F7C47E`** at(292,518); secondary **`#1F1C1A`** at(570,485); picture outline highlight **`#FFFFD8`** at(73,720); progress **`#EBCE9C`** at(150,848); track **`#262423`** at(320,848).

Focus: a slim warm gold/ivory border, not cyan. Active Home uses a gold vertical marker and gold icon/text without a filled pill. Primary action is a soft pale-gold vertical gradient; keep the surrounding black space and warm shadow.

## 04 — Atelier

Reference: `04-atelier-light.png`.

**Composition:** Ivory editorial canvas, horizontal navigation, a left text block and a **separate rounded hero image on the right**. Four wide picture cards have their captions below the artwork. There is no full-screen dark background and no sidebar.

| Element | Reference rectangle / placement |
|---|---|
| Canvas | Full viewport ivory |
| POV logo | `(72,31,98,52)` |
| Navigation row | Top35–86; active Home pill `(217,35,113,51)`, radius25 |
| Navigation anchors | Movies x381, TV Shows x522, Anime x690, Library x821; Search icon x958 and text x995; Settings icon x1127 and text x1164 |
| Eyebrow | `(74,191,500,20)`, uppercase copper/brown with wide tracking |
| Hero title slot | `(72,224,575,83)` |
| Metadata | `(73,325,450,27)` |
| Description | `(74,376,520,50)`, two lines |
| Resume button | `(73,464,230,71)`, radius15; white outer stroke and charcoal fill |
| More info button | `(318,465,220,69)`, radius15; gap15 |
| Separate hero artwork | `(682,115,937,452)`, radius20; landscape center-crop entirely inside its own panel |
| Continue Watching heading | `(54,599,750,39)` |
| Picture cards | `(59,650,371,177)`, `(452,648,376,183)`, `(847,648,376,183)`, `(1242,648,377,183)`; radius14 |
| First picture focus | Approximately `(54,644,381,189)`, charcoal border4, soft warm drop shadow |
| Progress | Inside picture near bottom: first `(77,814,337,5)`; cream fill, warm gray track |
| Card titles | x62/457/852/1247, top845, height27; sentence/title case, not tracked capitals |
| Remaining text | Same x as title, top876, height23 |
| Footer | Approximately `(1423,907,220,18)` |

Typography: hero76–80 light sans, tracking11–14 px; eyebrow14 medium, tracking7–8 px; metadata22; description21–22, line height30; primary/secondary23 medium; section33 medium; card titles21 medium; remaining18; nav21 with active Home bold; footer12.

Palette: canvas **`#FBF6F0`** at(830,925); active Home **`#EBE3D7`** at(230,60); primary **`#2C292D`** at(90,500); secondary **`#ECE3D9`** at(330,500); focus border **`#212125`** at(56,730); progress cream **`#EDD6BF`** at(170,817); track **`#9F9690`** at(330,817). Main text is near charcoal; descriptions are muted warm gray. Use a restrained copper eyebrow, approximately`#B48662`; it is an accent text treatment rather than a button color.

Focus: dark outline/shadow on light cards and a white outer stroke around a dark focused button. Keep the Home destination as a pale beige pill. Do not inherit cyan focus from the dark themes.

## 05 — Horizon

Reference: `05-horizon-futuristic.png`.

**Composition:** Horizontal navigation over edge-to-edge space artwork, nearly black base, wide light sans hero, neon cyan focus, and a cyan-to-purple primary action. Titles/progress are inside image cards. The diagonal light streak belongs to this artwork; it is not an extra fixed UI line to draw over every movie.

| Element | Reference rectangle / placement |
|---|---|
| POV logo | `(60,41,105,54)` |
| Active Home capsule | `(218,43,127,46)`, radius24, cyan outline2–3 and soft halo |
| Navigation anchors | Movies x385, TV Shows x518, Anime x676, Library x800; Search icon x1357/text1396; Settings icon1505/text1544 |
| Eyebrow | `(72,165,650,20)`, thin widely tracked capitals |
| Hero title slot | `(70,202,780,78)` |
| Metadata | `(71,290,460,27)` |
| Description | `(72,338,650,48)`, two lines |
| Resume button | `(69,419,270,76)`, radius18; outer focus approximately `(66,416,277,83)` |
| More info button | `(357,422,235,70)`, radius14; gap18 |
| Continue Watching heading | `(70,560,650,40)` |
| Card bodies | `(66,613,372,221)`, `(457,612,369,226)`, `(847,612,372,226)`, `(1238,612,374,226)`; radius15 |
| First-card focus | Approximately `(60,607,383,234)`, cyan3 and stronger outward cyan halo |
| Card title | Local x19, top171; uppercase sans with wide tracking |
| Progress / duration line | First bar `(85,815,213,6)`; right label around x369,y810; lower dark vignette supports the line |
| Footer | Approximately `(1365,888,250,19)` |

Typography: hero76–80 light sans, tracking17–20 px; eyebrow16, tracking7–8; metadata21; description21, line height29; action24 medium; section29 medium; card titles18–19, tracking5–6; duration15; nav20; footer14.

Palette: canvas **`#010308`** at(830,925); primary cyan side **`#64E8FD`** at(80,450), purple side **`#B773FC`** at(328,450); secondary **`#0A1220`** at(370,455); card focus **`#6BF7FF`** at(62,700); progress **`#59CCF8`** at(150,817); track **`#222F41`** at(280,817). Use cool white headings and pale blue-gray supporting text.

Focus: the most luminous cyan halo among the six, but keep text sharp. Preserve the primary cyan/purple gradient even when unfocused; reserve the extra external stroke/halo and lift for the actual focused control. The active navigation capsule remains identifiable while a content card has focus.

## 06 — Studio

Reference: `06-studio-dashboard.png`.

**Composition:** A left navigation rail plus a true **two-column content dashboard**: one large Continue Watching feature on the left, two vertically stacked Up Next cards on the right, then four category Browse tiles. Do not implement this as a full-bleed hero with the usual poster row underneath.

| Element | Reference rectangle / placement |
|---|---|
| Rail zone | `(0,0,287,941)`, flush near-black; no floating glass shell |
| POV logo | `(58,74,118,58)` |
| Active Home row | `(33,174,239,72)`, radius18, cyan outline; icon x61, label x117 |
| Navigation centers | Home211, Movies291, TV Shows371, Anime451, Library531, Search612, Settings693 |
| Continue Watching heading | `(310,61,650,43)` |
| Main feature panel | `(310,110,862,479)`, radius17; artwork bounded to this panel |
| Feature title slot | `(346,372,680,54)`; overlay lower third of the image |
| Feature metadata | `(346,434,480,30)` |
| Feature progress | `(347,477,378,8)`; elapsed/total label around `(746,474,155,21)` |
| Resume button | `(345,508,210,59)`, radius14 |
| More info button | `(567,507,187,61)`, radius14; gap12 |
| Up Next heading | `(1197,67,350,35)` |
| Up Next cards | `(1197,111,440,221)` and `(1197,352,440,237)`, radius18; 20 px vertical gap |
| Up Next title / episode | Local x24, title top119/132; episode text about top159/171; bottom gradient behind both |
| Up Next progress | First `(1222,304,314,6)`; second `(1222,559,314,6)`; remaining label right aligned near x1614 |
| Browse heading | `(310,624,400,32)` |
| Browse tiles | `(310,662,307,208)`, `(633,662,334,208)`, `(985,662,318,208)`, `(1318,662,319,208)`; radius16 |
| Browse labels | Local x24, top157; Movies, TV Shows, Anime, My Library plus chevron |
| Footer | Approximately `(1397,899,240,19)` |

The reference's Browse tile widths vary slightly; the measured rectangles above preserve that asymmetry. Keep the feature/right-column ratio, heading positions, all four tile endpoints, and the right margin even if integer rounding is necessary in native layout.

Typography: section Continue Watching34 medium; Up Next28 medium; feature title48–52 light sans with16–19 px tracking; metadata23; feature action21 medium; Up Next titles27–29 light sans with7–9 px tracking; episode17; remaining14; Browse heading27; Browse labels29–31 bold; nav22; footer13.

Palette: canvas **`#0B0F15`** at(830,925); rail **`#0B1014`** at(8,500); nav outline **`#02E2FB`** at(34,210); primary **`#01C4FE`** at(365,530); secondary **`#1C2028`** at(579,540); feature progress **`#02CDFA`** at(450,481); track **`#534C52`** at(700,481), composited over artwork. Panel outlines are subtle blue-gray; the sampled feature edge at(310,200) is artwork-influenced and should not be treated as a literal brown UI border.

Focus: active Home is the strongly outlined target in the reference; feature/Up Next/Browse panel edges are initially subtle. On actual remote movement use the same cyan outline/soft halo for the newly focused target. Do not permanently illuminate every panel or add administrative dashboard widgets.

## Dynamic content invariants

1. **Keep geometry fixed; replace content.** Backdrops, posters, title, year, genre, runtime, synopsis, progress, and episode labels come from the currently selected real item and actual local/connected-library data. The fictional titles, descriptions, timings, and the astronaut are reference content, not data to hardcode into the application.
2. Feature art uses a native image view/shader with a cover/center-crop presentation. Full-bleed themes retain their own left/bottom readability gradients; Atelier and Studio retain their bounded image panels. Do not place the complete reference PNG behind transparent click areas.
3. Render the actual title as live text. The stylized star/light crossing the reference's letter I is decorative within that fictional treatment; do not insert a literal I, a fixed star at that letter position, or AFTERLIGHT into arbitrary titles. Preserve each theme's type family, tracking, text bounds, and hierarchy.
4. Eyebrows in Atelier and Horizon occupy their own fixed slots. Use an available real tagline or neutral editorial/category label; an absent tagline must not pull the title/buttons upward or produce a fabricated movie description.
5. Progress bars are bounded percentages; remaining/elapsed/runtime labels must match their actual meaning. The screenshot timing values vary between themes and must never be copied into live history. Theme6's feature has elapsed/total text; the other themes have their own smaller duration/remaining slot.
6. Fewer than four real Continue Watching entries must not generate fake watched entries. Keep the pictured card size and spacing for available entries. An empty state can change the heading to Discover and use real catalog content while retaining the theme's composition. Studio's Up Next panels must use real episode data or a clear empty/discovery state, never fictitious saved history.
7. The native screen still exposes the same Home, Movies, TV Shows, Anime, Library, Search, and Settings destinations in the pictured order. Preserve the existing POV lettermark asset, and keep the quiet footer text **“Standalone app made by Mark Mateo”**.
8. The selected navigation destination and actual remote focus are separate states. Preserve the destination's pictured pill/bar/text treatment, while only the actual content/control focus gets the extra lift/outline emphasis. Transitions may change artwork/text within these bounds; they must not move the CTA or silently change layout theme.

## Android 8 rendering fallback

- Glass is the only theme requiring a noticeably translucent navigation shell. On Android8 approximate it with a smoked navy/charcoal translucent rounded drawable, a subtle vertical/light diagonal gradient, a thin cyan/purple edge, and a bounded soft shadow. Keep text on a sufficiently dark inner fill. This preserves the silhouette, depth, and measured rail dimensions without depending on live backdrop blur.
- Use hardware-friendly native gradients, rounded outlines, image clipping, and static/cheap focus halos. Do not call Android12 `RenderEffect` or other unavailable blur APIs on Android8. A device lacking blur should retain precisely the same geometry and focus behavior.
- Essential, Premiere, Atelier, Horizon, and Studio do not need runtime blur to match their references. Their distinct fills, image vignettes, typography, button shapes, and compositions carry the design.
- Layout/artwork changes must not recreate a playing session, alter the selected engine, change provider credentials, or reset progress. Theme selection is presentation state.

## Focused visual regression plan

- Capture each native home at the same viewport/aspect ratio with deterministic **synthetic public** art/text and compare the bounds in this document. Verify all six compositions individually, including Studio's Up Next stack and Premiere's full-width lower row.
- Check a short title and a long title per theme: CTA positions and Continue Watching/Browse headings must remain inside their measured slots.
- Move focus from active Home to the primary action and across the first four cards. Assert visible, unclipped theme-specific focus outlines and no focus on invisible containers.
- Inspect one empty-history home and one one-card home per composition; no synthetic progress or episodes may appear in ordinary account UI.
- On Android8 check rounded clipping, gradients, legible title overlays, and the Glass fallback. No blur-capability branch may change navigation/card coordinates.
