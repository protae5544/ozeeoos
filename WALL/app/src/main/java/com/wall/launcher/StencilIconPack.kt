package com.wall.launcher

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat

/**
 * WALL stencil icon pack: hand-drawn white stencil glyphs, one per known app.
 *
 * The old handling (recolor whatever icon the system hands us) is gone for
 * covered apps: if the package is in [PACK], its pack glyph is rendered
 * through the B&W stamp pipeline instead of the app's native icon.
 * Apps with no pack entry keep the procedural B&W stamp of their own icon
 * so nothing ever shows up blank. To extend the pack: add a
 * `res/drawable/stencil_<name>.xml` vector and one map entry below.
 */
object StencilIconPack {

    private val PACK: Map<String, Int> = mapOf(
        "com.termux" to R.drawable.stencil_termux,
        "com.google.android.gm" to R.drawable.stencil_gmail,
        "com.google.android.youtube" to R.drawable.stencil_youtube,
        "com.android.chrome" to R.drawable.stencil_chrome,
        "com.google.android.apps.maps" to R.drawable.stencil_maps,
        "com.google.android.photos" to R.drawable.stencil_photos,
        "com.android.vending" to R.drawable.stencil_playstore,
        "com.google.android.dialer" to R.drawable.stencil_dialer,
        "com.android.dialer" to R.drawable.stencil_dialer,
        "com.google.android.apps.messaging" to R.drawable.stencil_messaging,
        "jp.naver.line.android" to R.drawable.stencil_line,
        "com.android.settings" to R.drawable.stencil_settings,
        "com.google.android.calendar" to R.drawable.stencil_calendar
    )

    val packSize: Int get() = PACK.size

    fun stencilFor(context: Context, packageName: String): Drawable? {
        val resId = PACK[packageName] ?: return null
        return try {
            ContextCompat.getDrawable(context, resId)
        } catch (_: Exception) {
            null
        }
    }
}
