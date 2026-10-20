package io.github.thrillfall.mic2bluetooth

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.shape.RelativeCornerSize
import com.google.android.material.shape.ShapeAppearanceModel
import java.io.File

/** Theme / background / fonts / mic-button styling shared by both screens. */
object Appearance {
    const val BG_FILE = "background.jpg"

    class Palette(val bg: Int, val on: Int, val accent: Int)

    /** Call before super.onCreate() so Monet (dynamic colors) can apply to the whole screen. */
    fun beforeCreate(a: Activity) {
        if (Prefs(a).theme == Prefs.THEME_MONET) DynamicColors.applyToActivityIfAvailable(a)
    }

    /** System (Monet) colors: [primary, tertiary, secondary]. Works regardless of the chosen app theme. */
    fun monet(ctx: Context): IntArray {
        val c = DynamicColors.wrapContextIfAvailable(ctx)
        return intArrayOf(
            MaterialColors.getColor(c, androidx.appcompat.R.attr.colorPrimary, Color.parseColor("#D0BCFF")),
            MaterialColors.getColor(c, com.google.android.material.R.attr.colorTertiary, Color.parseColor("#EFB8C8")),
            MaterialColors.getColor(c, com.google.android.material.R.attr.colorSecondary, Color.parseColor("#CCC2DC"))
        )
    }

    fun buttonColor(ctx: Context, p: Prefs): Int = if (p.btnMonet) monet(ctx)[0] else p.btnColor

    fun waveColors(ctx: Context, p: Prefs): IntArray =
        if (p.waveMonet) monet(ctx).let { intArrayOf(it[0], it[1], it[2], it[0]) }
        else IntArray(4) { p.waveColor }

    fun palette(a: Activity): Palette {
        val p = Prefs(a)
        val themeBg = MaterialColors.getColor(a, com.google.android.material.R.attr.colorSurface, Color.BLACK)
        val themeAccent = MaterialColors.getColor(a, androidx.appcompat.R.attr.colorPrimary, Color.WHITE)
        val bg = when (p.theme) {
            Prefs.THEME_AMOLED -> Color.BLACK
            Prefs.THEME_MANUAL -> p.bgColor
            else -> themeBg
        }
        val accent = if (p.theme == Prefs.THEME_MANUAL) p.accent else themeAccent
        val on = if (ColorUtils.calculateLuminance(bg) > 0.5) Color.BLACK else Color.WHITE
        return Palette(bg, on, accent)
    }

    /**
     * Paints the background: phone wallpaper > custom image > theme color.
     * Returns the palette to use for texts.
     */
    @Suppress("DEPRECATION")
    fun applyBackground(a: Activity, root: View, image: ImageView, dim: View): Palette {
        val p = Prefs(a)
        val pal = palette(a)
        val dimColor = Color.argb((p.bgDim * 255 / 100).coerceIn(0, 255), 0, 0, 0)

        if (p.useWallpaper) {
            // Let the system draw the home wallpaper behind our (transparent) window
            a.window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            a.window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            a.window.setFormat(PixelFormat.TRANSLUCENT)
            root.setBackgroundColor(Color.TRANSPARENT)
            image.setImageDrawable(null)
            image.visibility = View.GONE
            dim.visibility = View.VISIBLE
            dim.setBackgroundColor(dimColor)
            return Palette(pal.bg, Color.WHITE, pal.accent)
        }

        a.window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        a.window.setBackgroundDrawable(ColorDrawable(pal.bg))
        root.setBackgroundColor(pal.bg)
        val f = File(a.filesDir, BG_FILE)
        val bmp = if (f.exists()) runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull() else null
        return if (bmp != null) {
            image.setImageBitmap(bmp)
            image.visibility = View.VISIBLE
            dim.visibility = View.VISIBLE
            dim.setBackgroundColor(dimColor)
            Palette(pal.bg, Color.WHITE, pal.accent)
        } else {
            image.setImageDrawable(null)
            image.visibility = View.GONE
            dim.visibility = View.GONE
            pal
        }
    }

    /** Edge-to-edge window with transparent bars, and content padded by the system bars. */
    fun setupWindow(a: Activity, content: View, on: Int) {
        WindowCompat.setDecorFitsSystemWindows(a.window, false)
        @Suppress("DEPRECATION")
        a.window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        a.window.navigationBarColor = Color.TRANSPARENT
        runCatching { a.window.isNavigationBarContrastEnforced = false }
        val c = WindowCompat.getInsetsController(a.window, content)
        c.isAppearanceLightStatusBars = (on == Color.BLACK)
        c.isAppearanceLightNavigationBars = (on == Color.BLACK)
        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(content)
    }

    /** Soft translucent card that works on any background (color, image or wallpaper). */
    fun styleCard(card: MaterialCardView, pal: Palette) {
        card.setCardBackgroundColor(ColorUtils.setAlphaComponent(pal.on, 30))
        card.strokeWidth = 0
        card.cardElevation = 0f
    }

    /** Applies the app font (Helvetica Neue Medium) to every TextView under [root]. */
    fun applyFont(root: View) {
        val tf = ResourcesCompat.getFont(root.context, R.font.helvetica_neue_medium) ?: return
        fun walk(v: View) {
            if (v is TextView) v.setTypeface(tf, v.typeface?.style ?: Typeface.NORMAL)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
    }

    fun stateList(checked: Int, unchecked: Int) = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(checked, unchecked)
    )

    fun styleMicButton(fab: FloatingActionButton, p: Prefs, active: Boolean) {
        val dp = fab.resources.displayMetrics.density
        val size = (p.btnSize * dp).toInt()
        fab.setCustomSize(size)
        fab.setMaxImageSize((size * 0.5f).toInt())
        val base = buttonColor(fab.context, p)
        val color = if (active) ColorUtils.blendARGB(base, Color.WHITE, 0.3f) else base
        fab.backgroundTintList = ColorStateList.valueOf(color)
        fab.imageTintList = ColorStateList.valueOf(
            if (ColorUtils.calculateLuminance(color) > 0.5) Color.BLACK else Color.WHITE
        )
        val r = when (p.btnShape) {
            Prefs.SHAPE_CIRCLE -> 0.5f
            Prefs.SHAPE_SQUARE -> 0.1f
            else -> 0.28f
        }
        fab.shapeAppearanceModel = ShapeAppearanceModel.builder()
            .setAllCornerSizes(RelativeCornerSize(r))
            .build()
    }
}
