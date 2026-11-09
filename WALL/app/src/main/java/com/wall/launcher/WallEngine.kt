package com.wall.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Core Artistic & Procedural Synthesis Engine for WALL.
 *
 * STRICT PALETTE ENFORCEMENT (5 Spray Cans + Chalk White + Warm Concrete):
 * - Spray Red:      #D8342A (Notifications & Scrub Uninstall ONLY)
 * - Mustard Yellow: #D9A227 (Zone 1 Primary: Stencil Clock & Tape Accents)
 * - Turquoise:      #1F7A72 (Zone 2 Secondary: Stencil Rat Easter Egg & Termux Mark)
 * - Indigo:         #2B4C8C (Zone 1 Secondary: System Telemetry Stencil)
 * - Magenta:        #C0436B (Zone 3 Accent: Stencil Cutout Template & Peel Marks)
 * - Chalk White:    #F2EFE9 (Highlights & Dark Icon Stencil Transfer)
 */
object WallPalette {
    const val SPRAY_RED_INT = 0xFFD8342A.toInt()
    const val MUSTARD_YELLOW_INT = 0xFFD9A227.toInt()
    const val TURQUOISE_INT = 0xFF1F7A72.toInt()
    const val INDIGO_INT = 0xFF2B4C8C.toInt()
    const val MAGENTA_INT = 0xFFC0436B.toInt()
    const val CHALK_WHITE_INT = 0xFFF2EFE9.toInt()

    val SprayRed = androidx.compose.ui.graphics.Color(0xFFD8342A)
    val MustardYellow = androidx.compose.ui.graphics.Color(0xFFD9A227)
    val Turquoise = androidx.compose.ui.graphics.Color(0xFF1F7A72)
    val Indigo = androidx.compose.ui.graphics.Color(0xFF2B4C8C)
    val Magenta = androidx.compose.ui.graphics.Color(0xFFC0436B)
    val ChalkWhite = androidx.compose.ui.graphics.Color(0xFFF2EFE9)

    val ConcreteBase = androidx.compose.ui.graphics.Color(0xFFCFC8BA)
    val ConcreteCrevice = androidx.compose.ui.graphics.Color(0xFF7D7567)
    val ConcreteDeepShadow = androidx.compose.ui.graphics.Color(0xFF3E3A33)
    val KraftPaperBase = androidx.compose.ui.graphics.Color(0xFFB89668)
    val KraftPaperDark = androidx.compose.ui.graphics.Color(0xFF947449)
    val MaskingTapeCream = androidx.compose.ui.graphics.Color(0xFFE5DEC9)
    val MarkerInk = androidx.compose.ui.graphics.Color(0xFF23211E)
}

data class SprayedIconAsset(
    val normalBitmap: ImageBitmap,
    val ghostBitmap: ImageBitmap,
    val dominantColor: Int,
    val tiltDegrees: Float
)

object WallEngine {

    // Cached single procedural concrete wall bitmap (RGB_565 = ~1.8 MB in RAM!)
    @Volatile
    private var cachedConcreteWall: ImageBitmap? = null

    @Volatile
    private var cachedRatStencil: ImageBitmap? = null

    @Volatile
    private var cachedBalloonStencil: ImageBitmap? = null

    /** Full-bleed stamp canvas: 408px so a 78dp slot on xxxhdpi gets a true
     *  1:1 pixel source instead of an upscaled 148px blur. */
    private const val ICON_CANVAS_PX = 408

    // Process-wide cache for pre-rendered spray-painted app icons, sized in KB:
    // each asset holds two ARGB_8888 bitmaps (~1.3MB at 408px), cap ~24MB.
    private val iconCache = object : LruCache<String, SprayedIconAsset>(24 * 1024) {
        override fun sizeOf(key: String, value: SprayedIconAsset): Int =
            (ICON_CANVAS_PX * ICON_CANVAS_PX * 8) / 1024
    }

    /**
     * Fast deterministic 2D hash for procedural concrete texture & spatter generation.
     */
    private fun hash2D(x: Int, y: Int, seed: Int = 1337): Float {
        var n = x * 374761393 + y * 668265263 + seed * 1442695041
        n = (n xor (n ushr 13)) * 1274126177
        n = n xor (n ushr 16)
        return (n and 0x7fffffff).toFloat() / 2147483647.0f
    }

