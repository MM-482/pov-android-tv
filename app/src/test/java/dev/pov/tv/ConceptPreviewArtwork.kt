package dev.pov.tv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Offline visual-QA media only. Source rectangles deliberately omit baked titles,
 * buttons, progress bars, navigation, branding and system chrome. The original
 * PNGs remain untouched; Android draws the sampled artwork at render time.
 * This class lives in test sources and cannot ship inside the production APK.
 */
internal class ConceptPreviewArtwork(private val root: File?) : AutoCloseable {
    private data class Source(val file: File, val bitmap: Bitmap, val sha256: String, val family: String)
    private data class Region(val family: String, val bounds: Rect)
    private val sources = linkedMapOf<String, Source>()
    private val samples = linkedMapOf<String, JSONObject>()
    val usesReferenceArtwork: Boolean get() = root != null

    fun drawable(theme: ThemeSpec, address: String, viewFamily: String): Drawable {
        val role = address.substringAfterLast(':')
        val region = region(theme, role, viewFamily)
        val source = source(theme, region.family) ?: return IllustrativeArtwork(theme, role.hashCode())
        require(Rect(0, 0, source.bitmap.width, source.bitmap.height).contains(region.bounds)) {
            "Artwork rectangle ${region.bounds} must lie inside ${source.file.name}"
        }
        samples["${theme.id}:${region.family}:$role"] = JSONObject()
            .put("theme", theme.displayName).put("media_role", role).put("reference_family", region.family)
            .put("source_file", source.file.name).put("source_sha256", source.sha256)
            .put("source_px", dimensions(source.bitmap.width, source.bitmap.height))
            .put("sample_rect_px", JSONArray(listOf(region.bounds.left, region.bounds.top, region.bounds.right, region.bounds.bottom)))
            .put("content", "Text-free illustrative artwork only; native UI is rendered independently")
        return ReferenceArtworkDrawable(source.bitmap, region.bounds)
    }

    fun referenceRecord(theme: ThemeSpec, family: String): JSONObject {
        val source = source(theme, family)
        val expectedWidth = if (family == "tv") 1672 else if (theme == ThemeSpec.GLASS) 852 else 830
        val expectedHeight = if (family == "tv") 941 else if (theme == ThemeSpec.GLASS) 1846 else 1896
        return JSONObject().put("kind", "Uploaded 2026-11-02 design concept; not an implemented app screenshot")
            .put("available", source != null).put("file", source?.file?.name ?: filename(theme, family))
            .put("pixel_canvas", dimensions(source?.bitmap?.width ?: expectedWidth, source?.bitmap?.height ?: expectedHeight))
            .put("sha256", source?.sha256 ?: JSONObject.NULL)
    }

    fun sourceRecords(): JSONArray = JSONArray(sources.values.map { source ->
        JSONObject().put("file", source.file.name).put("reference_family", source.family)
            .put("sha256", source.sha256).put("source_px", dimensions(source.bitmap.width, source.bitmap.height))
            .put("sampled_artwork", JSONArray(samples.values.filter { it.getString("source_file") == source.file.name }))
    })

