package io.github.thrillfall.mic2bluetooth

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color

/** All user settings, stored in SharedPreferences. */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("m2b", Context.MODE_PRIVATE)

    var gain: Float
        get() = sp.getFloat("gain", 1f)
        set(v) { sp.edit().putFloat("gain", v).apply() }

    /** 0 = off, 1 = soft, 2 = strong */
    var normMode: Int
        get() = sp.getInt("norm", 0)
        set(v) { sp.edit().putInt("norm", v).apply() }

    var headsetMic: Boolean
        get() = sp.getBoolean("headsetMic", false)
        set(v) { sp.edit().putBoolean("headsetMic", v).apply() }

    /** Use AudioSource.UNPROCESSED (no Android processing at all) when the phone supports it. */
    var rawMic: Boolean
        get() = sp.getBoolean("rawMic", false)
        set(v) { sp.edit().putBoolean("rawMic", v).apply() }

    var theme: Int
        get() = sp.getInt("theme", THEME_DEFAULT)
        set(v) { sp.edit().putInt("theme", v).apply() }

    var bgColor: Int
        get() = sp.getInt("bgColor", Color.parseColor("#121212"))
        set(v) { sp.edit().putInt("bgColor", v).apply() }

    var accent: Int
        get() = sp.getInt("accent", Color.parseColor("#BB86FC"))
        set(v) { sp.edit().putInt("accent", v).apply() }

    var btnColor: Int
        get() = sp.getInt("btnColor", Color.parseColor("#D32F2F"))
        set(v) { sp.edit().putInt("btnColor", v).apply() }

    /** Mic button uses the system (Monet) color instead of btnColor. */
    var btnMonet: Boolean
        get() = sp.getBoolean("btnMonet", false)
        set(v) { sp.edit().putBoolean("btnMonet", v).apply() }

    var wavesOn: Boolean
        get() = sp.getBoolean("wavesOn", true)
        set(v) { sp.edit().putBoolean("wavesOn", v).apply() }

    var waveColor: Int
        get() = sp.getInt("waveColor", Color.parseColor("#BB86FC"))
        set(v) { sp.edit().putInt("waveColor", v).apply() }

    var waveMonet: Boolean
        get() = sp.getBoolean("waveMonet", true)
        set(v) { sp.edit().putBoolean("waveMonet", v).apply() }

    /** Show the phone's home wallpaper behind the app. */
    var useWallpaper: Boolean
        get() = sp.getBoolean("useWallpaper", false)
        set(v) { sp.edit().putBoolean("useWallpaper", v).apply() }

    var btnShape: Int
        get() = sp.getInt("btnShape", SHAPE_ROUNDED)
        set(v) { sp.edit().putInt("btnShape", v).apply() }

    /** Mic button size in dp. */
    var btnSize: Int
        get() = sp.getInt("btnSize", 72)
        set(v) { sp.edit().putInt("btnSize", v).apply() }

    /** Background image darkening, 0..80 (%). */
    var bgDim: Int
        get() = sp.getInt("bgDim", 40)
        set(v) { sp.edit().putInt("bgDim", v).apply() }

    companion object {
        const val THEME_DEFAULT = 0
        const val THEME_AMOLED = 1
        const val THEME_MONET = 2
        const val THEME_MANUAL = 3
        const val SHAPE_CIRCLE = 0
        const val SHAPE_ROUNDED = 1
        const val SHAPE_SQUARE = 2
    }
}
