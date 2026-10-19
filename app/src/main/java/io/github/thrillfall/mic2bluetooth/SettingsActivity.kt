package io.github.thrillfall.mic2bluetooth

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import io.github.thrillfall.mic2bluetooth.databinding.ActivitySettingsBinding
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding
    private lateinit var p: Prefs

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) saveBackground(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Appearance.beforeCreate(this)
        super.onCreate(savedInstanceState)
        p = Prefs(this)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Theme
        b.themeGroup.check(
            when (p.theme) {
                Prefs.THEME_AMOLED -> R.id.themeAmoled
                Prefs.THEME_MONET -> R.id.themeMonet
                Prefs.THEME_MANUAL -> R.id.themeManual
                else -> R.id.themeDefault
            }
        )
        b.themeGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val t = when (id) {
                R.id.themeAmoled -> Prefs.THEME_AMOLED
                R.id.themeMonet -> Prefs.THEME_MONET
                R.id.themeManual -> Prefs.THEME_MANUAL
                else -> Prefs.THEME_DEFAULT
            }
            if (t != p.theme) {
                p.theme = t
                recreate()
            }
        }
        b.manualRow.visibility = if (p.theme == Prefs.THEME_MANUAL) View.VISIBLE else View.GONE
        b.bgColorButton.setOnClickListener {
            ColorPicker.show(this, getString(R.string.pick_bg_color), p.bgColor) {
                p.bgColor = it
                applyLook()
            }
        }
        b.accentButton.setOnClickListener {
            ColorPicker.show(this, getString(R.string.pick_accent), p.accent) {
                p.accent = it
                applyLook()
            }
        }

        // Background image
        b.pickImageButton.setOnClickListener { pickImage.launch("image/*") }
        b.removeImageButton.setOnClickListener {
            File(filesDir, Appearance.BG_FILE).delete()
            applyLook()
        }
        b.dimSlider.value = p.bgDim.toFloat().coerceIn(0f, 80f)
        b.dimLabel.text = getString(R.string.dim_label, p.bgDim)
        b.dimSlider.addOnChangeListener { _, value, _ ->
            p.bgDim = value.toInt()
            b.dimLabel.text = getString(R.string.dim_label, p.bgDim)
            b.bgDim.setBackgroundColor(Color.argb((p.bgDim * 255 / 100).coerceIn(0, 255), 0, 0, 0))
        }

        // Mic button
        b.btnColorButton.setOnClickListener {
            ColorPicker.show(this, getString(R.string.pick_button_color), p.btnColor) {
                p.btnColor = it
                refreshPreview()
            }
        }
        b.shapeGroup.check(
            when (p.btnShape) {
                Prefs.SHAPE_CIRCLE -> R.id.shapeCircle
                Prefs.SHAPE_SQUARE -> R.id.shapeSquare
                else -> R.id.shapeRounded
            }
        )
        b.shapeGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            p.btnShape = when (id) {
                R.id.shapeCircle -> Prefs.SHAPE_CIRCLE
                R.id.shapeSquare -> Prefs.SHAPE_SQUARE
                else -> Prefs.SHAPE_ROUNDED
            }
            refreshPreview()
        }
        b.sizeSlider.value = p.btnSize.toFloat().coerceIn(56f, 260f)
        b.sizeLabel.text = getString(R.string.size_label, p.btnSize)
        b.sizeSlider.addOnChangeListener { _, value, _ ->
            p.btnSize = value.toInt()
            b.sizeLabel.text = getString(R.string.size_label, p.btnSize)
            refreshPreview()
        }

        // Capture
        b.rawSwitch.isChecked = p.rawMic
        b.rawSwitch.setOnCheckedChangeListener { _, checked -> p.rawMic = checked }

        applyLook()
    }

    private fun applyLook() {
        val pal = Appearance.applyBackground(this, b.root, b.bgImage, b.bgDim)
        Appearance.setupWindow(this, b.content, pal.on)

        listOf(
            b.title, b.secTheme, b.secBackground, b.secButton, b.secCapture,
            b.dimLabel, b.sizeLabel, b.rawHint, b.rawSwitch
        ).forEach { it.setTextColor(pal.on) }

        listOf(
            b.themeDefault, b.themeAmoled, b.themeMonet, b.themeManual,
            b.bgColorButton, b.accentButton, b.pickImageButton, b.removeImageButton,
            b.btnColorButton, b.shapeCircle, b.shapeRounded, b.shapeSquare
        ).forEach {
            it.setTextColor(pal.on)
            it.strokeColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(pal.on, 120))
            it.backgroundTintList = Appearance.stateList(
                ColorUtils.setAlphaComponent(pal.accent, 100), Color.TRANSPARENT
            )
        }

        val accent = ColorStateList.valueOf(pal.accent)
        listOf(b.dimSlider, b.sizeSlider).forEach {
            it.trackActiveTintList = accent
            it.thumbTintList = accent
            it.haloTintList = accent
            it.trackInactiveTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(pal.on, 60))
        }
        b.rawSwitch.thumbTintList = Appearance.stateList(pal.accent, Color.parseColor("#9E9E9E"))
        b.rawSwitch.trackTintList = Appearance.stateList(
            ColorUtils.setAlphaComponent(pal.accent, 110),
            ColorUtils.setAlphaComponent(pal.on, 50)
        )

        refreshPreview()
    }

    private fun refreshPreview() {
        Appearance.styleMicButton(b.previewButton, p, false)
    }

    /** Copies the chosen picture into app storage, downscaled (max ~1600 px) and upright. */
    private fun saveBackground(uri: Uri) {
        Thread {
            val ok = runCatching {
                val bmp = decodeScaled(uri, 1600) ?: error("decode failed")
                val upright = rotateIfNeeded(uri, bmp)
                File(filesDir, Appearance.BG_FILE).outputStream().use {
                    upright.compress(Bitmap.CompressFormat.JPEG, 90, it)
                }
            }.isSuccess
            runOnUiThread {
                if (ok) applyLook() else Toast.makeText(this, R.string.image_error, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun decodeScaled(uri: Uri, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > maxSide * 2 || bounds.outHeight / sample > maxSide * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return null
        val big = maxOf(bmp.width, bmp.height)
        if (big <= maxSide) return bmp
        val f = maxSide.toFloat() / big
        return Bitmap.createScaledBitmap(bmp, (bmp.width * f).toInt(), (bmp.height * f).toInt(), true)
    }

    private fun rotateIfNeeded(uri: Uri, bmp: Bitmap): Bitmap {
        val orientation = runCatching {
            contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        val deg = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (deg == 0f) return bmp
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
    }
}
