package io.github.thrillfall.mic2bluetooth

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.View
import android.widget.ImageView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.shape.RelativeCornerSize
import com.google.android.material.shape.ShapeAppearanceModel
import java.io.File

/** Theme / background / mic-button styling shared by both screens. */
object Appearance {
    const val BG_FILE = "background.jpg"

    class Palette(val bg: Int, val on: Int, val accent: Int)

    /** Call before super.onCreate() so Monet (dynamic colors) can apply. */
    fun beforeCreate(a: Activity) {
        if (Prefs(a).theme == Prefs.THEME_MONET) DynamicColors.applyToActivityIfAvailable(a)
    }

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

    /** Paints the root color + optional custom background image. Returns the palette to use for texts. */
    fun applyBackground(a: Activity, root: View, image: ImageView, dim: View): Palette {
        val p = Prefs(a)
        val pal = palette(a)
        root.setBackgroundColor(pal.bg)
        val f = File(a.filesDir, BG_FILE)
        val bmp = if (f.exists()) runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull() else null
        return if (bmp != null) {
            image.setImageBitmap(bmp)
            image.visibility = View.VISIBLE
            dim.visibility = View.VISIBLE
            dim.setBackgroundColor(Color.argb((p.bgDim * 255 / 100).coerceIn(0, 255), 0, 0, 0))
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

    fun stateList(checked: Int, unchecked: Int) = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(checked, unchecked)
    )

    fun styleMicButton(fab: FloatingActionButton, p: Prefs, active: Boolean) {
        val dp = fab.resources.displayMetrics.density
        val size = (p.btnSize * dp).toInt()
        fab.setCustomSize(size)
        fab.setMaxImageSize((size * 0.5f).toInt())
        val color = if (active) ColorUtils.blendARGB(p.btnColor, Color.WHITE, 0.3f) else p.btnColor
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
