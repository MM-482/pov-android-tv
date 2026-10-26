package dev.pov.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import androidx.media3.ui.PlayerView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import dev.pov.tv.data.*
import dev.pov.tv.subtitles.SubtitleSettings
import dev.pov.tv.subtitles.SubtitleProvider
import java.net.URL
import kotlin.math.roundToInt
import java.util.Calendar
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class MainActivity : Activity() {
    private lateinit var store: SecureStore
    private lateinit var torbox: TorBoxApi
    private lateinit var trakt: TraktApi
    private lateinit var simkl: SimklApi
    private lateinit var mdblist: MdbListApi
    private lateinit var resolver: SourceResolver
    private lateinit var providers: ProviderHub
    private val catalog = CatalogApi()
    private lateinit var history: PlaybackStore
    private lateinit var playbackPreferences: PlaybackPreferences
    private val continueOptions = java.util.WeakHashMap<View, () -> Unit>()
    private val torboxFolderRevisions = java.util.IdentityHashMap<CloudFolder, String>()
    private lateinit var body: LinearLayout
    private lateinit var badge: TextView
    private lateinit var themePreferences: ThemePreferences
    private var theme = ThemeSpec.CINEMATIC
    private lateinit var shellRoot: FrameLayout
    private lateinit var heroBackdrop: FrameLayout
    private lateinit var backdropArt: ImageView
    private lateinit var scroller: ScrollView
    private val navigationViews = mutableMapOf<String, View>()
    private var destination = "home"
    private var isHome = false
    // Debug capture uses public discovery only; no account state or viewing history is shown.
    private var publicPreview = false
    private var playbackAuditPreview = false
    private lateinit var anime: AnimeCatalogApi
    private val worker = Executors.newFixedThreadPool(4)
    private val images = Executors.newFixedThreadPool(3)
    private val bitmapCache = object : LruCache<String, Bitmap>(18 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private var authorization: DeviceAuthorizationDialog? = null
    @Volatile private var generation = 0
    private lateinit var focusController: TvFocusController
    private val contentFocusChanged = android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, _ ->
        if (::focusController.isInitialized) focusController.revealCurrent()
    }
    private data class NavigationSnapshot(val screen: () -> Unit, val focus: TvFocusSnapshot, val destination: String)
    private var pendingFocus: TvFocusSnapshot? = null
    private var wantsContentFocus = false
    private var resumed = false
    private var miniPlayer: PlayerView? = null
    private var miniFrame: FrameLayout? = null
    private var miniLabel: TextView? = null
    private var miniFocus: MiniPlayerFocusRouter? = null
    internal var currentPlaybackOptionsDialog: AlertDialog? = null
        private set
    internal var currentContinueOptionsDialog: AlertDialog? = null
        private set
    private val playbackChanged: () -> Unit = { runOnUiThread { if (resumed && !isDestroyed) updateMiniPlayer() } }
    private val authWorker = Executors.newSingleThreadExecutor()
    private val selectedSeasons = mutableMapOf<String, Int>()
    private val catalogOffsets = mutableMapOf<String, ArrayDeque<Int>>()
    private var resolvingGeneration: Int? = null
    private var launchingPlayer = false
    private val backStack = ArrayDeque<NavigationSnapshot>()
    private var current: () -> Unit = { home() }
    private val remoteProgress = mutableMapOf<String, Double>()
    private var studioNextRevision = ""
    private var studioNextEpisodes = emptyList<PlaybackIdentity>()
    private var studioNextLoadedAt = 0L
    private val bg get() = theme.palette.background
    private val panel get() = theme.palette.panel
    private val muted get() = theme.palette.muted
    private val accent get() = theme.palette.accent
    private val ink get() = theme.palette.text

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val debug = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        playbackAuditPreview = debug && intent.getBooleanExtra("playback_audit_preview", false)
        val sourcePreview = debug && intent.getBooleanExtra("source_choice_preview", false)
        publicPreview = debug && (intent.getBooleanExtra("public_preview", false) || sourcePreview || playbackAuditPreview)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        store = SecureStore(this); torbox = TorBoxApi(store); trakt = TraktApi(store)
        simkl = SimklApi(store); mdblist = MdbListApi(store)
        resolver = SourceResolver(store, torbox); history = PlaybackStore(this)
        providers = ProviderHub(store)
        playbackPreferences = PlaybackPreferences(this)
        themePreferences = ThemePreferences(this)
        theme = if (publicPreview) ThemeSpec.conceptFromId(themePreferences.selected().id) else themePreferences.selectedConcept()
        anime = AnimeCatalogApi(trakt)
        InAppPlaybackSession.initialize(this)
        InAppPlaybackSession.addListener(playbackChanged)
        shell(); if (sourcePreview) sourceChoiceAudit() else home(); if (!publicPreview) { retrySync(); AdditionalTracking.retry(this) }
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener(contentFocusChanged)
    }

    override fun onResume() {
        super.onResume(); resumed = true; launchingPlayer = false
        if (::badge.isInitialized) updateBadge()
        if (::shellRoot.isInitialized) updateMiniPlayer()
    }
    override fun onPause() {
        // Keep the outgoing target until the incoming fullscreen view can attach first.
        resumed = false
        super.onPause()
    }
    override fun onStop() {
        miniPlayer?.let(InAppPlaybackSession::detachPlayerView)
        super.onStop()
    }
    override fun onDestroy() {
        window.decorView.viewTreeObserver.takeIf { it.isAlive }?.removeOnGlobalFocusChangeListener(contentFocusChanged)
        currentPlaybackOptionsDialog?.dismiss(); currentPlaybackOptionsDialog = null
        currentContinueOptionsDialog?.dismiss(); currentContinueOptionsDialog = null
        authorization?.dismiss(); miniPlayer?.let(InAppPlaybackSession::detachPlayerView)
        InAppPlaybackSession.removeListener(playbackChanged)
        worker.shutdownNow(); images.shutdownNow(); authWorker.shutdownNow(); bitmapCache.evictAll()
        super.onDestroy()
    }
    @Deprecated("Android TV Back handling") override fun onBackPressed() {
        if (backStack.isNotEmpty()) {
            val saved = backStack.removeLast(); current = saved.screen; destination = saved.destination
            pendingFocus = saved.focus; current(); refreshNavigation()
        } else AlertDialog.Builder(this).setTitle("Leave POV?")
            .setPositiveButton(if (InAppPlaybackSession.hasActive()) "Stop and leave" else "Leave") { _, _ -> InAppPlaybackSession.stop(); finish() }
            .setNegativeButton("Stay", null).show()
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            var focused: View? = currentFocus
            while (focused != null) {
                val options = continueOptions[focused]
                if (options != null) {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) options()
                    return true
                }
                focused = focused.parent as? View
            }
        }
        if (event.keyCode == KeyEvent.KEYCODE_MENU && InAppPlaybackSession.hasActive()) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) playbackOptions()
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && ::focusController.isInitialized) {
            val direction = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
                KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
                KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
                else -> null
            }
            if (direction != null && miniFocus?.route(direction, currentFocus) == true) return true
            if (body.hasFocus() && direction != null && focusController.next(direction) == null) {
                val leaving = (theme.layout.navigation == NavigationPlacement.TOP_BAR && direction == View.FOCUS_UP) ||
                    (theme.layout.navigation == NavigationPlacement.LEFT_RAIL && direction == View.FOCUS_LEFT)
                if (leaving && navigationViews[destination]?.requestFocus() == true) return true
            }
            if (body.hasFocus() && event.keyCode in setOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP))
                return focusController.move(if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) View.FOCUS_DOWN else View.FOCUS_UP)
            val entering = (theme.layout.navigation == NavigationPlacement.LEFT_RAIL && event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) ||
                (theme.layout.navigation == NavigationPlacement.TOP_BAR && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN)
            if (entering && navigationViews.values.any { it.hasFocus() } && focusController.first()) return true
        }
        return super.dispatchKeyEvent(event)
    }

    private data class Destination(val key: String, val label: String, val screen: () -> Unit)
    private fun destinations() = listOf(
        Destination("home", "Home") { home() }, Destination("movie", "Movies") { catalogPage("movie") },
        Destination("series", "Shows") { catalogPage("series") }, Destination("anime", "Anime") { animePage() },
        Destination("library", "Library") { cloud("torrents") }, Destination("search", "Search") { search() },
        Destination("trakt", "My Trakt") { traktPage() }, Destination("settings", "Settings") { settings() }) +
        if ((!publicPreview || playbackAuditPreview) && InAppPlaybackSession.hasActive()) listOf(Destination("nowplaying", "Now playing") { returnToVideo() }) else emptyList()

    private fun shell() {
        miniPlayer?.let(InAppPlaybackSession::detachPlayerView)
        miniPlayer = null; miniFrame = null; miniLabel = null; miniFocus = null
        navigationViews.clear()
        if (theme.conceptProfile != null) {
            val targets = destinations().filter { it.key !in setOf("trakt", "nowplaying") }.map { target ->
                ConceptDestination(target.key, if (target.key == "series") "TV Shows" else target.label) {
                    if (target.key == "search") target.screen()
                    else {
                        destination = target.key; backStack.clear(); current = target.screen
                        current(); refreshNavigation()
                    }
                }
            }
            val rendered = ConceptShellRenderer(this, theme).render(targets) { destination }
            shellRoot = rendered.root; body = rendered.body; scroller = rendered.scroller
            heroBackdrop = rendered.heroBackdrop; backdropArt = rendered.backdropArt; badge = rendered.badge
            navigationViews.putAll(rendered.navigationViews)
            focusController = TvFocusController(body, scroller) {
                val rect = android.graphics.Rect()
                miniFrame?.takeIf { it.isShown && it.getGlobalVisibleRect(rect) }?.let { rect }
            }
            scroller.setOnScrollChangeListener { _, _, y, _, _ -> heroBackdrop.translationY = -y.toFloat() }
            setContentView(shellRoot); updateBadge(); refreshNavigation()
            if (resumed) updateMiniPlayer()
            return
        }
        shellRoot = FrameLayout(this).apply { setBackgroundColor(bg); clipChildren = false }
        heroBackdrop = FrameLayout(this).apply { visibility = View.GONE }
        backdropArt = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        heroBackdrop.addView(backdropArt, FrameLayout.LayoutParams(-1, -1))
        heroBackdrop.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(bg, withAlpha(bg, 180), Color.TRANSPARENT)) }, FrameLayout.LayoutParams(-1, -1))
        heroBackdrop.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(bg, Color.TRANSPARENT, Color.TRANSPARENT)) }, FrameLayout.LayoutParams(-1, -1))
        shellRoot.addView(heroBackdrop, FrameLayout.LayoutParams(-1, dp(325)))
        scroller = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; clipChildren = false; clipToPadding = false }
        body = column().apply { clipChildren = false; clipToPadding = false }
        scroller.addView(body)
        focusController = TvFocusController(body, scroller) {
            val rect = android.graphics.Rect()
            miniFrame?.takeIf { it.isShown && it.getGlobalVisibleRect(rect) }?.let { rect }
        }
        scroller.setOnScrollChangeListener { _, _, y, _, _ -> heroBackdrop.translationY = -y.toFloat() }
        val layout = theme.layout
        shellRoot.addView(scroller, FrameLayout.LayoutParams(-1, -1).apply {
            leftMargin = dp(layout.railWidthDp + layout.contentInsetDp)
            rightMargin = dp(layout.contentInsetDp)
            topMargin = dp(layout.headerHeightDp)
        })
        if (layout.navigation == NavigationPlacement.LEFT_RAIL) {
            val rail = column().apply {
                setPadding(dp(12), dp(22), dp(1), dp(13))
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(bg, withAlpha(bg, 235)))
            }
            shellRoot.addView(rail, FrameLayout.LayoutParams(dp(layout.railWidthDp), -1))
            rail.addView(logo(), margin(61, 58, bottom = 24).apply { leftMargin = dp(10) })
            destinations().forEach { target -> rail.addView(navigation(target, false), margin(-1, 35, bottom = 4)) }
            rail.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
            rail.addView(text("Made by\nMark Mateo", 9, muted), margin(-1, -2, top = 8))
        } else {
            val header = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(layout.contentInsetDp), dp(5), dp(16), dp(5))
            }
            shellRoot.addView(header, FrameLayout.LayoutParams(-1, dp(layout.headerHeightDp)))
            header.addView(logo(), margin(45, 45, right = 22))
            destinations().forEach { target -> header.addView(navigation(target, true), LinearLayout.LayoutParams(0, dp(38), 1f)) }
            header.addView(View(this), margin(145, 1))
        }
        badge = text("", 12, ink).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(6), dp(12), dp(6)) }
        if (theme == ThemeSpec.EDITORIAL || theme == ThemeSpec.DASHBOARD) badge.background = rounded(panel, 22)
        shellRoot.addView(badge, FrameLayout.LayoutParams(-2, dp(31), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(if (layout.navigation == NavigationPlacement.LEFT_RAIL) 15 else (layout.headerHeightDp - 31) / 2)
            rightMargin = dp(layout.contentInsetDp.coerceAtLeast(19))
        })
        setContentView(shellRoot)
        updateBadge(); refreshNavigation()
        if (resumed) updateMiniPlayer()
    }

    private fun logo() = ImageView(this).apply {
        setImageResource(R.drawable.pov_icon); scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "POV"
    }

    private fun navigation(target: Destination, top: Boolean): View {
        val frame = FrameLayout(this).apply {
            isFocusable = true; isFocusableInTouchMode = true; isClickable = true; contentDescription = target.label
            tag = "nav:${target.key}"
        }
        val line = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        if (!top) {
            line.addView(NavigationIconView(this, target.key, muted), margin(19, 19, right = 12))
            line.setPadding(dp(11), 0, 0, 0)
        } else line.gravity = Gravity.CENTER
        val label = text(target.label, if (top) 12 else 12, muted)
        line.addView(label, LinearLayout.LayoutParams(-2, -2))
        frame.addView(line, FrameLayout.LayoutParams(-1, -1))
        val indicator = View(this).apply { tag = "indicator"; background = rounded(accent, 2) }
        frame.addView(indicator, if (top) FrameLayout.LayoutParams(dp(31), dp(2), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            else FrameLayout.LayoutParams(dp(3), dp(29), Gravity.START or Gravity.CENTER_VERTICAL))
        fun paint(focused: Boolean) {
            val selected = destination == target.key
            val color = if (selected || focused) ink else muted
            label.setTextColor(color); label.typeface = Typeface.create("sans-serif", if (selected) Typeface.BOLD else Typeface.NORMAL)
            for (index in 0 until line.childCount) (line.getChildAt(index) as? NavigationIconView)?.tint = color
            indicator.visibility = if (selected) View.VISIBLE else View.INVISIBLE
            if (selected && theme == ThemeSpec.EDITORIAL) indicator.background = rounded(theme.palette.secondaryAccent, 2)
            frame.background = rounded(if (focused || (!top && selected) || (theme == ThemeSpec.DASHBOARD && selected)) theme.palette.navSelected else Color.TRANSPARENT, if (theme == ThemeSpec.DASHBOARD) 24 else 7)
            frame.foreground = if (focused) FocusRingDrawable(accent, dp(if (theme == ThemeSpec.DASHBOARD) 24 else 7).toFloat(), true, dp(1).toFloat()) else null
        }
        frame.setOnFocusChangeListener { _, focused -> paint(focused) }
        frame.setOnClickListener {
            if (target.key == "search" || target.key == "nowplaying") target.screen()
            else { destination = target.key; backStack.clear(); current = target.screen; current(); refreshNavigation() }
        }
        navigationViews[target.key] = frame
        paint(false)
        return frame
    }

    private fun refreshNavigation() {
        // Rebuilding a nav cell's state does not steal focus from remote navigation.
        navigationViews.values.forEach { view -> view.onFocusChangeListener?.onFocusChange(view, view.hasFocus()) }
    }

    private fun updateBadge() {
        val provider = ProviderHub.SPECS.first { it.id == playbackPreferences.providerId }
        val box = !publicPreview && try { providers.connected(provider.id) } catch (_: Exception) { false }
        val tracked = !publicPreview && try { trakt.connected() } catch (_: Exception) { false }
        val value = android.text.SpannableString("●  ${provider.label}    ●  Trakt")
        value.setSpan(android.text.style.ForegroundColorSpan(if (box) theme.palette.success else muted), 0, 1, 0)
        val secondDot = value.toString().indexOf('●', 1)
        value.setSpan(android.text.style.ForegroundColorSpan(if (tracked) theme.palette.success else muted), secondDot, secondDot + 1, 0)
        badge.text = value
        badge.contentDescription = "${provider.label} ${if (box) "linked" else "not linked"}. Trakt ${if (tracked) "linked" else "not linked"}."
    }
    private fun navigate(screen: () -> Unit) {
        backStack.addLast(NavigationSnapshot(current, focusController.capture(), destination))
        pendingFocus = null; current = screen; screen()
    }
    private fun focusAfterRender(id: Int) {
        body.post {
            if (id != generation || isDestroyed) return@post
            val restore = pendingFocus
            if (restore != null) {
                if (focusController.restore(restore) || focusController.restore(null)) { pendingFocus = null; wantsContentFocus = false }
            } else if (wantsContentFocus && !focusController.hasVisibleLeafFocus()) {
                if (focusController.restore(null)) wantsContentFocus = false
            }
        }
    }
    private fun page(title: String, subtitle: String = ""): Int {
        generation++; resolvingGeneration = null; isHome = false; wantsContentFocus = true
        continueOptions.clear()
        if (theme.conceptProfile != null) ConceptShellRenderer.applyContentInsets(scroller, theme, home = false)
        focusController.reset(); body.removeAllViews(); heroBackdrop.visibility = View.GONE
        body.setPadding(0, dp(if (theme.layout.navigation == NavigationPlacement.LEFT_RAIL) 63 else 15), 0, contentBottomPadding())
        scroller.scrollTo(0, 0); heroBackdrop.translationY = 0f
        if (title.isNotBlank()) body.addView(text(title, 29, ink, true), margin(-1, -2, bottom = 5))
        if (subtitle.isNotBlank()) body.addView(text(subtitle, 12, muted), margin(-1, -2, bottom = 18))
        return generation
    }

    private fun home() {
        current = { home() }
        destination = "home"; refreshNavigation()
        val id = page(""); isHome = true; body.setPadding(0, 0, 0, contentBottomPadding())
        val recent = if (publicPreview) emptyList() else history.continueWatching()
        read(id, { catalog.popular("movie") to catalog.popular("series") }) { (movies, series) ->
            if (theme.conceptProfile != null) {
                val revision = if (theme == ThemeSpec.STUDIO && !publicPreview)
                    runCatching { if (trakt.connected()) trakt.accountRevision() else "" }.getOrDefault("") else ""
                if (studioNextRevision != revision) {
                    studioNextRevision = revision; studioNextEpisodes = emptyList(); studioNextLoadedAt = 0
                }
                renderConceptHome(movies, series, recent, studioNextEpisodes)
                if (revision.isNotBlank() && System.currentTimeMillis() - studioNextLoadedAt > 300_000) {
                    loadStudioNext(id, revision, movies, series, recent)
                }
                return@read
            }
            if (theme == ThemeSpec.DASHBOARD) {
                dashboard(recent, movies, series)
            } else {
                val featured = movies.firstOrNull() ?: series.firstOrNull()
                featured?.let { hero(it) }
                if (recent.isNotEmpty()) {
                    resumeRow(recent)
                } else {
                    mediaRow(if (theme == ThemeSpec.EDITORIAL || theme == ThemeSpec.CINEMA) "Discover" else "Popular movies", movies)
                }
                mediaRow(if (recent.isNotEmpty()) "Discover" else "Popular TV shows", if (recent.isNotEmpty()) movies else series)
                if (recent.isNotEmpty()) mediaRow("Popular TV shows", series)
            }
            homeCredit()
        }
    }

    private fun renderConceptHome(movies: List<MediaItemData>, series: List<MediaItemData>, recent: List<PlaybackStore.Recent>, upNext: List<PlaybackIdentity>) {
        ConceptHomeRenderer(this, theme).render(body, heroBackdrop, backdropArt,
            ConceptHomeData(movies, series, recent, upNext), ConceptHomeCallbacks(
                loadImage = { view, address -> image(view, address) },
                details = { item -> navigate { details(item) } },
                play = { item ->
                    if (item.type == "movie") navigate { showSources(PlaybackIdentity(item.imdbId, item.type, item.title)) }
                    else navigate { details(item) }
                },
                resume = { saved -> resumeSaved(saved) },
                episode = { identity -> navigate { showSources(identity) } },
                browse = { target -> navigate {
                    when (target) {
                        "movie", "movies" -> catalogPage("movie")
                        "series", "shows" -> catalogPage("series")
                        "anime" -> animePage()
                        else -> cloud("torrents")
                    }
                } },
                bindContinue = { view, saved -> bindContinueOptions(view, saved.key, saved.identity, saved.title) { resumeSaved(saved) } }
            ))
        focusAfterRender(generation)
    }

    private fun loadStudioNext(id: Int, revision: String, movies: List<MediaItemData>, series: List<MediaItemData>, recent: List<PlaybackStore.Recent>) {
        worker.execute {
            try {
                if (!trakt.connected() || trakt.accountRevision() != revision) return@execute
                val episodes = trakt.nextEpisodes(expectedRevision = revision)
                if (!trakt.connected() || trakt.accountRevision() != revision) return@execute
                runOnUiThread {
                    if (id != generation || !isHome || theme != ThemeSpec.STUDIO || isFinishing || isDestroyed ||
                        !trakt.connected() || trakt.accountRevision() != revision) return@runOnUiThread
                    studioNextEpisodes = episodes; studioNextLoadedAt = System.currentTimeMillis()
                    if (episodes.isEmpty()) return@runOnUiThread
                    val focused = body.findFocus()
                    if (focused != null) {
                        val rect = android.graphics.Rect(0, 0, focused.width, focused.height)
                        body.offsetDescendantRectToMyCoords(focused, rect)
                        // Do not replace the episode column while someone is navigating within it.
                        if (rect.left >= dp(680) && rect.top < dp(345)) return@runOnUiThread
                        pendingFocus = focusController.capture()
                    }
                    continueOptions.clear()
                    renderConceptHome(movies, series, recent, episodes)
                }
            } catch (_: Exception) {
                // Optional Up Next never blocks catalog, local resume, or playback.
                runOnUiThread { if (studioNextRevision == revision) studioNextLoadedAt = System.currentTimeMillis() }
            }
        }
    }

    private fun hero(item: MediaItemData, showAction: Boolean = true) {
        val layout = theme.layout
        val split = layout.hero == HeroComposition.SPLIT
        val framed = layout.hero == HeroComposition.FRAMED
        val full = layout.hero == HeroComposition.FULL_BLEED || layout.hero == HeroComposition.RESUME_GRID
        val height = if (!showAction) 262 else layout.heroHeightDp
        val frame = FrameLayout(this).apply { clipChildren = false; tag = "featured-hero" }
        val art = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val address = item.background.ifBlank { item.poster }
        if (full) {
            heroBackdrop.layoutParams.height = dp(height + layout.headerHeightDp + 22) + body.paddingTop
            heroBackdrop.requestLayout()
            heroBackdrop.visibility = View.VISIBLE
            image(backdropArt, address)
        } else {
            val artFrame = FrameLayout(this).apply { background = rounded(panel, layout.cornerRadiusDp); clipToOutline = true }
            artFrame.addView(art, FrameLayout.LayoutParams(-1, -1)); image(art, address)
            if (!split) artFrame.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.BLACK, withAlpha(Color.BLACK, 130), Color.TRANSPARENT)) }, FrameLayout.LayoutParams(-1, -1))
            frame.addView(artFrame, FrameLayout.LayoutParams(if (split) dp(577) else -1, -1, Gravity.END))
            if (framed) frame.foreground = rounded(Color.TRANSPARENT, 8, accent)
        }
        val copy = column()
        val copyWidth = if (split) 311 else if (theme == ThemeSpec.CINEMA) 330 else 410
        val title = text(item.title.uppercase(), layout.titleSizeSp, ink, theme == ThemeSpec.NEON).apply {
            typeface = if (theme.isConcept) ConceptUi(this@MainActivity, theme).font(400, serif = theme == ThemeSpec.PREMIERE)
                else Typeface.create(layout.titleTypeface, if (theme == ThemeSpec.NEON) Typeface.BOLD else Typeface.NORMAL)
            letterSpacing = layout.titleLetterSpacing; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            if (theme != ThemeSpec.CINEMA) {
                val measured = paint.measureText(text.toString()) + (text.length - 1).coerceAtLeast(0) * letterSpacing * textSize
                val ratio = (dp(copyWidth).toFloat() / measured.coerceAtLeast(1f)).coerceIn(0.58f, 1f)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize * ratio)
            } else if (item.title.length > 22) setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(36).toFloat())
        }
        if (split && showAction) copy.addView(text(greeting(), 16, ink), margin(-1, -2, bottom = 6))
        copy.addView(title, margin(-1, -2))
        copy.addView(text(listOf(item.year, item.genres.firstOrNull().orEmpty(), item.runtime).filter(String::isNotBlank).joinToString("  ·  "), 13, muted), margin(-1, -2, top = 5))
        val description = text(item.description, 13, muted).apply {
            maxLines = if (theme == ThemeSpec.CINEMA || theme == ThemeSpec.NEON) 2 else 3
            ellipsize = TextUtils.TruncateAt.END; setLineSpacing(dp(2).toFloat(), 1f)
        }
        if (item.description.isNotBlank()) copy.addView(description, margin(if (split) -1 else 345, -2, top = 10))
        val left = if (theme == ThemeSpec.CINEMATIC) 20 else if (framed) 25 else 0
        val copyTop = if (!showAction) 15 else when (theme) { ThemeSpec.CINEMATIC -> 90; ThemeSpec.EDITORIAL -> 15; ThemeSpec.NEON -> 21; else -> 8 }
        val actionBottom = if (theme == ThemeSpec.CINEMATIC) 20 else if (split) 10 else 16
        val availableCopy = dp(if (showAction) height - actionBottom - 38 - copyTop - 8 else height - copyTop - 10)
        repeat(10) {
            copy.measure(View.MeasureSpec.makeMeasureSpec(dp(copyWidth), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            if (copy.measuredHeight > availableCopy) {
                if (description.maxLines > 1) description.maxLines--
                else title.setTextSize(TypedValue.COMPLEX_UNIT_PX, (title.textSize * 0.9f).coerceAtLeast(dp(18).toFloat()))
            }
        }
        frame.addView(copy, FrameLayout.LayoutParams(dp(copyWidth), -2).apply { leftMargin = dp(left); topMargin = dp(copyTop) })
        if (showAction) {
            val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val saved = if (publicPreview) null else history.recent().firstOrNull { it.identity?.imdbId == item.imdbId && it.position < it.duration * 0.95 }
            actions.addView(primaryButton(if (saved != null) "▶  Resume" else "▶  Explore") {
                if (saved?.identity != null) navigate { showSources(saved.identity) } else navigate { details(item) }
            }, margin(160, 38, right = 17))
            if (!split && theme != ThemeSpec.CINEMA && theme != ThemeSpec.NEON) actions.addView(button("＋  Add to Library") {
                if (trakt.connected()) load(generation, { trakt.setWatchlist(PlaybackIdentity(item.imdbId, item.type, item.title), true) }) { toast("Added to your Trakt watchlist") }
                else { toast("Connect Trakt to save titles to your library"); navigate { traktPage() } }
            }.apply { background = rounded(withAlpha(panel, 90), 24) }, margin(159, 38))
            frame.addView(actions, FrameLayout.LayoutParams(dp(copyWidth), dp(38), Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(left); bottomMargin = dp(actionBottom)
            })
        }
        body.addView(frame, margin(-1, height, bottom = if (split) 10 else 0))
    }

    private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> "Good morning."; in 12..17 -> "Good afternoon."; else -> "Good evening."
    }

    private fun resumeRow(recent: List<PlaybackStore.Recent>) {
        section("Continue Watching")
        body.addView(text("Hold Select or press Menu for title options", 10, muted), margin(-1, -2, bottom = 7))
        val row = horizontalRow()
        recent.take(12).forEach { saved ->
            val identity = saved.identity
            val item = MediaItemData(identity?.imdbId.orEmpty(), identity?.type ?: "movie", saved.title,
                background = identity?.imdbId?.let { "https://images.metahub.space/background/medium/$it/img" }.orEmpty())
            val remaining = "${((saved.duration - saved.position).coerceAtLeast(0) / 60000)}m left"
            val action: () -> Unit = {
                resumeSaved(saved)
            }
            if (theme == ThemeSpec.CINEMATIC) {
                val group = column()
                group.addView(tile(item, saved.position.toDouble() / saved.duration.coerceAtLeast(1), action = action).apply {
                    tag = "resume:${saved.key}"
                    bindContinueOptions(this, saved.key, saved.identity, saved.title, action)
                }, margin(-1, theme.layout.cardHeightDp))
                group.addView(text(remaining, 10, muted), margin(-1, -2, top = 4))
                row.addView(group, margin(cardWidth(), theme.layout.cardHeightDp + 20, right = theme.layout.cardGapDp))
            } else row.addView(tile(item, saved.position.toDouble() / saved.duration.coerceAtLeast(1), remaining, action).apply {
                tag = "resume:${saved.key}"
                bindContinueOptions(this, saved.key, saved.identity, saved.title, action)
            }, margin(cardWidth(), theme.layout.cardHeightDp, right = theme.layout.cardGapDp))
        }
    }

    /** Owned synthetic row only; no real history, service calls, or account values enter this fixture. */
    internal fun showContinueAuditFixture() {
        check(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 && playbackAuditPreview)
        current = { showContinueAuditFixture() }
        page("Continue Watching audit", "Public synthetic title · hold Select or press Menu for options.")
        val key = "pov-public-continue-audit"
        if (!history.isHidden(key)) resumeRow(listOf(PlaybackStore.Recent(key, "Public Continue Watching audit", null, 60_000, 600_000)))
        else body.addView(text("Removed from Continue Watching", 18, ink), margin(-1, -2, top = 20))
    }

    private fun bindContinueOptions(view: View, key: String, identity: PlaybackIdentity?, title: String, resume: () -> Unit) {
        val open = {
            currentContinueOptionsDialog?.dismiss()
            val dialog = AlertDialog.Builder(this).setTitle(title)
                .setItems(arrayOf("Resume", "Remove from Continue Watching")) { _, choice ->
                    if (choice == 0) resume() else {
                        val focus = focusController.capture()
                        history.hideFromContinueWatching(key, identity)
                        identity?.let { remoteProgress.remove(identityKey(it)) }
                        pendingFocus = focus
                        current()
                        toast("Removed from Continue Watching on this device")
                    }
                }.setNegativeButton("Cancel", null).show()
            currentContinueOptionsDialog = dialog
            dialog.setOnDismissListener { if (currentContinueOptionsDialog === dialog) currentContinueOptionsDialog = null }
            Unit
        }
        continueOptions[view] = open
        view.setOnLongClickListener { open(); true }
        view.contentDescription = "${view.contentDescription?.toString().orEmpty()}. Hold Select for Resume or Remove from Continue Watching."
    }

    private fun dashboard(recent: List<PlaybackStore.Recent>, movies: List<MediaItemData>, series: List<MediaItemData>) {
        section(if (recent.isNotEmpty()) "Continue Watching" else "Discover")
        val rowTop = horizontalRow()
        val entries = if (recent.isNotEmpty()) recent.take(3).map { saved ->
            MediaItemData(saved.identity?.imdbId.orEmpty(), saved.identity?.type ?: "movie", saved.title,
                background = saved.identity?.imdbId?.let { "https://images.metahub.space/background/medium/$it/img" }.orEmpty()) to saved
        } else movies.take(3).map { it to null }
        val available = 960 - theme.layout.contentInsetDp * 2 - 13
        val firstWidth = (available * 0.37).toInt()
        val otherWidth = (available - firstWidth - 2 * theme.layout.cardGapDp) / 2
        entries.forEachIndexed { index, (item, saved) ->
            val action: () -> Unit = {
                if (saved != null) resumeSaved(saved) else navigate { details(item) }
            }
            val progress = saved?.let { it.position.toDouble() / it.duration.coerceAtLeast(1) }
            val caption = saved?.let { "${((it.duration - it.position).coerceAtLeast(0) / 60000)}m left" } ?: listOf(item.year, item.genres.firstOrNull().orEmpty()).filter(String::isNotBlank).joinToString(" · ")
            val frame = tile(item, progress, caption, action) as FrameLayout
            frame.tag = "dashboard:${saved?.key ?: item.imdbId}"
            saved?.let { bindContinueOptions(frame, it.key, it.identity, it.title, action) }
            if (index == 0) frame.addView(primaryButton(if (saved != null) "▶ Resume" else "▶ Explore", action).apply {
                saved?.let { bindContinueOptions(this, it.key, it.identity, it.title, action) }
            },
                FrameLayout.LayoutParams(dp(104), dp(31), Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(12); bottomMargin = dp(33) })
            rowTop.addView(frame, margin(if (index == 0) firstWidth else otherWidth, 185, right = theme.layout.cardGapDp))
        }
        val lower = LinearLayout(this)
        val next = column()
        val nextHeading = text("Discover Shows", 22, ink, true)
        next.addView(nextHeading, margin(-1, -2, top = 12, bottom = 9))
        val row = LinearLayout(this)
        series.take(3).forEach { item -> row.addView(tile(item, caption = "TV show") { navigate { details(item) } }, margin(204, 139, right = 8)) }
        next.addView(row)
        val request = generation
        if (!publicPreview && trakt.connected()) worker.execute {
            val upcoming = try { trakt.nextEpisodes() } catch (_: Exception) { emptyList() }
            runOnUiThread {
                if (request == generation && upcoming.isNotEmpty() && !isDestroyed) {
                    nextHeading.text = "Up Next"; row.removeAllViews()
                    upcoming.take(3).forEach { identity ->
                        val item = MediaItemData(identity.imdbId, identity.type, identity.title)
                        row.addView(tile(item, caption = "S${identity.season} E${identity.episode}") { navigate { showSources(identity) } }, margin(204, 139, right = 8))
                    }
                }
            }
        }
        lower.addView(next, LinearLayout.LayoutParams(0, -2, 1f))
        val library = column().apply { setPadding(dp(17), 0, 0, 0) }
        library.addView(text("My Library", 22, ink, true), margin(-1, -2, top = 12, bottom = 9))
        library.addView(button("▤   Movies    ›") { navigate { catalogPage("movie") } }, margin(-1, 44, bottom = 6))
        library.addView(button("▣   Shows     ›") { navigate { catalogPage("series") } }, margin(-1, 44, bottom = 6))
        library.addView(button("☁   ${ProviderHub.SPECS.first { it.id == playbackPreferences.providerId }.label} Cloud  ›") { navigate { cloud("torrents") } }, margin(-1, 44))
        lower.addView(library, margin(247, -2)); body.addView(lower)
        body.addView(text("TorBox ${if (!publicPreview && store.has("torbox.token")) "connected" else "not linked"}     ·     Trakt ${if (!publicPreview && trakt.connected()) "connected" else "not linked"}", 12, muted).apply {
            background = rounded(panel, 9); setPadding(dp(20), dp(12), dp(20), dp(12))
        }, margin(-1, -2, top = 14))
    }

    private fun resumeSaved(saved: PlaybackStore.Recent) {
        if (saved.key.startsWith("provider:")) {
            val provider = saved.key.split(':').getOrNull(1)
            if (ProviderHub.SPECS.any { it.id == provider }) {
                navigate { providerCloud(provider!!) }
                toast("Choose the saved file in this library. Its resume position is retained.")
                return
            }
        }
        if (saved.key.startsWith("cloud:")) {
            val parts = saved.key.split(':')
            val folder = parts.getOrNull(2)?.toLongOrNull(); val file = parts.getOrNull(3)?.toLongOrNull()
            val kind = parts.getOrNull(1)
            if (parts.size == 5 && kind in setOf("torrents", "usenet", "webdl") && folder != null && file != null) {
                val revision = java.net.URLDecoder.decode(parts[4], "UTF-8")
                read(generation, { torbox.streamUrl(kind!!, folder, file, expectedRevision = revision) }) { url ->
                    if (torbox.accountRevision() == revision) play(url, saved.title, saved.identity, saved.key)
                }
                return
            }
            navigate { cloud("torrents") }
            toast("Choose this saved file from your current cloud account to continue.")
            return
        }
        saved.identity?.let { identity -> navigate { showSources(identity) } } ?: navigate { cloud("torrents") }
    }

    private fun returnToVideo() {
        if (!InAppPlaybackSession.hasActive()) return
        PlayerActivity.openCurrent(this)
    }

    private fun playbackOptions() {
        if (!InAppPlaybackSession.hasActive() || currentPlaybackOptionsDialog?.isShowing == true) return
        val dialog = AlertDialog.Builder(this).setTitle("Now playing · ${InAppPlaybackSession.title}")
            .setItems(arrayOf("Return to video", "Stop playback")) { _, choice ->
                if (choice == 0) returnToVideo() else InAppPlaybackSession.stop()
            }.setNegativeButton("Keep browsing", null).create()
        dialog.setOnShowListener { dialog.window?.decorView?.tag = "pov_mini_playback_options" }
        currentPlaybackOptionsDialog = dialog
        dialog.setOnDismissListener { if (currentPlaybackOptionsDialog === dialog) currentPlaybackOptionsDialog = null }
        dialog.show()
    }

    private fun updateMiniPlayer() {
        val active = (!publicPreview || playbackAuditPreview) && InAppPlaybackSession.hasActive()
        updatePlaybackNavigation(active)
        val bottomPadding = contentBottomPadding()
        if (body.paddingBottom != bottomPadding) body.setPadding(body.paddingLeft, body.paddingTop, body.paddingRight, bottomPadding)
        if (!active) {
            miniPlayer?.let(InAppPlaybackSession::detachPlayerView); miniFrame?.visibility = View.GONE
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            return
        }
        val exo = InAppPlaybackSession.currentPlayer
        if (exo?.playWhenReady == true && exo.playerError == null && exo.playbackState != androidx.media3.common.Player.STATE_ENDED)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (miniFrame == null) {
            val frame = FrameLayout(this).apply {
                background = rounded(Color.BLACK, 8); clipToOutline = true; isFocusable = true; isFocusableInTouchMode = true; isClickable = true
                tag = "return-to-video"; contentDescription = "Now playing. Select to return to video. Hold Select or press Menu for playback options."
            }
            val video = PlayerView(this).apply {
                useController = false; setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                isFocusable = false; isFocusableInTouchMode = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }
            frame.addView(video, FrameLayout.LayoutParams(-1, -1))
            val caption = column().apply {
                setBackgroundColor(0xB0000000.toInt()); setPadding(dp(8), dp(6), dp(8), dp(6))
            }
            val label = text("", 11, Color.WHITE, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            caption.addView(label)
            caption.addView(text("Select to return · Hold Select for options", 9, 0xFFD3DFED.toInt()).apply { maxLines = 1 })
            frame.addView(caption, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            frame.setOnFocusChangeListener { view, focused ->
                if (focused) { pendingFocus = null; wantsContentFocus = false }
                view.foreground = if (focused) FocusRingDrawable(accent, dp(8).toFloat(), true, dp(2).toFloat()) else null
                caption.setBackgroundColor(if (focused) 0xF0091824.toInt() else 0xB0000000.toInt())
            }
            frame.setOnClickListener { returnToVideo() }
            frame.setOnLongClickListener { playbackOptions(); true }
            shellRoot.addView(frame, FrameLayout.LayoutParams(dp(247), dp(145), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(20); bottomMargin = dp(15)
            })
            miniFrame = frame; miniPlayer = video; miniLabel = label
            miniFocus = MiniPlayerFocusRouter(body, frame)
            frame.post { focusController.revealCurrent() }
        }
        miniFrame?.visibility = View.VISIBLE
        val status = when {
            exo?.playerError != null -> "Playback stopped"
            exo?.playbackState == androidx.media3.common.Player.STATE_ENDED -> "Finished"
            exo?.isPlaying == true -> "Playing"
            exo?.playWhenReady == true && exo.playbackState == androidx.media3.common.Player.STATE_BUFFERING -> "Loading"
            else -> "Paused"
        }
        miniLabel?.text = "$status · ${InAppPlaybackSession.title}"
        miniPlayer?.let(InAppPlaybackSession::attachPlayerView)
    }

    private fun updatePlaybackNavigation(active: Boolean) {
        val existing = navigationViews["nowplaying"]
        if (active == (existing != null)) return
        if (!active) {
            val restoreFocus = existing?.hasFocus() == true
            (existing?.parent as? ViewGroup)?.removeView(existing)
            navigationViews.remove("nowplaying")
            if (restoreFocus) navigationViews[destination]?.requestFocus()
            return
        }
        val host = navigationViews["home"]?.parent as? LinearLayout ?: return
        val lastNavigation = (0 until host.childCount).lastOrNull {
            (host.getChildAt(it).tag as? String)?.startsWith("nav:") == true
        } ?: return
        val top = theme.layout.navigation == NavigationPlacement.TOP_BAR
        val cell = navigation(Destination("nowplaying", "Now playing") { returnToVideo() }, top)
        val params = if (top) LinearLayout.LayoutParams(0, dp(38), 1f) else margin(-1, 35, bottom = 4)
        host.addView(cell, lastNavigation + 1, params)
    }

    private fun contentBottomPadding() = dp(if ((!publicPreview || playbackAuditPreview) && InAppPlaybackSession.hasActive()) 184 else 24)

    private fun homeCredit() {
        body.addView(text("Standalone app made by Mark Mateo  ·  Based on POV by kodifitzwell", 10, muted), margin(-1, -2, top = 20, bottom = 6))
    }

    private fun animePage(type: String = "series", query: String = "", skip: Int = 0) {
        current = { animePage(type, query, skip) }
        destination = "anime"; refreshNavigation()
        val id = page(if (query.isBlank()) "Anime" else "Anime · $query", "Discover anime series, films, and new worlds.")
        val tabs = horizontalRow()
        listOf("series" to "Series & seasons", "movie" to "Anime movies", "special" to "Specials & OVAs").forEach { (key, title) ->
            tabs.addView(button(if (type == key) "●  $title" else title) { animePage(key) }, margin(153, 40, right = 8))
        }
        tabs.addView(button("Search anime") { searchAnime(type) }, margin(140, 38, right = 8))
        read(id, { if (query.isBlank()) anime.popular(type, skip) else anime.search(query, type, skip) }) { result ->
            if (result.items.isEmpty()) body.addView(text("No anime found. Try a different title or category.", 14, muted), margin(-1, -2, top = 20))
            val width = 960 - theme.layout.railWidthDp - theme.layout.contentInsetDp * 2
            val count = (width / (cardWidth() + theme.layout.cardGapDp)).coerceAtLeast(1)
            result.items.chunked(count).forEach { group ->
                val row = LinearLayout(this); body.addView(row, margin(-1, -2, top = 14))
                group.forEach { title ->
                    val media = MediaItemData("", title.type, title.title, title.year, title.poster, title.background, title.description, title.genres)
                    row.addView(tile(media, caption = animeFormat(title)) { navigate { animeDetails(title) } }.apply { tag = "anime:${title.kitsuId}" }, margin(cardWidth(), theme.layout.cardHeightDp, right = theme.layout.cardGapDp))
                }
            }
            val paging = horizontalRow()
            if (skip > 0) paging.addView(button("Previous page") { animePage(type, query, (skip - 20).coerceAtLeast(0)) }, margin(150, 40, top = 16, right = 10))
            if (result.hasMore) paging.addView(button("More anime  ›") { animePage(type, query, result.nextOffset) }, margin(150, 40, top = 16))
            body.addView(text("Anime catalog: Kitsu. Episode lists and titles are confirmed before playback.", 10, muted), margin(-1, -2, top = 20))
        }
    }

    private fun searchAnime(type: String = "series") {
        val input = EditText(this).apply { hint = "Anime title"; setSingleLine() }
        AlertDialog.Builder(this).setTitle("Search anime").setView(input)
            .setPositiveButton("Search") { _, _ -> input.text.toString().trim().takeIf(String::isNotBlank)?.let { query -> navigate { animePage(type, query) } } }
            .setNegativeButton("Cancel", null).show()
    }

    private fun animeFormat(title: AnimeTitle): String = when (title.category) {
        "movie" -> "Anime movie"
        "special" -> if (title.subtype.equals("OVA", true)) "OVA / extra" else "Special / story chapter"
        else -> "Series / season"
    }

    private fun animeDetails(title: AnimeTitle) {
        current = { animeDetails(title) }
        val id = page(title.title, animeFormat(title))
        body.addView(button("Choose a matching title manually") { matchAnime(title) }, margin(280, 40, bottom = 10))
        read(id, { anime.details(title) }) { result ->
            body.removeAllViews(); hero(result.media, false)
            body.addView(text(animeFormat(title) + " · " + result.notice, 12, muted), margin(-1, -2, top = 9, bottom = 12))
            val relatedHost = column(); body.addView(relatedHost, margin(-1, -2, bottom = 12))
            relatedHost.addView(text("Finding related seasons and films…", 12, muted))
            loadRelatedAnime(title, relatedHost, id)
            mediaActions(result.media)
            if (title.type == "series") animeEpisodePicker(result)
            body.addView(button("Choose a matching title manually") { matchAnime(title) }, margin(280, 40, top = 15))
        }
    }

    private fun loadRelatedAnime(title: AnimeTitle, host: LinearLayout, id: Int) {
        worker.execute {
            try {
                val related = anime.related(title)
                runOnUiThread {
                    if (id == generation && !isDestroyed) {
                        host.removeAllViews()
                        val row = LinearLayout(this)
                        fun add(label: String, choices: List<AnimeTitle>, key: String) {
                            row.addView(button("$label (${choices.size})") {
                                if (choices.isEmpty()) toast("No catalog-linked $label are available")
                                else AlertDialog.Builder(this).setTitle(label).setItems(choices.map { it.title }.toTypedArray()) { _, index ->
                                    navigate { animeDetails(choices[index]) }
                                }.setNegativeButton("Cancel", null).show()
                            }.apply { tag = "related:$key" }, margin(192, 40, right = 8))
                        }
                        add("Seasons & story parts", related.seasons, "seasons")
                        add("Anime movies", related.movies, "movies")
                        add("Specials & OVAs", related.specials, "specials")
                        host.addView(row)
                        if (related.otherSeries.isNotEmpty()) host.addView(button("Related spin-off series (${related.otherSeries.size})") {
                            AlertDialog.Builder(this).setTitle("Spin-off series").setItems(related.otherSeries.map { it.title }.toTypedArray()) { _, index ->
                                navigate { animeDetails(related.otherSeries[index]) }
                            }.setNegativeButton("Cancel", null).show()
                        }, margin(280, 38, top = 7))
                        if (related.truncated) host.addView(text("More related entries may be available through Anime search.", 11, muted), margin(-1, -2, top = 6))
                        focusAfterRender(id)
                    }
                }
            } catch (_: Exception) { runOnUiThread {
                if (id == generation && !isDestroyed) {
                    host.removeAllViews(); host.addView(button("Retry related seasons and movies") { loadRelatedAnime(title, host, id) }, margin(310, 40))
                    focusAfterRender(id)
                }
            } }
        }
    }

    private fun animeEpisodePicker(result: AnimeEpisodeCatalog) {
        if (result.groups.isEmpty()) {
            body.addView(text("No verified episodes are available for this selected entry. A film has its own movie action; episode numbers are never substituted.", 14, muted), margin(-1, -2, top = 14)); return
        }
        val key = "anime:${result.anime.kitsuId}"
        val groupIndex = selectedSeasons[key]?.coerceIn(0, result.groups.lastIndex) ?: 0
        val selector = button("") { }
        val host = column()
        fun render(index: Int) {
            selectedSeasons[key] = index
            val group = result.groups[index]
            selector.text = "${group.title} · ${group.episodes.size} episodes  ▾"
            selector.contentDescription = selector.text; selector.tag = "anime-group:$key"
            host.removeAllViews()
            host.addView(text("${result.anime.title} · ${group.title}", 17, ink, true), margin(-1, -2, top = 15, bottom = 9))
            group.episodes.forEach { choice ->
                val identity = choice.identity
                val extra = buildList {
                    if (choice.catalogEpisode != identity.episode) add("Part episode ${choice.catalogEpisode}")
                    choice.absoluteEpisode?.let { add("Overall episode $it") }
                    if (choice.released.isNotBlank()) add(choice.released.take(10))
                }.joinToString(" · ")
                host.addView(episodeButton(identity, choice.title, choice.released, extra), margin(-1, 62, bottom = 7))
            }
            focusAfterRender(generation)
        }
        selector.setOnClickListener {
            AlertDialog.Builder(this).setTitle("Episodes in this entry").setSingleChoiceItems(
                result.groups.map { "${it.title} · ${it.episodes.size} episodes" }.toTypedArray(), selectedSeasons[key] ?: 0
            ) { dialog, index -> dialog.dismiss(); render(index); selector.requestFocus() }.setNegativeButton("Cancel", null).show()
        }
        body.addView(selector, margin(380, 42, top = 10)); body.addView(host)
        render(groupIndex)
    }

    private fun matchAnime(title: AnimeTitle) {
        val input = EditText(this).apply { setText(title.title); hint = "Title to search"; setSingleLine() }
        AlertDialog.Builder(this).setTitle("Find the correct ${if (title.type == "movie") "film" else "show"}")
            .setMessage("Choose the matching catalog title. For sequels and split seasons, check the show's season and episode list before playing.")
            .setView(input).setPositiveButton("Search") { _, _ ->
                val query = input.text.toString().trim()
                if (query.isNotBlank()) load(generation, { catalog.search(query, title.type) }) { matches ->
                    if (matches.isEmpty()) toast("No match found. Try the English or original title.")
                    else AlertDialog.Builder(this).setTitle("Confirm the correct title")
                        .setItems(matches.map { "${it.title} · ${it.year}" }.toTypedArray()) { _, index -> navigate { details(matches[index]) } }
                        .setNegativeButton("Cancel", null).show()
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun appearance() {
        current = { appearance() }
        destination = "settings"; refreshNavigation()
        page("Appearance", "Choose one of the six approved designs. Your accounts and playback stay connected.")
        val width = theme.conceptProfile?.let { 960 - it.contentLeft - it.contentRight }
            ?: (960 - theme.layout.railWidthDp - theme.layout.contentInsetDp * 2)
        val tileWidth = (width - 12) / 2
        ThemeSpec.selectableThemes.chunked(2).forEach { choices ->
            val row = LinearLayout(this); body.addView(row, margin(-1, -2, bottom = 16))
            choices.forEachIndexed { column, choice ->
                val tile = column().apply {
                    isFocusable = true; isFocusableInTouchMode = true; isClickable = true; tag = "theme:${choice.id}"; contentDescription = "Apply ${choice.displayName} theme"
                    background = rounded(panel, 9); setPadding(dp(6), dp(6), dp(6), dp(10))
                }
                val art = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = rounded(panel, 6); clipToOutline = true }
                assets.open(requireNotNull(choice.conceptProfile).previewAsset).use { stream -> art.setImageBitmap(BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inSampleSize = 2 })) }
                tile.addView(art, margin(-1, ((tileWidth - 12) * 9 / 16)))
                tile.addView(text(choice.displayName + if (choice == theme) "  ·  Selected" else "", 16, ink, true).apply { setPadding(dp(8), dp(8), dp(8), 0) })
                tile.addView(text(choice.description, 11, muted).apply { setPadding(dp(8), dp(5), dp(8), 0); maxLines = 2 }, margin(-1, 35))
                tile.setOnFocusChangeListener { view, focused -> view.foreground = if (focused) FocusRingDrawable(accent, dp(9).toFloat(), true, dp(1).toFloat()) else null }
                tile.setOnClickListener {
                    themePreferences.select(choice); theme = choice; destination = "home"; backStack.clear()
                    current = { home() }; shell(); home(); toast("${choice.displayName} theme applied")
                }
                row.addView(tile, margin(tileWidth, -2, right = if (column == 0) 12 else 0))
            }
        }
        body.addView(text("Design previews use sample artwork. Your home screen uses live titles and your viewing progress.", 11, muted), margin(-1, -2, top = 5)); focusAfterRender(generation)
    }

    private fun catalogPage(type: String, query: String = "", skip: Int = 0) {
        current = { catalogPage(type, query, skip) }
        destination = type; refreshNavigation()
        if (skip == 0 && query.isBlank()) catalogOffsets[type]?.clear()
        val id = page(if (query.isBlank()) if (type == "movie") "Movies" else "TV shows" else "Results for “$query”", "Find something worth your evening.")
        val actions = horizontalRow()
        actions.addView(button("Search ${if (type == "movie") "movies" else "shows"}") { search(type) }, margin(170, 38, right = 8))
        if (skip > 0) actions.addView(button("Previous page") { catalogPage(type, query, catalogOffsets[type]?.removeLastOrNull() ?: 0) }, margin(140, 38))
        read(id, { if (query.isBlank()) catalog.popular(type, skip) else catalog.search(query, type) }) { items ->
            mediaGrid(items)
            if (query.isBlank() && items.isNotEmpty()) body.addView(button("More titles  ›") { catalogOffsets.getOrPut(type) { ArrayDeque() }.addLast(skip); catalogPage(type, skip = skip + items.size) }, margin(180, 42, top = 20))
        }
    }

    private fun search(type: String = "movie") {
        val input = EditText(this).apply { hint = "Movie or TV show"; setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT }
        AlertDialog.Builder(this).setTitle("Search ${if (type == "movie") "movies" else "TV shows"}").setView(input)
            .setPositiveButton("Search") { _, _ -> val query = input.text.toString().trim(); if (query.isNotEmpty()) navigate { catalogPage(type, query) } }
            .setNeutralButton(if (type == "movie") "TV shows instead" else "Movies instead") { _, _ -> search(if (type == "movie") "series" else "movie") }
            .setNegativeButton("Cancel", null).show()
    }

    private fun details(item: MediaItemData) {
        current = { details(item) }
        val id = page(item.title, "Loading title…")
        read(id, { catalog.details(item) }) { result ->
            body.removeAllViews(); hero(result, false); mediaActions(result)
            if (result.type == "series") seasonPicker(result)
        }
    }

    private fun mediaActions(result: MediaItemData) {
        val identity = PlaybackIdentity(result.imdbId, result.type, result.title)
        val actions = horizontalRow()
        if (result.type == "movie") actions.addView(button("Find movie sources  ▶") { navigate { showSources(identity) } }, margin(200, 44, right = 10))
        if (result.description.isNotBlank()) actions.addView(button("Synopsis") {
            AlertDialog.Builder(this).setTitle(result.title).setMessage(result.description).setPositiveButton("Close", null).show()
        }, margin(110, 44, right = 8))
        if (trakt.connected()) {
            actions.addView(button("Add to watchlist") { load(generation, { trakt.setWatchlist(identity, true) }) { toast("Added to your Trakt watchlist") } }, margin(155, 44, right = 8))
            actions.addView(button("Remove from watchlist") { confirm("Remove ${result.title} from your Trakt watchlist?") { load(generation, { trakt.setWatchlist(identity, false) }) { toast("Removed from your Trakt watchlist") } } }, margin(190, 44, right = 8))
            if (result.type == "movie") actions.addView(button("Mark watched") { confirm("Mark ${result.title} as watched?") { load(generation, { trakt.markWatched(identity) }) { toast("Trakt history updated") } } }, margin(150, 44))
        }
        if (mdblist.connected() || simkl.connected()) body.addView(button("More list services  ›") { additionalListActions(result) }, margin(220, 42, top = 9))
    }

    private fun additionalListActions(item: MediaItemData) {
        val identity = PlaybackIdentity(item.imdbId, item.type, item.title)
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (mdblist.connected()) {
            actions += "Add to MDBList watchlist" to { load(generation, { mdblist.setWatchlist(identity, true) }) { toast("Added to MDBList watchlist") }; Unit }
            actions += "Remove from MDBList watchlist" to { confirm("Remove ${item.title} from your MDBList watchlist?") {
                load(generation, { mdblist.setWatchlist(identity, false) }) { toast("Removed from MDBList watchlist") }
            }; Unit }
            if (item.type == "movie") actions += "Mark watched on MDBList" to { confirm("Mark ${item.title} watched on MDBList?") {
                load(generation, { mdblist.markWatched(identity) }) { toast("MDBList history updated") }
            }; Unit }
        }
        if (simkl.connected()) {
            actions += "Add to Simkl Plan to Watch" to { load(generation, { simkl.addToWatchlist(item) }) { toast("Added to Simkl Plan to Watch") }; Unit }
            if (item.type == "movie") actions += "Mark watched on Simkl" to { confirm("Mark ${item.title} watched on Simkl?") {
                load(generation, { simkl.markWatched(identity) }) { toast("Simkl history updated") }
            }; Unit }
        }
        AlertDialog.Builder(this).setTitle(item.title).setItems(actions.map { it.first }.toTypedArray()) { _, index -> actions[index].second() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun seasonPicker(result: MediaItemData) {
        val seasons = EpisodePresentation.seasons(result.episodes)
        if (seasons.isEmpty()) { body.addView(text("Episode information is currently unavailable.", 14, muted), margin(-1, -2, top = 18)); return }
        val selector = button("") { }
        val host = column()
        fun render(season: Int) {
            selectedSeasons[result.imdbId] = season
            val label = if (season == 0) "Specials & extras" else "Season $season"
            val episodes = result.episodes.filter { it.season == season }.sortedBy { it.episode }
            selector.text = "$label · ${episodes.size} episodes  ▾"; selector.contentDescription = selector.text; selector.tag = "season:${result.imdbId}"
            host.removeAllViews(); host.addView(text(label, 18, ink, true), margin(-1, -2, top = 16, bottom = 10))
            episodes.forEach { episode ->
                val identity = PlaybackIdentity(result.imdbId, "series", result.title, episode.season, episode.episode)
                host.addView(episodeButton(identity, episode.title, episode.released, episode.released.take(10)), margin(-1, 62, bottom = 7))
            }
            focusAfterRender(generation)
        }
        selector.setOnClickListener {
            val selected = selectedSeasons[result.imdbId]
            AlertDialog.Builder(this).setTitle("Choose a season").setSingleChoiceItems(seasons.map { season ->
                if (season == 0) "Specials & extras" else "Season $season"
            }.toTypedArray(), seasons.indexOf(selected).coerceAtLeast(0)) { dialog, index ->
                dialog.dismiss(); render(seasons[index]); selector.requestFocus()
            }.setNegativeButton("Cancel", null).show()
        }
        body.addView(selector, margin(370, 44, top = 15)); body.addView(host)
        render(EpisodePresentation.selectedSeason(result.episodes, selectedSeasons[result.imdbId])!!)
    }

    private fun episodeButton(identity: PlaybackIdentity, title: String, released: String, detail: String): View {
        val future = EpisodePresentation.future(released)
        val number = if (identity.season == 0) "Special ${identity.episode}" else "S${identity.season} · E${identity.episode}"
        return button("$number   $title\n${if (future) "Not yet released" else detail}") {
            if (future) toast("This episode has not been released yet") else navigate { showSources(identity) }
        }.apply {
            tag = "episode:${identity.imdbId}:${identity.season}:${identity.episode}"
            contentDescription = "$number. $title. ${if (future) "Not yet released" else detail}"
            if (trakt.connected() && !future) setOnLongClickListener {
                confirm("Mark $number as watched?") { load(generation, { trakt.markWatched(identity) }) { toast("Episode marked watched") } }; true
            }
        }
    }

    private fun showSources(identity: PlaybackIdentity) {
        if (playbackPreferences.providerId != "torbox") { providerSources(playbackPreferences.providerId, identity); return }
        current = { showSources(identity) }
        val suffix = if (identity.type == "series") "  ·  S${identity.season} E${identity.episode}" else ""
        val id = page(identity.title + suffix, "Choose a source to play through TorBox.")
        body.addView(button("Playback service · TorBox  ›") { chooseProvider { showSources(identity) } }, margin(-1, 40, bottom = 10))
        if (!store.has("torbox.token")) { body.addView(button("Connect TorBox") { startTorBox() }, margin(200, 44)); return }
        val accountRevision = torbox.accountRevision()
        read(id, { resolver.sources(identity, expectedRevision = accountRevision) }) { sources ->
            if (accountRevision != torbox.accountRevision()) return@read
            if (sources.isEmpty()) body.addView(text("No sources were returned. Try again later or browse My TorBox.", 15, muted))
            val choices = TvChoiceList(this); body.addView(choices, margin(-1, -2))
            sources.distinctBy(TvChoiceKeys::source).take(70).forEach { source ->
                val status = if (source.cached) "READY IN TORBOX" else if (source.directUrl.isNotEmpty()) "DIRECT STREAM" else "TRANSFER MAY BE NEEDED"
                choices.addChoice(TvChoiceRow(this, TvChoiceKeys.source(source), source.title, source.detail,
                    "$status   ·   ${source.provider}", theme.palette) {
                    if (!source.cached && source.directUrl.isEmpty()) confirm("Add this source to your TorBox account? A transfer may need time to finish.") { resolve(source, identity, accountRevision) }
                    else resolve(source, identity, accountRevision)
                })
            }
        }
    }

    private fun providerSources(providerId: String, identity: PlaybackIdentity) {
        current = { providerSources(providerId, identity) }
        val spec = ProviderHub.SPECS.first { it.id == providerId }
        val id = page(identity.title + if (identity.type == "series") " · S${identity.season} E${identity.episode}" else "",
            "Choose a source to play through ${spec.label}.")
        body.addView(button("Playback service · ${spec.label}  ›") { chooseProvider { showSources(identity) } }, margin(-1, 40, bottom = 10))
        if (!providers.connected(providerId)) {
            body.addView(button("Connect ${spec.label}") { navigate { providerSettings(providerId) } }, margin(240, 44)); return
        }
        val revision = providers.accountRevision(providerId)
        if (providerId == "easynews") {
            read(id, { providers.searchEasynews(identity, expectedRevision = revision) }) { files ->
                if (revision != providers.accountRevision(providerId)) return@read
                providerFileRows(providerId, files, identity, accountRevision = revision)
            }
        } else read(id, { resolver.sourcesUncached(identity) }) { sources ->
            if (revision != providers.accountRevision(providerId)) return@read
            val choices = TvChoiceList(this); body.addView(choices, margin(-1, -2))
            if (sources.isEmpty()) body.addView(text("No sources are available. Try another service or source provider.", 14, muted))
            sources.distinctBy(TvChoiceKeys::source).take(70).forEach { source ->
                choices.addChoice(TvChoiceRow(this, "${providerId}:${TvChoiceKeys.source(source)}", source.title, source.detail,
                    if (source.directUrl.isNotBlank()) "DIRECT STREAM" else "${spec.label} · availability checked after selection", theme.palette) {
                    val prepare = { prepareProviderSource(providerId, source, identity, revision) }
                    if (source.directUrl.isNotBlank()) prepare() else confirm("Prepare this source in your ${spec.label} account? A transfer may be created.", prepare)
                })
            }
        }
    }

    private fun prepareProviderSource(providerId: String, source: SourceItem, identity: PlaybackIdentity, revision: String) {
        if (resolvingGeneration == generation || launchingPlayer) return
        val request = generation; resolvingGeneration = request
        load(request, {
            try { providers.prepare(providerId, source, identity, expectedRevision = revision) }
            catch (error: Exception) { runOnUiThread { if (resolvingGeneration == request) resolvingGeneration = null }; throw error }
        }) { prepared ->
            resolvingGeneration = null
            if (revision != providers.accountRevision(providerId)) { toast("The provider account changed. Choose a source again."); return@load }
            when {
                prepared.playback != null -> playProvider(prepared.playback, identity.title, identity, mediaKey(identity))
                prepared.automaticFile != null -> playProviderFile(providerId, prepared.automaticFile!!, identity, revision)
                else -> navigate {
                    current = { providerVersions(providerId, prepared.files, identity, prepared.preferredFileId, revision) }
                    providerVersions(providerId, prepared.files, identity, prepared.preferredFileId, revision)
                }
            }
        }
    }

    private fun mediaKey(identity: PlaybackIdentity) = "media:${identity.imdbId}:${identity.season}:${identity.episode}"

    private fun providerVersions(providerId: String, files: List<ProviderFile>, identity: PlaybackIdentity, preferred: String?, accountRevision: String) {
        current = { providerVersions(providerId, files, identity, preferred, accountRevision) }
        val id = page("Choose a video version", "Each row plays that exact file through ${ProviderHub.SPECS.first { it.id == providerId }.label}.")
        if (accountRevision != providers.accountRevision(providerId)) {
            body.addView(text("The provider account changed. Go back and choose a new source.", 14, muted)); return
        }
        providerFileRows(providerId, files, identity, accountRevision)
        if (pendingFocus == null && preferred != null) pendingFocus = TvFocusSnapshot("provider-file:$providerId:$preferred", 0)
        focusAfterRender(id)
    }

    private fun providerFileRows(providerId: String, files: List<ProviderFile>, identity: PlaybackIdentity? = null,
        accountRevision: String = providers.accountRevision(providerId)) {
        val choices = TvChoiceList(this); body.addView(choices, margin(-1, -2))
        if (files.isEmpty()) body.addView(text("No playable video files are available yet.", 14, muted))
        files.forEach { file ->
            choices.addChoice(TvChoiceRow(this, "provider-file:$providerId:${file.id}", file.name,
                if (file.size > 0) String.format(java.util.Locale.getDefault(), "%.2f GB", file.size / 1_000_000_000.0) else "Video file",
                palette = theme.palette) {
                if (accountRevision == providers.accountRevision(providerId)) playProviderFile(providerId, file, identity, accountRevision)
                else toast("The provider account changed. Reopen this folder or source.")
            })
        }
    }

    private fun playProviderFile(providerId: String, file: ProviderFile, identity: PlaybackIdentity?, revision: String) {
        if (launchingPlayer || resolvingGeneration == generation) { toast("Preparing your selected file…"); return }
        val request = generation; resolvingGeneration = request
        read(request, {
            try { providers.playback(providerId, file, expectedRevision = revision) }
            catch (error: Exception) { runOnUiThread { if (resolvingGeneration == request) resolvingGeneration = null }; throw error }
        }) { playback ->
            resolvingGeneration = null
            if (revision == providers.accountRevision(providerId)) playProvider(playback, identity?.title ?: file.name, identity,
                identity?.let(::mediaKey) ?: providerFileKey(providerId, file))
        }
    }

    private fun playProvider(playback: ProviderPlayback, title: String, identity: PlaybackIdentity?, key: String) {
        play(playback.url, title, identity, key, playback.headers)
    }

    /** Provider IDs can themselves be protected links; persist only an opaque local lookup key. */
    private fun providerFileKey(providerId: String, file: ProviderFile): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest("${file.id}\u0000${file.name}\u0000${file.link}".toByteArray(Charsets.UTF_8))
        return "provider:$providerId:" + digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun providerCloud(providerId: String, folderId: String = "", expectedRevision: String? = null) {
        current = { providerCloud(providerId, folderId, expectedRevision) }; destination = "library"; refreshNavigation()
        val spec = ProviderHub.SPECS.first { it.id == providerId }
        val id = page("My ${spec.label}", "Your cloud library.")
        listConnectionsRow()
        body.addView(button("Playback service · ${spec.label}  ›") { chooseProvider { cloud("torrents") } }, margin(-1, 42, bottom = 10))
        if (!providers.connected(providerId)) { body.addView(button("Connect ${spec.label}") { navigate { providerSettings(providerId) } }, margin(-1, 44)); return }
        if (!spec.cloud) { body.addView(text("Search a movie or episode to browse Easynews results.", 14, muted)); return }
        val revision = expectedRevision ?: providers.accountRevision(providerId)
        if (revision != providers.accountRevision(providerId)) {
            body.addView(text("The provider account changed. Go back and reopen its library.", 14, muted)); return
        }
        read(id, { providers.cloud(providerId, folderId, expectedRevision = revision) }) { folders ->
            if (revision != providers.accountRevision(providerId)) return@read
            val choices = TvChoiceList(this); body.addView(choices, margin(-1, -2))
            if (folders.isEmpty()) body.addView(text("Your ${spec.label} library is empty.", 14, muted))
            folders.forEach { folder -> choices.addChoice(TvChoiceRow(this, "provider-folder:$providerId:${folder.id}", folder.name,
                if (folder.directory) "Folder" else if (folder.ready) "Ready to browse" else "Processing · open to check files", palette = theme.palette) {
                navigate {
                    if (folder.directory) providerCloud(providerId, folder.id, revision) else {
                        current = { providerCloudFiles(providerId, folder, revision) }; providerCloudFiles(providerId, folder, revision)
                    }
                }
            }) }
        }
    }

    private fun providerCloudFiles(providerId: String, folder: ProviderFolder, accountRevision: String) {
        current = { providerCloudFiles(providerId, folder, accountRevision) }
        val id = page(folder.name, "Choose a video file. Unidentified cloud videos do not send tracking updates.")
        val revision = accountRevision
        if (revision != providers.accountRevision(providerId)) {
            body.addView(text("The provider account changed. Go back and reopen its library.", 14, muted)); return
        }
        read(id, { providers.files(providerId, folder.id, expectedRevision = revision) }) { files ->
            if (revision == providers.accountRevision(providerId)) providerFileRows(providerId, files.filter {
                Regex("(?i)\\.(mkv|mp4|m4v|avi|mov|webm|ts|m2ts|mpg|mpeg)$").containsMatchIn(it.name)
            }, accountRevision = revision)
        }
    }

    private fun chooseProvider(done: () -> Unit) {
        val specs = ProviderHub.SPECS
        AlertDialog.Builder(this).setTitle("Playback service")
            .setSingleChoiceItems(specs.map { "${it.label}${if (providers.connected(it.id)) " · connected" else ""}${if (it.experimental) " · experimental" else ""}" }.toTypedArray(),
                specs.indexOfFirst { it.id == playbackPreferences.providerId }) { dialog, index ->
                playbackPreferences.providerId = specs[index].id; dialog.dismiss(); updateBadge(); done()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun resolve(source: SourceItem, identity: PlaybackIdentity, accountRevision: String) {
        if (accountRevision != torbox.accountRevision()) { toast("The TorBox account changed. Reopen the source list."); return }
        if (resolvingGeneration == generation || launchingPlayer) { toast("Preparing the selected source…"); return }
        val request = generation; resolvingGeneration = request
        val progress = text("Preparing your video…", 14, accent); body.addView(progress, margin(-1, -2, top = 14))
        worker.execute {
            try {
                val prepared = resolver.prepare(source, identity, expectedRevision = accountRevision)
                runOnUiThread {
                    if (request == generation && !isFinishing && !isDestroyed) {
                        resolvingGeneration = null; body.removeView(progress)
                        if (accountRevision != torbox.accountRevision()) { toast("The TorBox account changed. Choose a source again."); return@runOnUiThread }
                        when (prepared) {
                            is PreparedSource.Direct -> play(prepared.url, prepared.identity.title, prepared.identity,
                                "media:${prepared.identity.imdbId}:${prepared.identity.season}:${prepared.identity.episode}")
                            is PreparedSource.Files -> {
                                torboxFolderRevisions[prepared.folder] = accountRevision
                                val only = prepared.automaticFile
                                if (only != null) playPreparedFile(prepared, only)
                                else navigate { preparedFiles(prepared) }
                            }
                        }
                    }
                }
            } catch (error: Exception) { runOnUiThread {
                if (request == generation && !isFinishing && !isDestroyed) { resolvingGeneration = null; body.removeView(progress); showError(error); focusAfterRender(request) }
            } }
        }
    }

    private fun preparedFiles(prepared: PreparedSource.Files) {
        current = { preparedFiles(prepared) }
        val identity = prepared.identity
        val suffix = if (identity.type == "series") " · S${identity.season} E${identity.episode}" else ""
        val id = page("Choose a video version", "Choose the correct file for ${identity.title}$suffix. Each row plays that exact file.")
        if (!publicPreview && torboxFolderRevisions[prepared.folder] != torbox.accountRevision()) {
            body.addView(text("The TorBox account changed. Go back and choose a source again.", 14, muted)); return
        }
        val choices = TvChoiceList(this); body.addView(choices, margin(-1, -2))
        prepared.files.forEach { file ->
            choices.addChoice(TvChoiceRow(this, TvChoiceKeys.file(prepared.folder.kind, prepared.folder.id, file.id),
                file.name, fileSize(file), palette = theme.palette) { playPreparedFile(prepared, file) })
        }
        if (prepared.files.isEmpty()) body.addView(text("No eligible video files are available. Go back and choose another source.", 15, muted))
        if (pendingFocus == null) prepared.preferredFileId?.let { fileId ->
            pendingFocus = TvFocusSnapshot(TvChoiceKeys.file(prepared.folder.kind, prepared.folder.id, fileId), 0)
        }
        focusAfterRender(id)
    }

    private fun playPreparedFile(prepared: PreparedSource.Files, file: CloudFile) {
        val identity = prepared.identity
        streamFile(prepared.folder, file, identity.title, identity,
            "media:${identity.imdbId}:${identity.season}:${identity.episode}")
    }

    private fun cloud(kind: String) {
        if (playbackPreferences.providerId != "torbox") { providerCloud(playbackPreferences.providerId); return }
        current = { cloud(kind) }
        destination = "library"; refreshNavigation()
        val id = page("My TorBox", "Your library, ready for the big screen.")
        listConnectionsRow()
        body.addView(button("Playback service · TorBox  ›") { chooseProvider { cloud("torrents") } }, margin(-1, 42, bottom = 10))
        if (!store.has("torbox.token")) { body.addView(button("Connect TorBox") { startTorBox() }, margin(190, 44)); return }
        val tabs = horizontalRow()
        listOf("torrents" to "Torrents", "usenet" to "Usenet", "webdl" to "Web downloads").forEach { (key, title) -> tabs.addView(button(title) { cloud(key) }, margin(150, 39, right = 8)) }
        val accountRevision = torbox.accountRevision()
        read(id, { torbox.cloud(kind, expectedRevision = accountRevision) }) { folders ->
            if (accountRevision != torbox.accountRevision()) return@read
            if (folders.isEmpty()) body.addView(text("Nothing here yet. Your TorBox downloads will appear in this library.", 15, muted))
            val choices = TvChoiceList(this); body.addView(choices, margin(-1, -2))
            folders.forEach { folder ->
                torboxFolderRevisions[folder] = accountRevision
                choices.addChoice(TvChoiceRow(this, TvChoiceKeys.folder(folder.kind, folder.id),
                folder.name, if (folder.ready) "Ready to browse" else "Not ready yet", palette = theme.palette) {
                navigate { cloudFolder(folder) }
            }) }
        }
    }

    private fun cloudFolder(folder: CloudFolder) {
        current = { cloudFolder(folder) }
        val id = page(folder.name, "Choose a video file.")
        val accountRevision = torboxFolderRevisions[folder]
        if (accountRevision == null || accountRevision != torbox.accountRevision()) {
            body.addView(text("The TorBox account changed. Go back and reopen its library.", 14, muted)); return
        }
        read(id, { torbox.folder(folder.kind, folder.id, expectedRevision = accountRevision) }) { result ->
            if (accountRevision != torbox.accountRevision()) return@read
            torboxFolderRevisions[result] = accountRevision
            val files = result.files.filter { Regex("\\.(mkv|mp4|m4v|avi|mov|webm|ts|m2ts|mpg|mpeg)$", RegexOption.IGNORE_CASE).containsMatchIn(it.name) }
            if (files.isEmpty()) body.addView(text("No playable video files are available yet.", 15, muted))
            val choices = TvChoiceList(this); body.addView(choices, margin(-1, -2))
            files.forEach { file -> choices.addChoice(TvChoiceRow(this, TvChoiceKeys.file(result.kind, result.id, file.id),
                file.name, fileSize(file), palette = theme.palette) { cloudChoice(result, file) }) }
        }
    }

    private fun fileSize(file: CloudFile) = String.format(java.util.Locale.getDefault(), "%.2f GB", file.size.toDouble() / 1_000_000_000)

    /** Debug-only, offline fixture uses the same cards/navigation without loading any account or library. */
    private fun sourceChoiceAudit() {
        current = { sourceChoiceAudit() }
        val id = page("Source navigation check", "Seventy synthetic source versions. No account or playback request is made.")
        body.addView(button("Playback service · Public fixture  ›") { toast("Offline navigation fixture") }
            .apply { tag = "fixture:source-service" }, margin(-1, 40, bottom = 10))
        // Match production: an already-focusable top action precedes asynchronously arriving rows.
        body.postDelayed({
            if (id != generation || isDestroyed) return@postDelayed
            val choices = TvChoiceList(this); body.addView(choices, margin(-1, -2))
            repeat(70) { index ->
                val resolution = when { index < 24 -> "2160p"; index < 48 -> "1080p"; else -> "720p" }
                val source = SourceItem("Version ${index + 1} · Example Movie 2026 extended edition with a deliberately long title to verify every row remains independently selectable",
                    "Public fixture", infoHash = String.format(java.util.Locale.ROOT, "%040x", index + 1), fileIndex = index, cached = true)
                choices.addChoice(TvChoiceRow(this, TvChoiceKeys.source(source), source.title,
                    "$resolution · synthetic file · no network", "READY IN TORBOX · Public fixture", theme.palette) {
                    navigate { sourceChoiceAuditFiles(index + 1) }
                })
            }
            focusAfterRender(id)
        }, 120)
    }

    private fun sourceChoiceAuditFiles(sourceVersion: Int) {
        current = { sourceChoiceAuditFiles(sourceVersion) }
        val id = page("Choose a video version", "Selected source version $sourceVersion. All filenames below are synthetic.")
        val selection = text("Choose a file version", 13, accent).apply { tag = "fixture:selection" }
        body.addView(selection, margin(-1, -2, bottom = 12))
        val choices = TvChoiceList(this); body.addView(choices, margin(-1, -2))
        repeat(7) { index ->
            choices.addChoice(TvChoiceRow(this, TvChoiceKeys.file("torrents", 1234, 101L + index),
                "File version ${index + 1} · Example Movie with a long synthetic filename.1080p.mkv", "${index + 1}.00 GB", palette = theme.palette) {
                selection.text = "Selected file version ${index + 1}"
            })
        }
        focusAfterRender(id)
    }

    private fun cloudChoice(folder: CloudFolder, file: CloudFile) {
        val choices = arrayOf("Play file", "Identify movie", "Identify episode")
        AlertDialog.Builder(this).setTitle(file.name).setItems(choices) { _, choice ->
            if (choice == 0) playCloud(folder, file, null)
            else identifyCloud(folder, file, if (choice == 1) "movie" else "series")
        }.setNegativeButton("Cancel", null).show()
    }

    private fun identifyCloud(folder: CloudFolder, file: CloudFile, type: String) {
        val input = EditText(this).apply { hint = "Search title"; setSingleLine() }
        AlertDialog.Builder(this).setTitle("Identify ${if (type == "movie") "movie" else "show"}").setView(input).setPositiveButton("Search") { _, _ ->
            val query = input.text.toString().trim()
            if (query.isBlank()) return@setPositiveButton
            load(generation, { catalog.search(query, type) }) { items ->
                if (items.isEmpty()) { toast("No titles found"); return@load }
                AlertDialog.Builder(this).setTitle("Choose the correct title").setItems(items.map { "${it.title} (${it.year})" }.toTypedArray()) { _, index ->
                    val item = items[index]; val identity = PlaybackIdentity(item.imdbId, type, item.title)
                    if (type == "movie") playCloud(folder, file, identity)
                    else load(generation, { catalog.details(item) }) { detail ->
                        chooseCloudEpisode(folder, file, detail)
                    }
                }.setNegativeButton("Cancel", null).show()
            }
        }.setNegativeButton("Cancel", null).show()
    }

    private fun chooseCloudEpisode(folder: CloudFolder, file: CloudFile, detail: MediaItemData) {
        val seasons = EpisodePresentation.seasons(detail.episodes)
        if (seasons.isEmpty()) { toast("Episode information unavailable"); return }
        AlertDialog.Builder(this).setTitle("Choose a season for ${detail.title}")
            .setItems(seasons.map { if (it == 0) "Specials & extras" else "Season $it" }.toTypedArray()) { _, index ->
                val season = seasons[index]
                val episodes = detail.episodes.filter { it.season == season }.sortedBy { it.episode }
                AlertDialog.Builder(this).setTitle(if (season == 0) "Choose a special" else "Choose a Season $season episode")
                    .setItems(episodes.map { "${if (season == 0) "Special" else "Episode"} ${it.episode}  ${it.title}" }.toTypedArray()) { _, e ->
                        val episode = episodes[e]
                        if (EpisodePresentation.future(episode.released)) toast("This episode has not been released yet")
                        else playCloud(folder, file, PlaybackIdentity(detail.imdbId, "series", detail.title, season, episode.episode))
                    }.setNeutralButton("Seasons") { _, _ -> chooseCloudEpisode(folder, file, detail) }
                    .setNegativeButton("Cancel", null).show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun playCloud(folder: CloudFolder, file: CloudFile, identity: PlaybackIdentity?) {
        val revision = torboxFolderRevisions[folder]
        if (revision == null || revision != torbox.accountRevision()) { toast("Reopen this file from your current TorBox library."); return }
        streamFile(folder, file, file.name, identity, "cloud:${folder.kind}:${folder.id}:${file.id}:${Net.encode(revision)}")
    }

    private fun streamFile(folder: CloudFolder, file: CloudFile, title: String, identity: PlaybackIdentity?, key: String) {
        if (resolvingGeneration == generation || launchingPlayer) { toast("Preparing the selected file…"); return }
        val request = generation; resolvingGeneration = request
        val accountRevision = torboxFolderRevisions[folder]
        if (accountRevision == null || accountRevision != torbox.accountRevision()) {
            resolvingGeneration = null; toast("Reopen this file from your current TorBox account."); return
        }
        val progress = text("Preparing your video…", 14, accent); body.addView(progress, margin(-1, -2, top = 14))
        worker.execute {
            try {
                val url = torbox.streamUrl(folder.kind, folder.id, file.id, expectedRevision = accountRevision)
                runOnUiThread {
                    if (request == generation && !isFinishing && !isDestroyed) {
                        resolvingGeneration = null; body.removeView(progress)
                        if (accountRevision == torbox.accountRevision()) play(url, title, identity, key)
                    }
                }
            } catch (error: Exception) { runOnUiThread {
                if (request == generation && !isFinishing && !isDestroyed) {
                    resolvingGeneration = null; body.removeView(progress); showError(error); focusAfterRender(request)
                }
            } }
        }
    }

    private fun play(url: String, title: String, identity: PlaybackIdentity?, key: String, headers: Map<String, String> = emptyMap()) {
        if (launchingPlayer) return
        if (Uri.parse(url).scheme != "https") { toast("This stream did not return a secure playback link."); return }
        launchingPlayer = true
        val remotePercent = identity?.let { remoteProgress[identityKey(it)] } ?: 0.0
        fun launch(resume: Boolean) {
            try {
                history.beginPlayback(key, identity)
                val intent = Intent(this, PlayerActivity::class.java).putExtra("url", url).putExtra("title", title)
                    .putExtra("playback_request", if (headers.isEmpty()) "" else InAppPlaybackSession.prepareRequest(url, headers))
                    .putExtra("identity", identity?.toJson()?.toString()).putExtra("key", key)
                if (resume) intent.putExtra("resume", history.resume(key)).putExtra("resume_percent", remotePercent)
                startActivity(intent)
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            } catch (error: Exception) {
                launchingPlayer = false
                toast(uiErrorMessage(error))
            }
        }
        try {
            val savedResume = history.resume(key)
            if (savedResume > 0 || remotePercent > 0) AlertDialog.Builder(this).setTitle("Resume watching?")
                .setMessage(if (savedResume > 0) "Continue from ${savedResume / 60_000} minutes?" else "Continue from ${remotePercent.toInt()}% watched on Trakt?")
                .setPositiveButton("Resume") { _, _ -> launch(true) }
                .setNeutralButton("Start over") { _, _ -> launch(false) }
                .setNegativeButton("Cancel") { _, _ -> launchingPlayer = false }.setOnCancelListener { launchingPlayer = false }.show()
            else launch(true)
        } catch (error: Exception) {
            launchingPlayer = false
            toast(uiErrorMessage(error))
        }
    }

    private fun traktPage() {
        current = { traktPage() }
        destination = if (theme.isConcept) "library" else "trakt"; refreshNavigation()
        val id = page("My Trakt", "Your watchlist. Your progress. Your next episode.")
        if (!trakt.connected()) { body.addView(button("Connect Trakt") { startTrakt() }, margin(190, 44)); return }
        read(id, { Triple(trakt.continueWatching(), trakt.watchlist("movie"), trakt.watchlist("series")) }) { (progress, movies, series) ->
            val visibleProgress = progress.filterNot { history.isHidden(identity = it.first) }
            if (visibleProgress.isNotEmpty()) { section("Continue watching"); visibleProgress.forEach { (identity, percent) ->
                remoteProgress[identityKey(identity)] = percent
                val resume = { navigate { showSources(identity) } }
                body.addView(button("${identity.title}${if (identity.type == "series") "  ·  S${identity.season} E${identity.episode}" else ""}   ·   ${percent.toInt()}%", resume).apply {
                    bindContinueOptions(this, "", identity, identity.title, resume)
                }, margin(-1, 44, bottom = 7))
            } }
            val nextGroup = column(); body.addView(nextGroup, margin(-1, -2, top = 16))
            read(id, { trakt.nextEpisodes() }) { next ->
                if (next.isNotEmpty()) {
                    nextGroup.addView(text("Up next", 20, ink, true), margin(-1, -2, bottom = 12))
                    next.forEach { identity -> nextGroup.addView(button("${identity.title}   ·   S${identity.season} E${identity.episode}") { navigate { showSources(identity) } }, margin(-1, 44, bottom = 7)) }
                }
            }
            mediaRow("Movie watchlist", movies); mediaRow("TV watchlist", series)
            body.addView(button("Personal lists") { navigate { traktLists() } }, margin(180, 42, top = 18))
        }
    }

    private fun traktLists() {
        current = { traktLists() }
        val id = page("Your Trakt lists")
        read(id, { trakt.lists() }) { lists ->
            if (lists.isEmpty()) body.addView(text("No personal lists yet.", 15, muted))
            lists.forEach { (listId, title) -> body.addView(button(title) {
                navigate { val pageId = page(title); read(pageId, { trakt.listItems(listId) }) { mediaGrid(it) } }
            }, margin(-1, 44, bottom = 8)) }
        }
    }

    private fun settings() {
        current = { settings() }
        destination = "settings"; refreshNavigation()
        page("Settings", "Make POV yours.")
        section("Appearance")
        body.addView(button("Themes · ${theme.displayName}  ›") { navigate { appearance() } }, margin(-1, 46, bottom = 9))
        section("Playback")
        body.addView(button("Playback engine · ${if (playbackPreferences.selectedEngine == PlaybackEngine.VLC) "VLC" else "Media3"}  ›") {
            val engines = arrayOf(PlaybackEngine.MEDIA3, PlaybackEngine.VLC)
            AlertDialog.Builder(this).setTitle("Playback engine")
                .setSingleChoiceItems(arrayOf("Media3", "VLC"), engines.indexOf(playbackPreferences.selectedEngine)) { dialog, index ->
                    playbackPreferences.selectedEngine = engines[index]
                    dialog.dismiss(); toast("Playback engine saved for the next video"); settings()
                }.setNegativeButton("Cancel", null).show()
        }, margin(-1, 44, bottom = 8))
        body.addView(button("Subtitles · ${subtitleLanguageName(playbackPreferences.subtitleLanguage)}  ›") { navigate { subtitleSettings() } }, margin(-1, 44, bottom = 8))
        body.addView(button("Playback service · ${ProviderHub.SPECS.first { it.id == playbackPreferences.providerId }.label}  ›") { chooseProvider { settings() } }, margin(-1, 44, bottom = 8))
        section("Accounts")
        body.addView(button("Playback service accounts  ›") { navigate { providerAccounts() } }, margin(-1, 44, bottom = 8))
        body.addView(button(if (store.has("torbox.token")) "TorBox · connected · reconnect" else "Connect TorBox") { startTorBox() }, margin(-1, 44, bottom = 8))
        body.addView(button("Use TorBox API key") { inputSetting("TorBox API key", "Paste your own TorBox API key", "", true) { key -> load(generation, { torbox.setApiKey(key) }) { updateBadge(); toast("TorBox connected"); settings() } } }, margin(-1, 44, bottom = 8))
        if (store.has("torbox.token")) body.addView(button("Disconnect TorBox") { confirm("Disconnect TorBox on this device?") { torbox.disconnect(); settings(); updateBadge() } }, margin(-1, 44, bottom = 8))
        body.addView(button(if (trakt.connected()) "Trakt · connected · reconnect" else "Connect Trakt") { startTrakt() }, margin(-1, 44, bottom = 8))
        if (trakt.connected()) body.addView(button("Disconnect Trakt") { confirm("Disconnect Trakt on this device?") { trakt.disconnect(); history.clearPending(); remoteProgress.clear(); settings(); updateBadge() } }, margin(-1, 44, bottom = 8))
        body.addView(button("MDBList · ${if (mdblist.connected()) "connected" else "not linked"}  ›") { navigate { mdbSettings() } }, margin(-1, 44, bottom = 8))
        body.addView(button("Simkl · ${if (simkl.connected()) "connected" else "not linked"}  ›") { navigate { simklSettings() } }, margin(-1, 44, bottom = 8))
        section("App connections")
        body.addView(text("This preview uses your own Trakt app Client ID. Create an app at developer.trakt.tv, then enter its Client ID here before linking your account.", 13, muted), margin(-1, -2, bottom = 9))
        body.addView(button("Trakt app Client ID") { configureTraktClientId() }, margin(-1, 44, bottom = 8))
        body.addView(button("Trakt registered callback URI") { inputSetting("Trakt callback URI", "Must match your registered Trakt app callback", store.get("trakt.redirect_uri", "urn:ietf:wg:oauth:2.0:oob")) { value ->
            store.put("trakt.redirect_uri", value.trim()); toast("Registered callback saved")
        } }, margin(-1, 44, bottom = 8))
        body.addView(button("Source provider address") { inputSetting("Source provider", "HTTPS Stremio-compatible add-on address", store.get("sources.endpoint", "https://torrentio.strem.fun")) { value ->
            val uri = Uri.parse(value.trim()); if (uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) { store.put("sources.endpoint", value.trim()); toast("Source provider saved") } else toast("Enter a valid HTTPS address")
        } }, margin(-1, 44, bottom = 8))
        section("About")
        body.addView(text("POV Standalone · ${BuildConfig.VERSION_NAME}\nStandalone app made by Mark Mateo.\nBased on the original POV Kodi add-on by kodifitzwell.\nOriginal project: github.com/kodifitzwell/repo\nLicensed under GNU GPL version 3.\nCatalogs: Cinemeta and Kitsu. Playback: AndroidX Media3 and VideoLAN LibVLC.", 13, muted), margin(-1, -2, bottom = 12))
        body.addView(button("Open-source notices") { AlertDialog.Builder(this).setTitle("Open-source notices").setMessage(assets.open("NOTICE.txt").bufferedReader().use { it.readText() }).setPositiveButton("Close", null).show() }, margin(230, 42))
    }

    private fun subtitleLanguageName(code: String): String = java.util.Locale.forLanguageTag(code)
        .getDisplayName(java.util.Locale.getDefault()).ifBlank { code }

    private fun subtitleSettings() {
        current = { subtitleSettings() }
        page("Subtitles", "Choose a preferred language. Local SRT files can be loaded from the player’s Subtitles menu.")
        body.addView(button("Preferred language · ${subtitleLanguageName(playbackPreferences.subtitleLanguage)}") {
            val common = listOf("en", "es", "fr", "de", "it", "pt", "pt-br", "ja", "ko", "zh", "ar", "hi")
            val languages = (common + java.util.Locale.getISOLanguages().sortedBy(::subtitleLanguageName)).distinct()
            AlertDialog.Builder(this).setTitle("Preferred subtitle language")
                .setSingleChoiceItems(languages.map { "${subtitleLanguageName(it)} ($it)" }.toTypedArray(), languages.indexOf(playbackPreferences.subtitleLanguage)) { dialog, index ->
                    playbackPreferences.subtitleLanguage = languages[index]; dialog.dismiss(); subtitleSettings()
                }.setNegativeButton("Cancel", null).show()
        }, margin(-1, 44, bottom = 12))
        listOf(Triple("OpenSubtitles", SubtitleSettings.OPEN_SUBTITLES_API_KEY, SubtitleProvider.OPEN_SUBTITLES),
            Triple("SubDL", SubtitleSettings.SUBDL_API_KEY, SubtitleProvider.SUBDL),
            Triple("SubSource", SubtitleSettings.SUBSOURCE_API_KEY, SubtitleProvider.SUBSOURCE)).forEach { (label, key, provider) ->
            section(label)
            body.addView(button("$label API key · ${if (store.has(key)) "saved" else "not configured"}") {
                inputSetting("$label API key", "Enter your own $label API key", "", true) { value ->
                    SubtitleSettings.saveCredentials(store, mapOf(key to value)); subtitleSettings()
                }
            }, margin(-1, 44, bottom = 7))
            if (label == "OpenSubtitles") {
                body.addView(text("Downloads require your OpenSubtitles account as well as an API key.", 12, muted), margin(-1, -2, bottom = 7))
                body.addView(button("OpenSubtitles account") {
                    inputSetting("OpenSubtitles username", "Your OpenSubtitles username", "") { username ->
                        inputSetting("OpenSubtitles password", "Your OpenSubtitles password", "", true, trimInput = false) { password ->
                            SubtitleSettings.saveCredentials(store, mapOf(SubtitleSettings.OPEN_SUBTITLES_USERNAME to username, SubtitleSettings.OPEN_SUBTITLES_PASSWORD to password))
                            subtitleSettings()
                        }
                    }
                }, margin(-1, 44, bottom = 7))
            }
            if (store.has(key)) body.addView(button("Clear $label connection") {
                confirm("Remove $label credentials from this device?") {
                    SubtitleSettings.clearConnection(store, provider)
                    subtitleSettings()
                }
            }, margin(-1, 42, bottom = 8))
        }
    }

    private fun providerAccounts() {
        current = { providerAccounts() }
        page("Playback accounts", "Choose your service. Accounts stay separate; selecting one does not disconnect another.")
        ProviderHub.SPECS.forEach { spec ->
            body.addView(button("${spec.label} · ${if (providers.connected(spec.id)) "connected" else "not linked"}${if (spec.experimental) " · experimental" else ""}  ›") {
                if (spec.id == "torbox") navigate { settings() } else navigate { providerSettings(spec.id) }
            }, margin(-1, 44, bottom = 8))
        }
    }

    private fun providerSettings(providerId: String) {
        current = { providerSettings(providerId) }
        val spec = ProviderHub.SPECS.first { it.id == providerId }
        page(spec.label, "Use your own ${spec.label} account. ${spec.credentialLabel} setup: ${spec.credentialUrl}")
        if (spec.experimental) body.addView(text("Easynews uses its member search service. Provider behavior may change; protected playback uses Media3.", 13, muted), margin(-1, -2, bottom = 10))
        body.addView(button("${if (providers.connected(providerId)) "Reconnect" else "Connect"} ${spec.label}") {
            val connect: (String) -> Unit = { username ->
                inputSetting("${spec.label} ${spec.credentialLabel}", "Enter your own ${spec.credentialLabel}", "", true, trimInput = providerId != "easynews") { token ->
                    load(generation, { providers.connect(providerId, token, username) }) {
                        toast("${spec.label} connected"); providerSettings(providerId); updateBadge()
                    }
                }
            }
            if (providerId == "easynews") inputSetting("Easynews username", "Your Easynews username", "", done = connect) else connect("")
        }, margin(-1, 44, bottom = 8))
        if (providers.connected(providerId)) {
            body.addView(button("Use ${spec.label} for playback") {
                playbackPreferences.providerId = providerId; updateBadge(); toast("${spec.label} selected for playback"); providerSettings(providerId)
            }, margin(-1, 44, bottom = 8))
            if (spec.cloud) body.addView(button("Browse ${spec.label} cloud") { navigate { providerCloud(providerId) } }, margin(-1, 44, bottom = 8))
            body.addView(button("Disconnect ${spec.label}") {
                confirm("Remove ${spec.label} credentials from this device?") { providers.disconnect(providerId); providerSettings(providerId); updateBadge() }
            }, margin(-1, 44, bottom = 8))
        }
    }

    private fun listConnectionsRow() {
        val row = horizontalRow()
        row.addView(button("Trakt library") { navigate { traktPage() } }, margin(190, 40, right = 10))
        row.addView(button("MDBList lists") { navigate { mdbPage() } }, margin(190, 40, right = 10))
        row.addView(button("Simkl library") { navigate { simklPage() } }, margin(190, 40, right = 10))
    }

    private fun mdbSettings() {
        current = { mdbSettings() }
        page("MDBList", "Use your own MDBList API key from mdblist.com/preferences. This connection is independent of Trakt.")
        body.addView(button(if (mdblist.connected()) "Replace MDBList API key" else "Connect MDBList") {
            inputSetting("MDBList API key", "Your MDBList API key", "", true) { key ->
                load(generation, { mdblist.connectApiKey(key) }) { AdditionalTracking.clear(this, "mdblist"); toast("MDBList connected"); mdbSettings() }
            }
        }, margin(-1, 44, bottom = 8))
        if (mdblist.connected()) {
            body.addView(button("Open MDBList library") { navigate { mdbPage() } }, margin(-1, 44, bottom = 8))
            trackingToggle("mdblist", "MDBList")
            body.addView(button("Disconnect MDBList") { confirm("Disconnect MDBList on this device?") {
                mdblist.disconnect(); AdditionalTracking.clear(this, "mdblist"); mdbSettings()
            } }, margin(-1, 44, bottom = 8))
        }
    }

    private fun simklSettings() {
        current = { simklSettings() }
        page("Simkl", "Link a separately registered Simkl AUTH V2 TV/device application. Trakt settings stay independent.")
        body.addView(button("Simkl app Client ID") {
            inputSetting("Simkl AUTH V2 Client ID", "Register a TV/device app at simkl.com/settings/developer", "") { value ->
                simkl.setClientId(value); AdditionalTracking.clear(this, "simkl"); remoteProgress.clear(); simklSettings()
            }
        }, margin(-1, 44, bottom = 8))
        body.addView(button(if (simkl.connected()) "Reconnect Simkl" else "Connect Simkl") {
            if (!simkl.configured()) toast("Enter your Simkl AUTH V2 app Client ID first")
            else authorize("Simkl", { simkl.startDeviceAuth() }, { simkl.pollDeviceAuth(it) }, cancel = { simkl.cancelDeviceAuth(it) })
        }, margin(-1, 44, bottom = 8))
        if (simkl.connected()) {
            body.addView(button("Open Simkl library") { navigate { simklPage() } }, margin(-1, 44, bottom = 8))
            trackingToggle("simkl", "Simkl")
            body.addView(button("Disconnect Simkl") { confirm("Disconnect Simkl on this device?") {
                simkl.disconnect(); AdditionalTracking.clear(this, "simkl"); remoteProgress.clear(); simklSettings()
            } }, margin(-1, 44, bottom = 8))
        }
    }

    private fun mdbPage() {
        current = { mdbPage() }; destination = "library"; refreshNavigation()
        val id = page("My MDBList", "Your watchlist and personal, liked, and external lists.")
        if (!mdblist.connected()) { body.addView(button("Connect MDBList") { navigate { mdbSettings() } }, margin(-1, 44)); return }
        val revision = mdblist.accountRevision()
        read(id, {
            check(mdblist.accountRevision() == revision) { "The MDBList account changed. Reopen this library." }
            Triple(mdblist.watchlist("movie"), mdblist.watchlist("series"), mdblist.lists()).also {
                check(mdblist.accountRevision() == revision) { "The MDBList account changed. Reopen this library." }
            }
        }) { (movies, shows, lists) ->
            if (mdblist.accountRevision() != revision) return@read
            mediaRow("Movie watchlist", movies); mediaRow("Show watchlist", shows)
            section("Lists")
            lists.forEach { list -> body.addView(button("${list.name} · ${list.kind}") { navigate {
                current = { mdbListItems(list.id, list.kind, list.name, revision) }; mdbListItems(list.id, list.kind, list.name, revision)
            } }, margin(-1, 44, bottom = 7)) }
            if (movies.isEmpty() && shows.isEmpty() && lists.isEmpty()) body.addView(text("Your MDBList library is empty.", 14, muted))
        }
    }

    private fun mdbListItems(listId: String, kind: String, title: String, accountRevision: String) {
        current = { mdbListItems(listId, kind, title, accountRevision) }
        val id = page(title)
        read(id, {
            check(mdblist.accountRevision() == accountRevision) { "The MDBList account changed. Reopen this library." }
            mdblist.listItems(listId, kind)
        }) { if (mdblist.accountRevision() == accountRevision) mediaGrid(it) }
    }

    private fun simklPage() {
        current = { simklPage() }; destination = "library"; refreshNavigation()
        val id = page("My Simkl", "Your movie/show watchlists and saved playback. Continue Watching removal stays local to POV.")
        if (!simkl.connected()) { body.addView(button("Connect Simkl") { navigate { simklSettings() } }, margin(-1, 44)); return }
        val revision = simkl.accountRevision()
        read(id, {
            check(simkl.accountRevision() == revision) { "The Simkl account changed. Reopen this library." }
            Triple(simkl.watchlist("movie"), simkl.watchlist("series"), simkl.paused()).also {
                check(simkl.accountRevision() == revision) { "The Simkl account changed. Reopen this library." }
            }
        }) { (movies, shows, progress) ->
            if (simkl.accountRevision() != revision) return@read
            val visible = progress.filterNot { history.isHidden(identity = it.identity) }
            if (visible.isNotEmpty()) section("Continue Watching")
            visible.forEach { saved ->
                remoteProgress[identityKey(saved.identity)] = saved.progress
                val action = { navigate { showSources(saved.identity) } }
                body.addView(button("${saved.identity.title} · ${saved.progress.toInt()}%", action).apply {
                    bindContinueOptions(this, "", saved.identity, saved.identity.title, action)
                }, margin(-1, 44, bottom = 7))
            }
            mediaRow("Movie watchlist", movies); mediaRow("Show watchlist", shows)
            body.addView(button("Custom lists · Simkl PRO/VIP") { navigate { simklLists() } }, margin(-1, 44, top = 12))
        }
    }

    private fun simklLists() {
        current = { simklLists() }; val id = page("Simkl custom lists", "Custom lists require Simkl PRO or VIP.")
        val revision = simkl.accountRevision()
        read(id, { simkl.customLists() }) { lists ->
            if (simkl.accountRevision() != revision) return@read
            lists.forEach { list -> body.addView(button(list.name) { navigate {
                current = { simklListItems(list.id, list.name, revision) }; simklListItems(list.id, list.name, revision)
            } }, margin(-1, 44, bottom = 7)) }
            if (lists.isEmpty()) body.addView(text("No custom lists found.", 14, muted))
        }
    }

    private fun simklListItems(listId: String, title: String, accountRevision: String) {
        current = { simklListItems(listId, title, accountRevision) }; val id = page(title)
        read(id, {
            check(simkl.accountRevision() == accountRevision) { "The Simkl account changed. Reopen this library." }
            simkl.listItems(listId)
        }) { if (simkl.accountRevision() == accountRevision) mediaGrid(it) }
    }

    private fun trackingToggle(providerId: String, label: String) {
        val enabled = AdditionalTracking.isEnabled(this, providerId)
        body.addView(button("$label playback tracking · ${if (enabled) "On" else "Off"}") {
            if (enabled) { AdditionalTracking.setEnabled(this, providerId, false); current() }
            else AlertDialog.Builder(this).setTitle("Enable $label playback tracking?")
                .setMessage("POV will send identified movie/episode IDs, playback percentage, and play/pause/stop events to your connected $label account. This can update its viewing history and resume progress. Trakt is independent. You can turn this off at any time.")
                .setPositiveButton("Enable tracking") { _, _ -> AdditionalTracking.setEnabled(this, providerId, true); current() }
                .setNegativeButton("Cancel", null).show()
        }, margin(-1, 44, bottom = 8))
    }

    private fun startTorBox() { authorize("TorBox", { torbox.startDeviceAuth() }, { torbox.pollDeviceAuth(it) }, cancel = { torbox.cancelDeviceAuth(it) }) }
    private fun startTrakt() {
        authorize("Trakt", { trakt.startDeviceAuth() }, { trakt.pollDeviceAuth(it) },
            cancel = { trakt.cancelDeviceAuth(it) }, configure = ::configureTraktClientId)
    }

    private fun authorize(name: String, start: () -> DeviceAuth, poll: (DeviceAuth) -> Boolean,
                          cancel: (DeviceAuth?) -> Unit = {}, configure: (() -> Unit)? = null) {
        if (authorization?.isShowing == true) return
        authorization = DeviceAuthorizationDialog(this, name, authWorker, start, poll, onConnected = {
            if (name == "Trakt") { history.clearPending(); remoteProgress.clear() }
            if (name == "Simkl") { AdditionalTracking.clear(this, "simkl"); remoteProgress.clear() }
            updateBadge(); toast("$name connected"); current()
        }, onCancel = cancel, configure = configure).also { it.show() }
    }

    private fun configureTraktClientId() {
        val content = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        content.addView(text("This preview needs a registered Trakt application Client ID to request an activation code. It is different from your Trakt username, password, or activation code.", 16, Color.LTGRAY), margin(-1, -2, bottom = 12))
        content.addView(text("Create or manage your application at developer.trakt.tv/apps.", 15, accent), margin(-1, -2, bottom = 12))
        val input = EditText(this).apply {
            hint = "Registered Trakt application Client ID"; setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val errorText = text("", 14, Color.rgb(255, 185, 170))
        try { input.setText(store.get("trakt.client_id")) } catch (_: Exception) { errorText.text = "The saved Client ID could not be read. Enter it again to replace it." }
        content.addView(input, margin(-1, -2)); content.addView(errorText, margin(-1, -2, top = 8))
        val dialog = AlertDialog.Builder(this).setTitle("Set up Trakt").setView(content)
            .setPositiveButton("Save & get code", null).setNegativeButton("Cancel", null).create()
        dialog.show()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val value = input.text.toString().trim()
            if (!value.matches(Regex("[A-Za-z0-9_-]{1,256}"))) {
                input.error = "Enter the Client ID from your registered Trakt application."
                input.requestFocus(); return@setOnClickListener
            }
            try {
                val changed = trakt.setClientId(value)
                if (changed) { history.clearPending(); remoteProgress.clear() }
                dialog.dismiss(); updateBadge(); startTrakt()
            } catch (_: Exception) {
                errorText.text = "Trakt setup could not be saved on this device. Check available storage and try again."
            }
        }
    }

    private fun retrySync() {
        worker.execute { try {
            val accountRevision = trakt.accountRevision()
            if (trakt.connected()) history.pending().forEach { event ->
                try {
                    if (trakt.progressForAccount(event.identity, event.percent, event.action, accountRevision)) {
                        history.acknowledge(event.identity, event.revision)
                    }
                } catch (_: Exception) { /* Retain for the next launch. */ }
            }
        } catch (_: Exception) { /* Account storage may be temporarily unavailable. */ } }
    }

    private fun identityKey(identity: PlaybackIdentity) = "${identity.imdbId}:${identity.season}:${identity.episode}"

    private fun inputSetting(title: String, hintText: String, initial: String, secret: Boolean = false, trimInput: Boolean = true, done: (String) -> Unit) {
        val input = EditText(this).apply { hint = hintText; setText(initial); setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD }
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(input).setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        if (secret) dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val value = input.text.toString().let { if (trimInput) it.trim() else it }
            if (value.isBlank()) { input.error = "Enter a value before saving."; return@setOnClickListener }
            try { done(value); dialog.dismiss() }
            catch (error: Exception) { input.error = uiErrorMessage(error); input.requestFocus() }
        }
    }

    private fun <T> read(id: Int, work: () -> T, done: (T) -> Unit) = load(id, work, readOnly = true, done = done)

    private fun <T> load(id: Int, work: () -> T, readOnly: Boolean = false, done: (T) -> Unit) {
        val progress = text("Loading…", 13, muted); body.addView(progress, margin(-1, -2, top = 12, bottom = 12))
        worker.execute {
            if (readOnly && id != generation) return@execute
            try { val result = work(); runOnUiThread { if (id == generation && !isFinishing && !isDestroyed) { body.removeView(progress); done(result); focusAfterRender(id) } } }
            catch (error: Exception) { runOnUiThread { if (id == generation && !isFinishing && !isDestroyed) { body.removeView(progress); showError(error); focusAfterRender(id) } } }
        }
    }

    private fun showError(error: Exception) {
        val message = uiErrorMessage(error)
        body.addView(text(message, 14, if (theme == ThemeSpec.EDITORIAL) Color.rgb(150, 37, 28) else Color.rgb(255, 185, 170)), margin(-1, -2, top = 12, bottom = 10))
        body.addView(button("Try again") { current() }, margin(150, 40, bottom = 12))
    }
    private fun cardWidth(): Int {
        val visible = when (theme) { ThemeSpec.CINEMATIC -> 4; ThemeSpec.DASHBOARD -> 3; else -> 5 }
        val available = 960 - theme.layout.railWidthDp - theme.layout.contentInsetDp * 2 - 13
        return minOf(theme.layout.cardWidthDp, (available - (visible - 1) * theme.layout.cardGapDp) / visible)
    }

    private fun section(title: String) {
        body.addView(text(title, theme.layout.sectionTitleSizeSp, ink, true), margin(-1, -2, top = if (isHome && body.childCount == 1) 3 else 11, bottom = 7))
    }
    private fun mediaRow(title: String, items: List<MediaItemData>) {
        if (items.isEmpty()) return
        section(title); val row = horizontalRow()
        items.take(20).forEach { row.addView(card(it).apply { tag = "row:$title:${it.type}:${it.imdbId}" }, margin(cardWidth(), theme.layout.cardHeightDp, right = theme.layout.cardGapDp)) }
    }
    private fun mediaGrid(items: List<MediaItemData>) {
        if (items.isEmpty()) { body.addView(text("No titles found. Try a different search.", 14, muted)); return }
        val width = 960 - theme.layout.railWidthDp - theme.layout.contentInsetDp * 2
        val count = (width / (cardWidth() + theme.layout.cardGapDp)).coerceAtLeast(1)
        items.chunked(count).forEach { chunk ->
            val row = LinearLayout(this); body.addView(row, margin(-1, -2, top = 14))
            chunk.forEach { row.addView(card(it), margin(cardWidth(), theme.layout.cardHeightDp, right = theme.layout.cardGapDp)) }
        }
    }
    private fun card(item: MediaItemData): View = tile(item) { navigate { details(item) } }

    private fun tile(item: MediaItemData, progress: Double? = null, caption: String = "", action: () -> Unit): View {
        val shape = theme.layout.cards
        val frame = FrameLayout(this).apply {
            background = rounded(panel, theme.layout.cornerRadiusDp); isFocusable = true; isFocusableInTouchMode = true; isClickable = true
            contentDescription = listOf(item.title, item.year, caption).filter(String::isNotBlank).joinToString(", ")
            tag = "media:${item.type}:${item.imdbId.ifBlank { item.title }}"
        }
        val artFrame = FrameLayout(this).apply { background = rounded(panel, theme.layout.cornerRadiusDp); clipToOutline = true }
        val art = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        artFrame.addView(art, FrameLayout.LayoutParams(-1, -1))
        val fallback = if (item.imdbId.matches(Regex("tt\\d+"))) "https://images.metahub.space/${if (shape == CardFormat.POSTER) "poster" else "background"}/medium/${item.imdbId}/img" else ""
        image(art, (if (shape == CardFormat.POSTER) item.poster else item.background).ifBlank { fallback.ifBlank { item.poster } })
        artFrame.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(0xE8000000.toInt(), 0x08000000, Color.TRANSPARENT)) }, FrameLayout.LayoutParams(-1, -1))
        val labels = column().apply { setPadding(dp(10), dp(5), dp(9), dp(if (progress != null) 17 else 9)) }
        labels.addView(text(item.title, if (theme == ThemeSpec.DASHBOARD) 14 else 12, Color.WHITE, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        val secondary = caption.ifBlank { item.year }
        if (secondary.isNotBlank() && (shape == CardFormat.LANDSCAPE || theme == ThemeSpec.DASHBOARD)) labels.addView(text(secondary, 10, 0xFFCFD8E4.toInt()), margin(-1, -2, top = 2))
        artFrame.addView(labels, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        frame.addView(artFrame, FrameLayout.LayoutParams(-1, -1))
        if (progress != null) {
            val track = FrameLayout(this).apply { background = rounded(0xFF34424D.toInt(), 3) }
            track.addView(View(this).apply { background = rounded(theme.palette.progress, 3) }, FrameLayout.LayoutParams(0, -1))
            frame.addView(track, FrameLayout.LayoutParams(-1, dp(4), Gravity.BOTTOM).apply { leftMargin = dp(9); rightMargin = dp(9); bottomMargin = dp(6) })
            track.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                val targetWidth = (track.width * progress.coerceIn(0.0, 1.0)).roundToInt()
                val fill = track.getChildAt(0)
                if (fill.layoutParams.width != targetWidth) fill.layoutParams = fill.layoutParams.apply { width = targetWidth }
            }
        }
        frame.foreground = FocusRingDrawable(theme.palette.border, dp(theme.layout.cornerRadiusDp).toFloat(), false, dp(1).toFloat())
        frame.setOnFocusChangeListener { view, focused ->
            view.foreground = FocusRingDrawable(if (focused) accent else theme.palette.border, dp(theme.layout.cornerRadiusDp).toFloat(), focused, dp(1).toFloat())
            view.animate().scaleX(if (focused) theme.layout.focusScale else 1f).scaleY(if (focused) theme.layout.focusScale else 1f).setDuration(130).start()
        }
        frame.setOnClickListener { action() }
        return frame
    }

    private fun image(view: ImageView, address: String) {
        if (!address.startsWith("https://")) return
        if (view.tag != address) view.setImageDrawable(null)
        view.tag = address
        bitmapCache.get(address)?.let { view.setImageBitmap(it); return }
        images.execute {
            try {
                val connection = URL(address).openConnection() as HttpsURLConnection
                connection.connectTimeout = 12_000; connection.readTimeout = 12_000
                val bitmap = try { connection.inputStream.use { stream -> BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inSampleSize = if (view === backdropArt || view.width >= dp(350)) 1 else 2 }) } } finally { connection.disconnect() }
                if (bitmap != null) { bitmapCache.put(address, bitmap); runOnUiThread { if (!isDestroyed && view.tag == address) view.setImageBitmap(bitmap) } }
            } catch (_: Exception) { /* The title remains usable when artwork is unavailable. */ }
        }
    }

    private fun horizontalRow(): LinearLayout {
        val scroller = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; isFocusable = false; isFocusableInTouchMode = false; descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS }
        val row = LinearLayout(this).apply { setPadding(dp(3), dp(4), dp(10), dp(4)); clipChildren = false; clipToPadding = false }
        scroller.addView(row); body.addView(scroller, margin(-1, -2)); return row
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun text(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(size).toFloat()); setTextColor(color)
        typeface = if (theme.isConcept) ConceptUi(this@MainActivity, theme).font(if (bold) 650 else 400)
            else Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        includeFontPadding = false
    }
    private fun button(label: String, action: () -> Unit): TextView = text(label, 13, ink, true).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(5), dp(12), dp(5)); background = rounded(panel, 7)
        isFocusable = true; isFocusableInTouchMode = true; isClickable = true; contentDescription = label; tag = "action:$label"
        setOnFocusChangeListener { view, focus ->
            view.background = rounded(if (focus) accent else panel, 7)
            view.foreground = if (focus) FocusRingDrawable(accent, dp(7).toFloat(), true, dp(1).toFloat()) else null
            setTextColor(if (focus) Color.rgb(4, 15, 24) else ink)
        }
        setOnClickListener { action() }
    }
    private fun primaryButton(label: String, action: () -> Unit): TextView = text(label, 15, theme.palette.buttonText, true).apply {
        tag = "primary:$label"
        gravity = Gravity.CENTER; isFocusable = true; isFocusableInTouchMode = true; isClickable = true
        background = rounded(theme.palette.primaryButton, if (theme == ThemeSpec.EDITORIAL || theme == ThemeSpec.NEON) 23 else 6)
        setOnFocusChangeListener { view, focused ->
            view.foreground = if (focused) FocusRingDrawable(if (theme == ThemeSpec.NEON) theme.palette.secondaryAccent else accent,
                dp(if (theme == ThemeSpec.EDITORIAL || theme == ThemeSpec.NEON) 23 else 6).toFloat(), true, dp(1).toFloat()) else null
        }
        setOnClickListener { action() }
    }
    private fun rounded(color: Int, radius: Int, stroke: Int = Color.TRANSPARENT) = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(color); cornerRadius = dp(radius).toFloat(); if (stroke != Color.TRANSPARENT) setStroke(dp(2), stroke) }
    private fun withAlpha(color: Int, alpha: Int) = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    private fun margin(width: Int, height: Int, top: Int = 0, bottom: Int = 0, right: Int = 0) = LinearLayout.LayoutParams(if (width < 0) width else dp(width), if (height < 0) height else dp(height)).apply { setMargins(0, dp(top), dp(right), dp(bottom)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.widthPixels / 960f).roundToInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun confirm(message: String, action: () -> Unit) { AlertDialog.Builder(this).setMessage(message).setPositiveButton("Continue") { _, _ -> action() }.setNegativeButton("Cancel", null).show() }
}
