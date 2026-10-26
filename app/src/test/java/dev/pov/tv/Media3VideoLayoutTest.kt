package dev.pov.tv

import android.os.Looper
import android.graphics.Rect
import android.view.ContextThemeWrapper
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.VideoSize
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSurfaceView

/** Real PlayerView/AspectRatioFrameLayout ordering, without a codec or Surface renderer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1920dp-h1080dp-land-mdpi", shadows = [Media3VideoLayoutTest.FrameSurfaceShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class Media3VideoLayoutTest {
    private val size = VideoSize(450, 360)

    @Test fun `unprimed PlayerView binds full surface before its known video ratio resizes that surface`() {
        screen(1920, 1080, size).use { screen ->
            assertEquals(1920 to 1080, screen.surfaceSize())
            screen.view.player = screen.player

            assertEquals(listOf(1920 to 1080), screen.player.boundSizes)
            screen.layout()
            assertEquals(1350 to 1080, screen.surfaceSize())
            assertNotEquals(screen.player.boundSizes.single(), screen.surfaceSize())
        }
    }

    @Test fun `primed fullscreen binds final ratio geometry and refuses stale surface frame dimensions`() {
        screen(1920, 1080, size).use { screen ->
            Media3VideoLayout.prime(screen.view, size)
            screen.layout()
            assertEquals(1350 to 1080, screen.surfaceSize())
            screen.view.player = screen.player
            screen.layout()

            assertEquals(listOf(1350 to 1080), screen.player.boundSizes)
            assertEquals(screen.player.boundSizes.single(), screen.surfaceSize())
            // Model surfaceChanged buffer geometry independently of actual PlayerView layout.
            // Robolectric's stock FakeSurfaceHolder returns null for getSurfaceFrame; this test's
            // shadow supplies the stable mutable Rect exposed by an Android SurfaceHolder.
            val frame = screen.surface.holder.surfaceFrame
            frame.set(0, 0, 1920, 1080)
            assertFalse(Media3VideoLayout.geometryMatchesSurface(screen.view))
            frame.set(0, 0, 1350, 1080)
            assertTrue(Media3VideoLayout.geometryMatchesSurface(screen.view))
            screen.surface.requestLayout()
            assertFalse(Media3VideoLayout.geometryMatchesSurface(screen.view))
        }
    }

    @Test fun `primed mini target keeps fitted dimensions when PlayerView takes ownership`() {
        screen(494, 290, size).use { screen ->
            Media3VideoLayout.prime(screen.view, size)
            screen.layout()
            val fitted = screen.surfaceSize()
            assertEquals(362 to 290, fitted)
            screen.view.player = screen.player
            screen.layout()

            assertEquals(listOf(fitted), screen.player.boundSizes)
            assertEquals(fitted, screen.surfaceSize())
        }
    }

    @Test fun `non square pixel ratio is primed with the same geometry PlayerView applies`() {
        val nonSquare = VideoSize(720, 576, 0, 1.2f)
        screen(1920, 1080, nonSquare).use { screen ->
            Media3VideoLayout.prime(screen.view, nonSquare)
            screen.layout()
            val fitted = screen.surfaceSize()
            assertEquals(1620 to 1080, fitted)
            screen.view.player = screen.player
            screen.layout()

            assertEquals(listOf(fitted), screen.player.boundSizes)
            assertEquals(fitted, screen.surfaceSize())
        }
    }

    private fun screen(width: Int, height: Int, videoSize: VideoSize): Screen {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme)
        val view = PlayerView(context).apply { useController = false }
        val root = FrameLayout(context).apply { addView(view, FrameLayout.LayoutParams(-1, -1)) }
        return Screen(root, view, RecordingPlayer(videoSize), width, height).apply { layout() }
    }

    private class Screen(val root: FrameLayout, val view: PlayerView, val player: RecordingPlayer,
        val width: Int, val height: Int) : AutoCloseable {
        val surface get() = view.videoSurfaceView as SurfaceView
        fun surfaceSize() = surface.width to surface.height
        fun layout() {
            repeat(2) {
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, height)
                shadowOf(Looper.getMainLooper()).idle()
            }
        }
        override fun close() { view.player = null; player.release(); shadowOf(Looper.getMainLooper()).idle() }
    }

    private class RecordingPlayer(private val size: VideoSize) : SimpleBasePlayer(Looper.getMainLooper()) {
        val boundSizes = mutableListOf<Pair<Int, Int>>()
        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().add(Player.COMMAND_SET_VIDEO_SURFACE).add(Player.COMMAND_RELEASE).build())
            .setVideoSize(size).build()
        override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> {
            val surface = videoOutput as SurfaceView
            boundSizes += surface.width to surface.height
            return Futures.immediateVoidFuture()
        }
        override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> = Futures.immediateVoidFuture()
        override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()
    }

    @Implements(SurfaceView::class)
    class FrameSurfaceShadow : ShadowSurfaceView() {
        private val framedHolder = object : ShadowSurfaceView.FakeSurfaceHolder() {
            private val frame = Rect()
            override fun getSurfaceFrame(): Rect = frame
        }
        @Implementation
        override fun getHolder(): SurfaceHolder = framedHolder
    }
}
