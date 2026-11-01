package com.akira.island.service

import android.app.Notification
import android.content.Context
import android.os.Build

/**
 * «Живые обновления» Android 16 (Live Updates): приложение просит поднять своё постоянное уведомление
 * повыше (EXTRA_REQUEST_PROMOTED_ONGOING), система ставит FLAG_PROMOTED_ONGOING и показывает его в строке
 * состояния чипом с коротким текстом (setShortCriticalText) — у Samsung это Now Bar. Остров показывает
 * такие уведомления любого приложения своей капсулой, как «живую активность» iPhone.
 *
 * Проект собирается с compileSdk 35, поэтому методы API 36 вызываются через отражение.
 */
object LiveUpdates {
    /** Notification.FLAG_PROMOTED_ONGOING (API 36) */
    private const val FLAG_PROMOTED_ONGOING = 0x00040000
    /** Notification.EXTRA_REQUEST_PROMOTED_ONGOING (API 36) */
    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
    private const val PROGRESS_STYLE = "android.app.Notification\$ProgressStyle"

    /** Живое обновление: система его подняла или приложение об этом просит (и оно постоянное). */
    fun isLiveUpdate(n: Notification): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        if (n.flags and FLAG_PROMOTED_ONGOING != 0) return true
        val ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0
        return ongoing && n.extras?.getBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, false) == true
    }

    /** Короткий текст для чипа в строке состояния («5 мин», «2 остановки») — Notification.getShortCriticalText(). */
    fun shortText(n: Notification): String? {
        if (Build.VERSION.SDK_INT < 36) return null
        return runCatching { Notification::class.java.getMethod("getShortCriticalText").invoke(n) as? String }
            .getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Прогресс уведомления в стиле ProgressStyle: значение, максимум и «неопределённый ли». */
    class Progress(val value: Int, val max: Int, val indeterminate: Boolean)

    fun progress(context: Context, n: Notification): Progress? {
        if (Build.VERSION.SDK_INT < 36) return null
        if (n.extras?.getString(Notification.EXTRA_TEMPLATE) != PROGRESS_STYLE) return null
        return runCatching {
            val style = Notification.Builder.recoverBuilder(context, n).style ?: return null
            val cls = style.javaClass
            Progress(
                value = cls.getMethod("getProgress").invoke(style) as Int,
                max = cls.getMethod("getProgressMax").invoke(style) as Int,
                indeterminate = cls.getMethod("isProgressIndeterminate").invoke(style) as Boolean,
            )
        }.getOrNull()
    }
}
