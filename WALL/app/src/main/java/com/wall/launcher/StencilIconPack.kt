package com.wall.launcher

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat

/**
 * WALL stencil icon pack: stencil cards, one per known app.
 *
 * The old handling (recolor whatever icon the system hands us) is gone for
 * covered apps: if the package is in [PACK], its pack art is rendered
 * through the B&W stamp pipeline instead of the app's native icon.
 * Most entries are user-supplied concrete stencil cards
 * (res/drawable-nodpi/pack_*.png); the rest are hand-drawn white vector
 * glyphs (res/drawable/stencil_*.xml). Apps with no pack entry keep the
 * procedural B&W stamp of their own icon so nothing ever shows up blank.
 * To extend the pack: add a pack_*.png and one map entry below.
 */
object StencilIconPack {

    private val PACK: Map<String, Int> = mapOf(
        // User-supplied stencil cards (content matched to the drawer mockup)
        "com.miui.gallery" to R.drawable.pack_gallery,
        "com.android.gallery3d" to R.drawable.pack_gallery,
        "com.google.android.inputmethod.latin" to R.drawable.pack_violin,
        "com.github.android" to R.drawable.pack_panda,
        "com.google.android.gm" to R.drawable.pack_gmail_m,
        "com.google.android.googlequicksearchbox" to R.drawable.pack_google_g,
        "com.openai.chatgpt" to R.drawable.pack_chatgpt,
        "com.google.android.apps.maps" to R.drawable.pack_maps,
        "com.google.android.apps.bard" to R.drawable.pack_gemini,
        "org.wikipedia" to R.drawable.pack_wikipedia,
        "com.android.vending" to R.drawable.pack_playstore,
        "com.android.settings" to R.drawable.pack_settings,
        "com.opera.browser" to R.drawable.pack_opera,
        "com.opera.mini.native" to R.drawable.pack_opera,
        "com.google.android.apps.nbu.files" to R.drawable.pack_files,
        "com.android.documentsui" to R.drawable.pack_files,
        "com.google.android.keep" to R.drawable.pack_keep,
        "com.shopee.th" to R.drawable.pack_shop,
        "com.lazada.android" to R.drawable.pack_shop,
        "com.kiwibrowser.browser" to R.drawable.pack_kiwi,
        "com.lemon.lvoverseas" to R.drawable.pack_capcut,
        "com.google.android.deskclock" to R.drawable.pack_clock,
        "com.android.deskclock" to R.drawable.pack_clock,
        "com.spotify.music" to R.drawable.pack_music,
        "com.google.android.apps.youtube.music" to R.drawable.pack_music,
        "com.amazon.kindle" to R.drawable.pack_kindle,
        "com.android.camera" to R.drawable.pack_camera,
        "jp.ne.ibis.ibispaintx" to R.drawable.pack_paint,
        "com.miui.notes" to R.drawable.pack_notes,
        "com.samsung.android.app.notes" to R.drawable.pack_notes,
        "com.whatsapp" to R.drawable.pack_whatsapp,
        "com.xai.grok" to R.drawable.pack_grok,
        "com.xiaomi.smarthome" to R.drawable.pack_mihome,
        "com.microsoft.skydrive" to R.drawable.pack_onedrive,
        "com.twitter.android" to R.drawable.pack_x,
        // Hand-drawn fallback glyphs
        "com.termux" to R.drawable.stencil_termux,
        "com.google.android.youtube" to R.drawable.stencil_youtube,
        "com.android.chrome" to R.drawable.stencil_chrome,
        "com.google.android.photos" to R.drawable.stencil_photos,
        "com.google.android.dialer" to R.drawable.stencil_dialer,
        "com.android.dialer" to R.drawable.stencil_dialer,
        "com.google.android.apps.messaging" to R.drawable.stencil_messaging,
        "jp.naver.line.android" to R.drawable.stencil_line,
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
