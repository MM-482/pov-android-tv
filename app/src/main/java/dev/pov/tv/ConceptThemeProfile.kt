package dev.pov.tv

/** Coordinates transcribed from the six approved 1672 x 941 studies, on a 960 x 540 canvas. */
data class ConceptThemeProfile(
    val previewAsset: String,
    val railX: Int = 0, val railY: Int = 0, val railWidth: Int = 134, val railHeight: Int = 540,
    val logoX: Int = 29, val logoY: Int = 24, val logoWidth: Int = 61, val logoHeight: Int = 42,
    val navX: Int = 10, val navY: Int = 89, val navWidth: Int = 117, val navHeight: Int = 40, val navStep: Int = 46,
    val navIconInset: Int = 18,
    val contentLeft: Int = 152, val contentRight: Int = 18, val headerHeight: Int = 0,
    val heroCopyX: Int = 174, val heroCopyY: Int = 89, val heroCopyWidth: Int = 373,
    val heroHeight: Int = 313, val titleSize: Int = 44, val titleTracking: Float = .15f,
    val sectionX: Int = 152, val sectionY: Int = 316, val cardsY: Int = 343, val cardsX: Int = sectionX,
    val cardWidth: Int = 192, val cardHeight: Int = 139, val cardGap: Int = 8,
    val cardImageHeight: Int = 139, val labelsOutside: Boolean = false,
    val radius: Int = 8, val buttonRadius: Int = 7, val footerY: Int = 510,
) {
    companion object {
        fun forTheme(theme: ThemeSpec): ConceptThemeProfile? = when (theme) {
            ThemeSpec.GLASS -> ConceptThemeProfile("theme_previews/01-glass-apple-tv.png",
                railX=16,railY=17,railWidth=120,railHeight=504,logoX=46,logoY=43,logoWidth=58,logoHeight=36,
                navX=23,navY=102,navWidth=106,navHeight=38,navStep=48,navIconInset=14,contentLeft=154,
                heroCopyY=102,heroCopyWidth=396,heroHeight=325,titleSize=47,titleTracking=.20f,
                sectionX=158,sectionY=327,cardsY=355,cardWidth=190,cardHeight=143,cardGap=10,
                cardImageHeight=102,labelsOutside=true,radius=10,buttonRadius=11,footerY=517)
            ThemeSpec.ESSENTIAL -> ConceptThemeProfile("theme_previews/02-essential-classic.png")
            ThemeSpec.PREMIERE -> ConceptThemeProfile("theme_previews/03-premiere-cinematic.png",
                railWidth=135,railHeight=342,logoY=27,navX=20,navY=88,navWidth=111,navHeight=33,navStep=37,navIconInset=12,
                heroCopyX=153,heroCopyY=124,heroCopyWidth=501,heroHeight=350,titleSize=51,titleTracking=.13f,
                sectionX=45,sectionY=356,cardsY=379,cardWidth=212,cardHeight=119,cardGap=10,cardImageHeight=119,radius=8,buttonRadius=12,footerY=518)
            ThemeSpec.ATELIER -> ConceptThemeProfile("theme_previews/04-atelier-light.png",
                railWidth=0,railHeight=0,logoX=41,logoY=17,logoWidth=57,logoHeight=33,
                navX=125,navY=20,navWidth=65,navHeight=29,navStep=85,contentLeft=31,contentRight=31,headerHeight=60,
                heroCopyX=42,heroCopyY=109,heroCopyWidth=325,heroHeight=336,titleSize=45,titleTracking=.16f,
                sectionX=31,sectionY=345,cardsY=372,cardsX=34,cardWidth=216,cardHeight=143,cardGap=10,
                cardImageHeight=105,labelsOutside=true,radius=9,buttonRadius=9,footerY=523)
            ThemeSpec.HORIZON -> ConceptThemeProfile("theme_previews/05-horizon-futuristic.png",
                railWidth=0,railHeight=0,logoX=34,logoY=22,logoWidth=61,logoHeight=33,
                navX=125,navY=24,navWidth=73,navHeight=28,navStep=91,contentLeft=39,contentRight=32,headerHeight=69,
                heroCopyX=41,heroCopyY=94,heroCopyWidth=448,heroHeight=315,titleSize=44,titleTracking=.25f,
                sectionX=40,sectionY=323,cardsY=351,cardsX=38,cardWidth=214,cardHeight=130,cardGap=10,cardImageHeight=130,radius=9,buttonRadius=10,footerY=512)
            ThemeSpec.STUDIO -> ConceptThemeProfile("theme_previews/06-studio-dashboard.png",
                railWidth=166,logoX=33,logoY=42,logoWidth=68,logoHeight=33,
                navX=19,navY=100,navWidth=137,navHeight=41,navStep=46,navIconInset=16,contentLeft=178,contentRight=21,
                sectionX=178,sectionY=33,cardsY=63,cardWidth=496,cardHeight=276,cardGap=10,radius=10,buttonRadius=9,footerY=518)
            else -> null
        }
    }
}

