package com.quickmath.overlay

import android.content.Context
import android.content.SharedPreferences

/** All user settings. Stored on the phone only. */
class AppPrefs(context: Context) {

    val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("quickmath_prefs", Context.MODE_PRIVATE)

    /** Pause between OCR scans (ms). Lower = faster answers, more battery. */
    var intervalMs: Int
        get() = sp.getInt("interval_ms", 200)
        set(v) { sp.edit().putInt("interval_ms", v).apply() }

    var bubbleSizeSp: Int
        get() = sp.getInt("bubble_size", 24)
        set(v) { sp.edit().putInt("bubble_size", v).apply() }

    var opacityPct: Int
        get() = sp.getInt("opacity", 90)
        set(v) { sp.edit().putInt("opacity", v).apply() }

    /** Seconds the answer stays visible. 0 = until the next question. */
    var displaySec: Int
        get() = sp.getInt("display_sec", 0)
        set(v) { sp.edit().putInt("display_sec", v).apply() }

    var haptic: Boolean
        get() = sp.getBoolean("haptic", true)
        set(v) { sp.edit().putBoolean("haptic", v).apply() }

    var opAdd: Boolean
        get() = sp.getBoolean("op_add", true)
        set(v) { sp.edit().putBoolean("op_add", v).apply() }

    var opSub: Boolean
        get() = sp.getBoolean("op_sub", true)
        set(v) { sp.edit().putBoolean("op_sub", v).apply() }

    var opMul: Boolean
        get() = sp.getBoolean("op_mul", true)
        set(v) { sp.edit().putBoolean("op_mul", v).apply() }

    var opDiv: Boolean
        get() = sp.getBoolean("op_div", true)
        set(v) { sp.edit().putBoolean("op_div", v).apply() }

    var bubbleX: Int
        get() = sp.getInt("bubble_x", 40)
        set(v) { sp.edit().putInt("bubble_x", v).apply() }

    var bubbleY: Int
        get() = sp.getInt("bubble_y", 320)
        set(v) { sp.edit().putInt("bubble_y", v).apply() }

    fun allowedOps(): Set<Char> {
        val s = HashSet<Char>()
        if (opAdd) s.add('+')
        if (opSub) s.add('-')
        if (opMul) s.add('*')
        if (opDiv) s.add('/')
        return s
    }
}
