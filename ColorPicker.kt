package io.github.thrillfall.mic2bluetooth

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog

/** Tiny color picker: preset swatches + free hex input (#RRGGBB) + optional "Monet" choice. */
object ColorPicker {
    private val PRESETS = listOf(
        "#D32F2F", "#E91E63", "#9C27B0", "#3F51B5", "#2196F3", "#00BCD4", "#4CAF50",
        "#FFC107", "#FF9800", "#795548", "#FFFFFF", "#9E9E9E", "#000000", "#121212"
    ).map { Color.parseColor(it) }

    /** onPick(color, monet): monet = true means "use the system (Monet) color". */
    fun show(
        ctx: Context,
        title: String,
        current: Int,
        allowMonet: Boolean,
        onPick: (Int, Boolean) -> Unit
    ) {
        val dp = ctx.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val input = EditText(ctx).apply {
            setText(String.format("#%06X", current and 0xFFFFFF))
            setSingleLine()
        }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        PRESETS.forEach { c ->
            val sw = View(ctx).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c)
                    setStroke(px(1), Color.GRAY)
                }
                layoutParams = LinearLayout.LayoutParams(px(36), px(36)).apply {
                    setMargins(px(4), px(4), px(4), px(4))
                }
                setOnClickListener { input.setText(String.format("#%06X", c and 0xFFFFFF)) }
            }
            row.addView(sw)
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(12), px(20), 0)
            addView(HorizontalScrollView(ctx).apply { addView(row) })
            addView(input)
        }
        val builder = AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val c = runCatching { Color.parseColor(input.text.toString().trim()) }.getOrNull()
                if (c != null) onPick(c, false)
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (allowMonet) {
            builder.setNeutralButton(R.string.pick_monet) { _, _ -> onPick(current, true) }
        }
        builder.show()
    }
}