/** Pure construction helpers avoid enum initialization cycles. */
internal fun conceptPalette(id: String): ThemePalette {
    val light = id == "atelier"
    val warm = id == "premiere"
    val accent = when (id) { "glass" -> 0xFFFCFDFC; "premiere" -> 0xFFF4DFAB; "atelier" -> 0xFF212125; "horizon" -> 0xFF6BF7FF; "studio" -> 0xFF02E2FB; else -> 0xFF00EBFE }.toInt()
    val background=when(id){"glass"->0xFF030710;"essential"->0xFF080F1D;"premiere"->0xFF000102;"atelier"->0xFFFBF6F0;"horizon"->0xFF010308;else->0xFF0B0F15}.toInt()
    val panel=when(id){"glass"->0xFF232633;"essential"->0xFF263143;"premiere"->0xFF1F1C1A;"atelier"->0xFFECE3D9;"horizon"->0xFF0A1220;else->0xFF1C2028}.toInt()
    val progress=when(id){"glass"->0xFF36E7FA;"essential"->0xFF05EDF6;"premiere"->0xFFEBCE9C;"atelier"->0xFFEDD6BF;"horizon"->0xFF59CCF8;else->0xFF02CDFA}.toInt()
    return ThemePalette(
        background=background,panel=panel,
        text=(if(light) 0xFF24242A else if(warm) 0xFFFFF7E6 else 0xFFF4F6FC).toInt(),
        muted=(if(light) 0xFF66636A else if(warm) 0xFFBDB8B1 else 0xFFB7C3D8).toInt(),
        accent=accent,secondaryAccent=(if(warm) 0xFFEBA23E else if(light) 0xFFAA7755 else 0xFFBA44FF).toInt(),
        buttonText=(if(light) 0xFFFFFFFF else 0xFF03070A).toInt(),
        border=(if(light) 0xFFD9D0C5 else 0xFF2B394A).toInt(),
        navSelected=(if(light) 0xFFECE3D8 else if(warm) 0xFF1A150C else 0xFF102437).toInt(),
        progress=progress,primaryButton=if(id=="studio")0xFF01C4FE.toInt() else if(light)0xFF2C292D.toInt() else accent)
}

internal fun conceptLayout(id: String): ThemeLayout {
    val top = id in setOf("atelier", "horizon")
    return ThemeLayout(if(top) NavigationPlacement.TOP_BAR else NavigationPlacement.LEFT_RAIL,
        if(id=="atelier") HeroComposition.SPLIT else if(id=="studio") HeroComposition.RESUME_GRID else HeroComposition.FULL_BLEED,
        CardFormat.LANDSCAPE,contentInsetDp=if(top) 32 else 18,
        railWidthDp=if(top) 0 else if(id=="studio") 160 else 136,
        headerHeightDp=if(id=="atelier") 60 else if(id=="horizon") 69 else 0,
        heroHeightDp=if(id=="studio") 276 else 315,cardWidthDp=if(top) 216 else 190,cardHeightDp=132,
        cardGapDp=10,cornerRadiusDp=if(id=="glass") 11 else 8,titleSizeSp=if(id=="premiere") 51 else 44,
        titleTypeface=if(id=="premiere") "serif" else "sans-serif-light",titleLetterSpacing=.16f,
        sectionTitleSizeSp=18,focusScale=1.012f)
}
