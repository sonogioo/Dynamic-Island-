package com.akira.island.core

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalConfiguration
import com.akira.island.data.AppLanguage
import com.akira.island.data.SettingsRepo
import java.util.Locale

/**
 * Язык приложения и острова: русский, английский или итальянский. «Как в системе» — русский или итальянский,
 * если телефон на нём, иначе английский. Строки пишутся парой прямо в коде: tr("Зарядка", "Charging");
 * итальянский берётся по английской строке из словаря Italian.
 */
object Lang {
    /** Не русский: английский или итальянский (у итальянского недостающие строки — английские) */
    val en: Boolean
        get() = when (SettingsRepo.current.language) {
            AppLanguage.EN, AppLanguage.IT -> true
            AppLanguage.RU -> false
            AppLanguage.SYSTEM -> systemLanguage() != "ru"
        }

    val it: Boolean
        get() = when (SettingsRepo.current.language) {
            AppLanguage.IT -> true
            AppLanguage.SYSTEM -> systemLanguage() == "it"
            else -> false
        }

    /** Для дат: «пятница, 24 октября», «venerdì 24 ottobre» или «Friday, October 24» */
    val locale: Locale get() = when {
        it -> Locale.ITALIAN
        en -> Locale.ENGLISH
        else -> Locale.forLanguageTag("ru")
    }

    private fun systemLanguage(): String =
        runCatching { Resources.getSystem().configuration.locales[0].language }.getOrNull() ?: Locale.getDefault().language
}

/** Строка на текущем языке. */
fun tr(ru: String, en: String): String = when {
    Lang.it -> Italian.words[en] ?: en
    Lang.en -> en
    else -> ru
}

/**
 * Сменили язык (в настройках или в системе) — всё внутри рисуется заново уже на новом языке.
 * Состояние снаружи (открытая вкладка и экран) остаётся.
 */
@Composable
fun LangScope(content: @Composable () -> Unit) {
    val s by SettingsRepo.state.collectAsState()
    // Язык системы меняется вместе с конфигурацией — чтение подписывает на неё
    LocalConfiguration.current
    key(s.language, Lang.en) { content() }
}