    private fun valueNoise(x: Float, y: Float, seed: Int): Float {
        val ix = floor(x).toInt()
        val iy = floor(y).toInt()
        val fx = x - ix
        val fy = y - iy
        val ux = fx * fx * (3f - 2f * fx)
        val uy = fy * fy * (3f - 2f * fy)

        val a = hash2D(ix, iy, seed)
        val b = hash2D(ix + 1, iy, seed)
        val c = hash2D(ix, iy + 1, seed)
        val d = hash2D(ix + 1, iy + 1, seed)

        return a + (b - a) * ux + (c - a) * uy + (a - b - c + d) * ux * uy
    }

    private fun fbm(x: Float, y: Float, seed: Int): Float {
        var v = 0f
        var amp = 0.5f
        var freq = 1f
        for (i in 0 until 4) {
            v += amp * valueNoise(x * freq, y * freq, seed + i * 97)
            freq *= 2.07f
            amp *= 0.5f
        }
        return v
    }

    /**
     * Generates the sun-bleached warm concrete wall once and caches it.
     * Uses RGB_565 at 960x600 (16:10 Mi Pad 5 Pro aspect ratio) to stay strictly under RAM budget
     * while rendering rich trowel ridges, water stains, aggregate pits, and faint weathered paint.
     */
    fun getOrGenerateConcreteWall(): ImageBitmap {
        cachedConcreteWall?.let { return it }
        synchronized(this) {
            cachedConcreteWall?.let { return it }

            val width = 960
            val height = 600
            val pixels = IntArray(width * height)

            // Directional light from top-left creates subtle plaster relief across the slab
            for (y in 0 until height) {
                val ny = y.toFloat() / height
                for (x in 0 until width) {
                    val nx = x.toFloat() / width

                    // Macro concrete undulation & damp water stains
                    val macro = fbm(nx * 4.5f, ny * 3.0f, 101)
                    val medium = fbm(nx * 18f + macro * 0.8f, ny * 12f, 202)
                    val fine = valueNoise(nx * 95f, ny * 65f, 303)
                    val microGrain = (hash2D(x, y, 404) - 0.5f) * 0.08f

                    // Trowel sweep marks & horizontal formwork seams
                    val seamDist = abs((ny * 3.0f) - floor(ny * 3.0f + 0.5f))
                    val seamFactor = if (seamDist < 0.012f) -0.045f * (1f - seamDist / 0.012f) else 0f

                    // Pitted concrete pockets revealing darker inner aggregate
                    val pitNoise = hash2D(x / 2, y / 2, 505)
                    val pit = if (pitNoise > 0.986f && medium < 0.48f) -0.13f else 0f

                    // Water drip streaks from top edge
                    val dripColumn = valueNoise(nx * 28f, 0.5f, 606)
                    val dripStain = if (ny < dripColumn * 0.45f) {
                        -0.035f * (1f - ny / (dripColumn * 0.45f))
                    } else 0f

                    // Top-left sunlight gradient across the wall
                    val sunFalloff = (1f - (nx * 0.45f + ny * 0.55f)) * 0.045f

                    val shade = (macro * 0.45f + medium * 0.35f + fine * 0.20f) +
                            microGrain + seamFactor + pit + dripStain + sunFalloff

                    // Warm sun-bleached plaster base RGB: (#CFC8BA)
                    var r = (204 + (shade - 0.5f) * 78f).toInt()
                    var g = (197 + (shade - 0.5f) * 74f).toInt()
                    var b = (182 + (shade - 0.5f) * 68f).toInt()

                    // Faint sun-bleached traces of ancient turquoise/mustard chalk under-layers
                    if (macro > 0.68f && medium > 0.58f) {
                        r += 4
                        g += 2
                        b -= 3
                    }

                    r = r.coerceIn(110, 236)
                    g = g.coerceIn(104, 230)
                    b = b.coerceIn(92, 215)

                    pixels[y * width + x] = Color.rgb(r, g, b)
                }
            }

            // Use RGB_565 so the 960x600 wall consumes only 1.15 MB of heap memory
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            bmp.setPixels(pixels, 0, width, 0, 0, width, height)
            val imageBmp = bmp.asImageBitmap()
            cachedConcreteWall = imageBmp
            return imageBmp
        }
    }

