package com.tweak.tuner

import android.content.Context
import android.content.res.Configuration

/** Mode tampilan: 0 = ikuti sistem, 1 = terang, 2 = gelap (bawaan). */
object Night {
    fun wrap(base: Context): Context {
        val mode = base.getSharedPreferences("tweak", Context.MODE_PRIVATE).getInt("ikuti sistem", 0)
        if (mode == 0) return base
        val cfg = Configuration(base.resources.configuration)
        val flag = if (mode == 2) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or flag
        return base.createConfigurationContext(cfg)
    }
}
