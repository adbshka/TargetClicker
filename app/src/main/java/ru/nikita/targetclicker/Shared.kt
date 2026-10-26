package ru.nikita.targetclicker

import android.content.Context

/** Настройки (общие для активити и сервисов, живут в одном процессе). */
object Config {
    @Volatile var intervalMs = 20L   // пауза между кликами
    @Volatile var topPct = 42        // верх области поиска, % высоты экрана
    @Volatile var bottomPct = 84     // низ области поиска, % высоты экрана
    @Volatile var leadMs = 40        // упреждение по движению цели

    fun load(c: Context) {
        val p = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        intervalMs = p.getLong("interval", 20L)
        topPct = p.getInt("top", 42)
        bottomPct = p.getInt("bottom", 84)
        leadMs = p.getInt("lead", 40)
    }

    fun save(c: Context) {
        c.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
            .putLong("interval", intervalMs)
            .putInt("top", topPct)
            .putInt("bottom", bottomPct)
            .putInt("lead", leadMs)
            .apply()
    }
}

/** Последнее найденное положение цели (в координатах экрана). */
object TargetState {
    @Volatile var found = false
    @Volatile var x = 0f
    @Volatile var y = 0f
    @Volatile var vx = 0f        // пикселей в мс
    @Volatile var vy = 0f
    @Volatile var time = 0L      // SystemClock.uptimeMillis()
    @Volatile var clicking = false
    @Volatile var captureActive = false
}