    /**
     * Generates an original stencil rat artwork in Turquoise (#1F7A72) for the corner Easter Egg.
     * Inspired by street stencil language, 100% original procedural vector silhouette.
     */
    fun getOrGenerateTurquoiseRatStencil(): ImageBitmap {
        cachedRatStencil?.let { return it }
        synchronized(this) {
            cachedRatStencil?.let { return it }
            val size = 128
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = WallPalette.TURQUOISE_INT
                style = Paint.Style.FILL
            }

            // Faint turquoise overspray halo
            val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    size * 0.52f, size * 0.62f, size * 0.42f,
                    Color.argb(42, 31, 122, 114),
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawCircle(size * 0.52f, size * 0.62f, size * 0.42f, haloPaint)

            // crouched street rat silhouette looking toward the terminal
            canvas.drawOval(RectF(36f, 64f, 88f, 98f), paint) // arched body
            canvas.drawOval(RectF(74f, 54f, 104f, 78f), paint) // head/snout
            canvas.drawCircle(76f, 50f, 9f, paint) // left ear
            canvas.drawCircle(86f, 52f, 7f, paint) // right ear
            canvas.drawRect(44f, 94f, 53f, 106f, paint) // back foot
            canvas.drawRect(76f, 92f, 84f, 106f, paint) // front foot

            // Whipping tail
            val tailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = WallPalette.TURQUOISE_INT
                style = Paint.Style.STROKE
                strokeWidth = 5f
                strokeCap = Paint.Cap.ROUND
            }
            canvas.drawArc(RectF(10f, 68f, 48f, 108f), 90f, 170f, false, tailPaint)