    private fun source(theme: ThemeSpec, family: String): Source? {
        if (root == null) return null
        val folder = if (family == "tv") "cameo-android-concepts-2026-11-02" else "cameo-mobile-concepts-2026-11-02"
        val relative = "$folder/${filename(theme, family)}"
        return sources.getOrPut(relative) {
            val file = File(root, relative)
            require(file.isFile) { "Missing optional reference artwork: $relative" }
            val bytes = file.readBytes()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inScaled = false })) { "Cannot decode reference $relative" }
            bitmap.density = Bitmap.DENSITY_NONE
            val assets = JSONObject(File(root, "$folder/assets.json").readText().removePrefix("\uFEFF")).getJSONArray("assets")
            val asset = (0 until assets.length()).map { assets.getJSONObject(it) }.single { it.getString("file") == file.name }
            require(asset.getInt("width") == bitmap.width && asset.getInt("height") == bitmap.height) {
                "Reference image dimensions differ from original assets.json: $relative"
            }
            if (asset.has("sha256")) require(asset.getString("sha256").equals(hash, ignoreCase = true)) {
                "Reference image hash differs from original assets.json: $relative"
            }
            Source(file, bitmap, hash, family)
        }
    }

    private fun filename(theme: ThemeSpec, family: String): String {
        val number = when (theme) {
            ThemeSpec.GLASS -> "01"; ThemeSpec.PREMIERE -> "02"; ThemeSpec.ESSENTIAL -> "03"
            ThemeSpec.ATELIER -> "04"; ThemeSpec.HORIZON -> "05"
            else -> error("Only the five supplied Cameo concepts have reference fixtures")
        }
        return "$number-cameo-${if (family == "phone") "mobile-" else ""}${theme.displayName.lowercase(Locale.ROOT)}.png"
    }

    private fun region(theme: ThemeSpec, role: String, viewFamily: String): Region {
        if (role == "hero" && viewFamily != "tv") return Region("phone", when (theme) {
            ThemeSpec.GLASS -> Rect(0, 236, 660, 544)
            ThemeSpec.PREMIERE -> Rect(0, 234, 760, 606)
            ThemeSpec.ESSENTIAL -> Rect(26, 292, 803, 597)
            ThemeSpec.ATELIER -> Rect(26, 273, 801, 653)
            ThemeSpec.HORIZON -> Rect(0, 239, 830, 635)
            else -> error("Unsupported preview theme")
        })
        if (role == "hero") return Region("tv", when (theme) {
            ThemeSpec.GLASS -> Rect(850, 85, 1650, 420)
            ThemeSpec.PREMIERE -> Rect(540, 90, 1650, 440)
            ThemeSpec.ESSENTIAL -> Rect(830, 70, 1640, 382)
            ThemeSpec.ATELIER -> Rect(568, 104, 1620, 394)
            ThemeSpec.HORIZON -> Rect(660, 108, 1618, 405)
            else -> error("Unsupported preview theme")
        })
        if (role == "sky") return Region("tv", when (theme) {
            ThemeSpec.GLASS -> Rect(1110, 790, 1352, 898)
            ThemeSpec.PREMIERE -> Rect(1005, 770, 1295, 877)
            ThemeSpec.ESSENTIAL -> Rect(1120, 770, 1370, 883)
            ThemeSpec.ATELIER -> Rect(1010, 777, 1300, 880)
            ThemeSpec.HORIZON -> Rect(1005, 775, 1300, 880)
            else -> error("Unsupported preview theme")
        })
        val roleIndex = listOf("silent", "afterlight", "wild", "city").indexOf(role)
        require(roleIndex >= 0) { "Unknown synthetic preview role: $role" }
        val regions = when (theme) {
            ThemeSpec.GLASS -> listOf(Rect(38, 909, 418, 1042), Rect(445, 909, 820, 1042), Rect(34, 1362, 414, 1645), Rect(438, 1362, 820, 1645))
            ThemeSpec.PREMIERE -> listOf(Rect(24, 973, 405, 1120), Rect(433, 973, 804, 1120), Rect(25, 1480, 403, 1720), Rect(434, 1470, 806, 1720))
            ThemeSpec.ESSENTIAL -> listOf(Rect(29, 958, 403, 1090), Rect(431, 958, 801, 1090), Rect(29, 1408, 401, 1725), Rect(431, 1408, 801, 1725))
            ThemeSpec.ATELIER -> listOf(Rect(27, 1015, 401, 1116), Rect(430, 1015, 800, 1116), Rect(30, 1480, 401, 1710), Rect(431, 1475, 800, 1710))
            ThemeSpec.HORIZON -> listOf(Rect(35, 959, 402, 1102), Rect(433, 959, 798, 1102), Rect(37, 1408, 401, 1705), Rect(436, 1408, 794, 1705))
            else -> error("Unsupported preview theme")
        }
        return Region("phone", regions[roleIndex])
    }

    override fun close() { sources.values.forEach { it.bitmap.recycle() }; sources.clear() }

    private fun dimensions(width: Int, height: Int) = JSONObject().put("width", width).put("height", height)

    private class ReferenceArtworkDrawable(private val image: Bitmap, private val source: Rect) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        override fun getIntrinsicWidth(): Int = source.width()
        override fun getIntrinsicHeight(): Int = source.height()
        override fun draw(canvas: Canvas) { canvas.drawBitmap(image, source, bounds, paint) }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
        @Deprecated("Deprecated in Drawable") override fun getOpacity(): Int = PixelFormat.OPAQUE
    }

    /** Distinct fallback illustration when optional original concept files are absent. */
    private class IllustrativeArtwork(private val theme: ThemeSpec, private val seed: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun getIntrinsicWidth(): Int = 1200
        override fun getIntrinsicHeight(): Int = 800
        override fun draw(canvas: Canvas) {
            val save = canvas.save()
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            canvas.scale(bounds.width() / 1200f, bounds.height() / 800f)
            val sky = when (theme) {
                ThemeSpec.PREMIERE -> 0xFFB97948.toInt()
                ThemeSpec.HORIZON -> 0xFF665C9A.toInt()
                else -> 0xFF668BAA.toInt()
            }
            paint.shader = LinearGradient(0f, 0f, 0f, 800f, sky, theme.palette.background, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, 1200f, 800f, paint); paint.shader = null
            paint.color = Color.argb(90, 190, 220, 243); canvas.drawCircle(850f, 190f, 240f, paint)
            paint.color = 0xFF253B4B.toInt()
            val mountain = Path().apply {
                moveTo(0f, 520f); lineTo(180f, 285f); lineTo(330f, 470f); lineTo(540f, 350f)
                lineTo(720f, 520f); lineTo(960f, 400f); lineTo(1200f, 520f)
                lineTo(1200f, 800f); lineTo(0f, 800f); close()
            }
            canvas.drawPath(mountain, paint)
            paint.color = 0xFF102736.toInt(); canvas.drawRect(0f, 600f, 1200f, 800f, paint)
            paint.color = Color.argb(100, 165, 197, 214); paint.strokeWidth = 3f
            repeat(8) { index -> canvas.drawLine(0f, 630f + index * 22, 1200f, 615f + index * 22, paint) }
            val x = 280f + (seed and 127)
            paint.color = 0xFFB4C2C6.toInt(); canvas.drawCircle(x, 420f, 25f, paint)
            canvas.drawRoundRect(RectF(x - 28, 446f, x + 28, 545f), 12f, 12f, paint)
            canvas.drawRect(x - 25, 535f, x - 4, 610f, paint); canvas.drawRect(x + 4, 535f, x + 25, 610f, paint)
            paint.color = 0xFF253B4B.toInt(); canvas.drawRoundRect(RectF(x - 18, 405f, x + 20, 427f), 9f, 9f, paint)
            canvas.restoreToCount(save)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
        @Deprecated("Deprecated in Drawable") override fun getOpacity(): Int = PixelFormat.OPAQUE
    }
}