            // Cutout stencil bridges & eye in concrete transparency
            val bridgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                color = Color.BLACK
                style = Paint.Style.STROKE
                strokeWidth = 3.2f
            }
            canvas.drawCircle(92f, 63f, 2.5f, bridgePaint.apply { style = Paint.Style.FILL })
            bridgePaint.style = Paint.Style.STROKE
            // Stencil separation bridges across body and neck
            canvas.drawLine(72f, 56f, 68f, 88f, bridgePaint)
            canvas.drawLine(52f, 64f, 56f, 98f, bridgePaint)

            // Eroded concrete grain & spray spatter around rat
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val px = bmp.getPixel(x, y)
                    val a = Color.alpha(px)
                    if (a > 0) {
                        val grain = hash2D(x, y, 919)
                        if (grain > 0.82f) {
                            val erodedAlpha = (a * 0.55f).toInt()
                            bmp.setPixel(
                                x, y,
                                Color.argb(erodedAlpha, Color.red(px), Color.green(px), Color.blue(px))
                            )
                        }
                    } else {
                        // Sparse spatter dots near silhouette
                        val spatter = hash2D(x, y, 777)
                        if (spatter > 0.994f && x in 24..108 && y in 42..110) {
                            bmp.setPixel(x, y, Color.argb(120, 31, 122, 114))
                        }
                    }
                }
            }

            val result = bmp.asImageBitmap()
            cachedRatStencil = result
            return result
        }
    }

    /**
     * Original chalk-white stencil balloon with a mustard string tied at its knot.
     * The string runs down to the bitmap's bottom edge so the balloon can be pinned
     * above the stencil clock as if tethered to it. 100% procedural, zero assets.
     * Header-zone palette: Chalk White balloon + Mustard Yellow string.
     */
    fun getOrGenerateChalkBalloonStencil(): ImageBitmap {
        cachedBalloonStencil?.let { return it }
        synchronized(this) {
            cachedBalloonStencil?.let { return it }
            val w = 104
            val h = 160
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val chalk = WallPalette.CHALK_WHITE_INT
            val mustard = WallPalette.MUSTARD_YELLOW_INT

            // Faint chalk overspray halo around the balloon
            val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    w * 0.5f, h * 0.28f, w * 0.48f,
                    Color.argb(40, 242, 239, 233),
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawCircle(w * 0.5f, h * 0.28f, w * 0.48f, haloPaint)

            // Balloon body + tie knot
            val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = chalk
                style = Paint.Style.FILL
            }
            canvas.drawOval(RectF(w * 0.16f, h * 0.03f, w * 0.84f, h * 0.55f), bodyPaint)
            canvas.drawRect(w * 0.45f, h * 0.55f, w * 0.55f, h * 0.585f, bodyPaint)

            // Mustard string: loose S-curve from the knot down to the tie point
            val stringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = mustard
                style = Paint.Style.STROKE
                strokeWidth = 3f
                strokeCap = Paint.Cap.ROUND
            }
            val stringPath = android.graphics.Path().apply {
                moveTo(w * 0.5f, h * 0.585f)
                quadTo(w * 0.36f, h * 0.72f, w * 0.55f, h * 0.84f)
                quadTo(w * 0.62f, h * 0.90f, w * 0.56f, h * 0.985f)
            }
            canvas.drawPath(stringPath, stringPaint)
            canvas.drawCircle(
                w * 0.56f, h * 0.975f, 3f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = mustard
                    style = Paint.Style.FILL
                }
            )

            // Stencil bridge cutout across the balloon
            val bridgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                color = Color.BLACK
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }
            canvas.drawLine(w * 0.20f, h * 0.30f, w * 0.80f, h * 0.30f, bridgePaint)

            // Eroded concrete grain & spatter around the balloon
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val px = bmp.getPixel(x, y)
                    val a = Color.alpha(px)
                    if (a > 0) {
                        val grain = hash2D(x, y, 727)
                        if (grain > 0.82f) {
                            val erodedAlpha = (a * 0.55f).toInt()
                            bmp.setPixel(
                                x, y,
                                Color.argb(erodedAlpha, Color.red(px), Color.green(px), Color.blue(px))
                            )
                        }
                    } else {
                        val spatter = hash2D(x, y, 717)
                        if (spatter > 0.994f && x in 8..96 && y in 0..110) {
                            bmp.setPixel(x, y, Color.argb(110, 242, 239, 233))
                        }
                    }
                }
            }

            val result = bmp.asImageBitmap()
            cachedBalloonStencil = result
            return result
        }
    }

    /**
     * Converts an app's native Drawable into a distressed black-and-white
     * stamp stencil on concrete (ref: grok B&W icon-pack screenshot).
     * Done with restraint so icons stay recognizable:
     * - Full-bleed 408px canvas: no upscale blur on dense screens
     * - Gentle desaturation + soft 1.12 lift: midtone detail survives
     * - Smooth chalk/charcoal duotone blend, never a hard snap
     * - Feathered squircle edge + sparse pinhole grain, never bites
     * - Directional cast shadow (top-left light source), faint halo,
     *   spatter, and ±1.5° organic tilt kept subtle.
     */
    fun getOrRenderSprayedIcon(
        packageName: String,
        drawable: Drawable
    ): SprayedIconAsset {
        iconCache.get(packageName)?.let { return it }

        val canvasSize = ICON_CANVAS_PX
        val iconSize = 288
        val offset = (canvasSize - iconSize) / 2

        // 1. Rasterize source app icon
        val rawBmp = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888)
        val rawCanvas = Canvas(rawBmp)
        drawable.setBounds(0, 0, iconSize, iconSize)
        drawable.draw(rawCanvas)

        // 2. Gentle grade: desaturate, then a soft lift that keeps midtone
        // detail instead of crushing it. The old 1.32 snap is what ate icons.
        val gradedBmp = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888)
        val gradedCanvas = Canvas(gradedBmp)
        val satMatrix = ColorMatrix().apply { setSaturation(0f) }
        val contrast = 1.12f
        val shift = (-0.5f * contrast + 0.5f) * 255f
        val contrastMatrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, shift,
                0f, contrast, 0f, 0f, shift,
                0f, 0f, contrast, 0f, shift,
                0f, 0f, 0f, 1f, 0f
            )
        )
        satMatrix.postConcat(contrastMatrix)
        val gradePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(satMatrix)
        }
        gradedCanvas.drawBitmap(rawBmp, 0f, 0f, gradePaint)
        rawBmp.recycle()

        // 3. Extract dominant pigment color for the faint overspray halo
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var count = 0L
        val srcPixels = IntArray(iconSize * iconSize)
        gradedBmp.getPixels(srcPixels, 0, iconSize, 0, 0, iconSize, iconSize)

        for (i in srcPixels.indices step 4) {
            val c = srcPixels[i]
            val a = Color.alpha(c)
            if (a > 120) {
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                // Ignore near-white background pixels when sampling dominant hue
                if (r < 240 || g < 240 || b < 240) {
                    sumR += r
                    sumG += g
                    sumB += b
                    count++
                }
            }
        }
        val domR = if (count > 0) (sumR / count).toInt() else 60
        val domG = if (count > 0) (sumG / count).toInt() else 65
        val domB = if (count > 0) (sumB / count).toInt() else 70
        val dominantColor = Color.rgb(domR, domG, domB)

        // 4. Process pixels into weathered stencil pigment + ghost imprint
        val ghostBmp = Bitmap.createBitmap(canvasSize, canvasSize, Bitmap.Config.ARGB_8888)
        val finalBmp = Bitmap.createBitmap(canvasSize, canvasSize, Bitmap.Config.ARGB_8888)
        val finalCanvas = Canvas(finalBmp)
        val ghostCanvas = Canvas(ghostBmp)

        val pkgHash = packageName.hashCode()
        // Deterministic rotation in [-1.5°, +1.5°]
        val tiltDegrees = (((pkgHash and 0xFFFF) / 65535f) * 3.0f) - 1.5f

        finalCanvas.save()
        finalCanvas.rotate(tiltDegrees, canvasSize / 2f, canvasSize / 2f)
        ghostCanvas.save()
        ghostCanvas.rotate(tiltDegrees, canvasSize / 2f, canvasSize / 2f)

        // 4a. Draw faint overspray halo around the stencil
        val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                canvasSize / 2f,
                canvasSize / 2f,
                canvasSize * 0.47f,
                Color.argb(38, domR, domG, domB),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
        }
        finalCanvas.drawCircle(canvasSize / 2f, canvasSize / 2f, canvasSize * 0.47f, haloPaint)

        // 4b. Process icon silhouette, stencil spatter edge, and concrete multiply absorption
        val sprayedIconBmp = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888)
        val ghostIconBmp = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888)
        val shadowIconBmp = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888)

        val sprayedPixels = IntArray(iconSize * iconSize)
        val ghostPixels = IntArray(iconSize * iconSize)
        val shadowPixels = IntArray(iconSize * iconSize)

        val center = iconSize / 2f
        val maxRadius = iconSize * 0.48f

        for (y in 0 until iconSize) {
            for (x in 0 until iconSize) {
                val idx = y * iconSize + x
                val c = srcPixels[idx]
                val a = Color.alpha(c)
                if (a < 25) continue

                val dx = (x - center) / maxRadius
                val dy = (y - center) / maxRadius
                val distSq = max(abs(dx), abs(dy)) // Squircle stencil boundary
                val radialDist = sqrt(dx * dx + dy * dy)

                val edgeNoise = hash2D(x, y, pkgHash)
                val coarsePorosity = valueNoise(x * 0.22f, y * 0.22f, pkgHash + 31)

                // Feather rounded-square sticker edges into an organic
                // spray-painted border — a soft wobble, never a hard bite
                var edgeMask = 1.0f
                if (distSq > 0.88f) {
                    val falloff = (1.0f - (distSq - 0.88f) / 0.16f).coerceIn(0f, 1f)
                    edgeMask = (falloff * (0.75f + edgeNoise * 0.5f)).coerceIn(0f, 1f)
                }

                if (edgeMask <= 0.02f) continue

                // Graded bitmap is fully desaturated: r == g == b == gray level
                val lum = Color.red(c) / 255f

                // Gentle duotone: smooth chalk/charcoal blend that KEEPS midtone
                // detail instead of snapping it away. t=0 -> charcoal spray,
                // t=1 -> chalk white, smoothstepped across the middle band so
                // icon shapes stay recognizable.
                val t = ((lum - 0.30f) / 0.40f).coerceIn(0f, 1f)
                val smooth = t * t * (3f - 2f * t)
                // Fine concrete grain modulates tone, never erases it
                val grain = 0.94f + (coarsePorosity - 0.5f) * 0.10f + (edgeNoise - 0.5f) * 0.06f
                val r = ((52f + (233f - 52f) * smooth) * grain).toInt().coerceIn(20, 244)
                val g = ((48f + (229f - 48f) * smooth) * grain).toInt().coerceIn(18, 240)
                val b = ((44f + (220f - 44f) * smooth) * grain).toInt().coerceIn(16, 232)

                // Sparse pinholes where raw wall shows through — rare and tiny,
                // scaled to the new resolution so they read as grain, not damage
                val porosityAlpha = if (edgeNoise > 0.965f) 0.55f else 0.98f
                val finalAlpha = (a * edgeMask * porosityAlpha).toInt().coerceIn(0, 255)

                sprayedPixels[idx] = Color.argb(finalAlpha, r, g, b)

                // Ghost stencil remnant ("The wall remembers" when peeled)
                val ghostAlpha = if (coarsePorosity > 0.42f) (finalAlpha * 0.34f).toInt() else (finalAlpha * 0.14f).toInt()
                ghostPixels[idx] = Color.argb(ghostAlpha, r, g, b)

                // Directional Wall Surface Shadow (cast toward bottom-right from top-left light)
                if (radialDist < 1.05f) {
                    val shadowAlpha = (finalAlpha * 0.28f).toInt()
                    shadowPixels[idx] = Color.argb(shadowAlpha, 58, 52, 44)
                }
            }
        }

        sprayedIconBmp.setPixels(sprayedPixels, 0, iconSize, 0, 0, iconSize, iconSize)
        ghostIconBmp.setPixels(ghostPixels, 0, iconSize, 0, 0, iconSize, iconSize)
        shadowIconBmp.setPixels(shadowPixels, 0, iconSize, 0, 0, iconSize, iconSize)
        gradedBmp.recycle()

        // 4c. Draw directional cast shadow directly onto wall plane (+4px X, +5px Y)
        val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        finalCanvas.drawBitmap(shadowIconBmp, offset + 11f, offset + 14f, bitmapPaint)

        // 4d. Draw sprayed icon pigment layer
        finalCanvas.drawBitmap(sprayedIconBmp, offset.toFloat(), offset.toFloat(), bitmapPaint)
        ghostCanvas.drawBitmap(ghostIconBmp, offset.toFloat(), offset.toFloat(), bitmapPaint)

        // 4e. Add micro spray spatter droplets & occasional paint micro-drip below icon
        val spatterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        for (i in 0 until 28) {
            val angle = hash2D(i, 1, pkgHash) * (Math.PI.toFloat() * 2f)
            val dist = (iconSize * 0.44f) + hash2D(i, 2, pkgHash) * 39f
            val sx = (canvasSize / 2f) + cos(angle) * dist
            val sy = (canvasSize / 2f) + sin(angle) * dist
            val radius = 2.2f + hash2D(i, 3, pkgHash) * 4.4f
            spatterPaint.color = Color.argb(
                (70 + hash2D(i, 4, pkgHash) * 90).toInt(),
                domR, domG, domB
            )
            finalCanvas.drawCircle(sx, sy, radius, spatterPaint)
        }

        // Subtle gravity paint bleed on ~35% of icons
        if ((abs(pkgHash) % 10) < 4) {
            val dripX = offset + iconSize * (0.3f + (abs(pkgHash shr 4) % 40) / 100f)
            val dripStartY = (offset + iconSize - 17).toFloat()
            val dripLen = 19f + (abs(pkgHash shr 8) % 25)
            spatterPaint.color = Color.argb(145, domR, domG, domB)
            spatterPaint.strokeWidth = 6f
            finalCanvas.drawLine(dripX, dripStartY, dripX, dripStartY + dripLen, spatterPaint)
            finalCanvas.drawCircle(dripX, dripStartY + dripLen, 5f, spatterPaint)
        }

        finalCanvas.restore()
        ghostCanvas.restore()

        sprayedIconBmp.recycle()
        ghostIconBmp.recycle()
        shadowIconBmp.recycle()

        val asset = SprayedIconAsset(
            normalBitmap = finalBmp.asImageBitmap(),
            ghostBitmap = ghostBmp.asImageBitmap(),
            dominantColor = dominantColor,
            tiltDegrees = tiltDegrees
        )
        iconCache.put(packageName, asset)
        return asset
    }

    /**
     * Computes the time-of-day atmospheric color tint.
     * - Daylight (07:00 - 17:59): Harsh sun-bleached concrete (bright, slightly chalky)
     * - Evening/Night (18:00 - 06:59): Warm sodium-vapor amber streetlamp glow
     */
    fun isNightSodiumLampHour(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour < 7 || hour >= 18
    }
}
