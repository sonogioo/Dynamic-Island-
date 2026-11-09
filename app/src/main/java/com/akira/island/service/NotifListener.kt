package com.akira.island.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.os.Build
import android.os.SystemClock
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.TextView
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import com.akira.island.core.CallInfo
import com.akira.island.core.ChronoInfo
import com.akira.island.core.IslandBus
import com.akira.island.core.LiveKind
import com.akira.island.core.ProgressInfo
import com.akira.island.data.SettingsRepo

/**
 * Доступ к уведомлениям даёт две вещи: медиасессии всех плееров
 * и уведомления с прогрессом или секундомером (загрузки, Часы, звонки).
 */
class NotifListener : NotificationListenerService() {
    private var media: MediaMonitor? = null

    override fun onListenerConnected() {
        SettingsRepo.init(this)
        media = MediaMonitor(applicationContext).also { it.start() }
        // Уже висящие уведомления разбираем без показа сообщений — они не новые
        runCatching { activeNotifications?.forEach { NotificationParser.onPosted(this, it, initial = true) } }
    }

    override fun onListenerDisconnected() {
        media?.stop()
        media = null
        IslandBus.progress.value = emptyMap()
        IslandBus.chrono.value = emptyMap()
        IslandBus.calls.value = emptyMap()
        IslandBus.live.value = emptyMap()
        IslandBus.ringingAlarms.value = emptySet()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        NotificationParser.onPosted(this, sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        NotificationParser.onRemoved(sbn.key)
    }
}

object NotificationParser {
    private val icons = HashMap<String, ImageBitmap?>()

    fun onPosted(context: Context, sbn: StatusBarNotification, initial: Boolean = false) {
        if (sbn.packageName == context.packageName) return
        val n = sbn.notification ?: return
        val ex = n.extras ?: return
        val isMedia = n.category == Notification.CATEGORY_TRANSPORT || ex.containsKey(Notification.EXTRA_MEDIA_SESSION)
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: appLabel(context, sbn.packageName)

        // ProgressStyle Android 16 хранит прогресс в самом стиле — достаём его, если в extras пусто
        val styled = if (!ex.containsKey(Notification.EXTRA_PROGRESS_MAX)) LiveUpdates.progress(context, n) else null
        val max = styled?.max ?: ex.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val value = styled?.value ?: ex.getInt(Notification.EXTRA_PROGRESS, 0)
        val indeterminate = styled?.indeterminate ?: ex.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
        val ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0
        val hasProgress = !isMedia && ((max > 0 && value < max) || (indeterminate && ongoing))
        IslandBus.progress.value = IslandBus.progress.value.toMutableMap().apply {
            if (hasProgress) {
                put(sbn.key, ProgressInfo(sbn.key, sbn.packageName, title, icon(context, sbn.packageName), value, max, indeterminate, sbn.postTime))
            } else {
                remove(sbn.key)
            }
        }

        val call = if (n.category == Notification.CATEGORY_CALL && !isMedia) parseCall(context, sbn, title) else null
        // Какой канал присылает всплывающую плашку звонка или будильника — для экрана «Системные плашки»
        SystemBanners.onPosted(sbn, incomingCall = call?.incoming == true)
        // Звонящий будильник или таймер «Часов» — у него полноэкранный сигнал; остров включит экран сам,
        // даже если всплывающие плашки «Часов» выключены
        // Истёкший таймер «Часов» (например, Samsung) может прийти без категории будильника — у «Часов» хватает полноэкранного сигнала
        val clockApp = ClockApps.isClock(context, sbn.packageName)
        val ringing = n.fullScreenIntent != null && call == null &&
            (n.category == Notification.CATEGORY_ALARM || clockApp && n.category != Notification.CATEGORY_CALL)
        if (ringing != (sbn.key in IslandBus.ringingAlarms.value)) {
            IslandBus.ringingAlarms.value = if (ringing) IslandBus.ringingAlarms.value + sbn.key else IslandBus.ringingAlarms.value - sbn.key
        }
        IslandBus.calls.value = IslandBus.calls.value.toMutableMap().apply {
            if (call != null) put(sbn.key, call) else remove(sbn.key)
            // Звонок Samsung уже пришёл по состоянию телефона — оставляем только уведомление с кнопками
            if (call != null && CallMonitor.isSamsungDialer(call.pkg)) remove(CallMonitor.KEY)
        }

        val ticking = ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false) && n.`when` > 0
        // Кнопки «Часов» (пауза, продолжить, сброс) — их нажимают кнопки острова
        val clock = clockApp
        val buttons = if (clock) ClockWords.buttons(n.actions) else emptyMap()
        // «Часы», которые рисуют таймер и секундомер своей раскладкой (RemoteViews), не ставят EXTRA_SHOW_CHRONOMETER:
        // счёт идёт в виджете Chronometer или в тексте — читаем саму раскладку
        val remote = if (clock && !ticking && call == null && !isMedia && sbn.isOngoing) ClockRemote.read(context, n) else null
        val pausedByButtons = ClockAction.START in buttons && ClockAction.PAUSE !in buttons
        val running = remote != null && !pausedByButtons && (remote.base != null || ClockAction.PAUSE in buttons)
        // Таймер или секундомер «Часов» на паузе: счёт стоит, время только в тексте, а в кнопках — «Продолжить»
        val pausedMs = if (clock && !ticking && !running && call == null && !isMedia && ClockAction.START in buttons) {
            listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_SUB_TEXT, Notification.EXTRA_BIG_TEXT)
                .firstNotNullOfOrNull { ClockScreenParser.find(ex.getCharSequence(it)) } ?: remote?.textMs
        } else {
            null
        }
        // Неактивный (на паузе) таймер, который остров уже убрал, снова не показываем
        val chrono = call == null && !isMedia && (ticking || running || pausedMs != null) && !ClockControl.wasDismissed(sbn.key, pausedMs)
        if (clock && chrono) ClockControl.reader?.onNotificationChrono(sbn.packageName)
        if (pausedMs != null && chrono) ClockControl.dropPausedLater(sbn.key)
        IslandBus.chrono.value = IslandBus.chrono.value.toMutableMap().apply {
            if (chrono) {
                val down = when {
                    ticking -> ex.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false)
                    remote?.base != null -> remote.countDown
                    else -> looksLikeTimer(n, title)
                }
                // Начало секундомера или конец таймера по настенным часам, как у Notification.when
                val textMs = remote?.textMs
                val start = when {
                    ticking -> n.`when`
                    remote?.base != null -> remote.base
                    running && textMs != null -> System.currentTimeMillis() + if (down) textMs else -textMs
                    else -> n.`when`
                }
                val stop = n.actions?.firstOrNull { a ->
                    val t = a.title?.toString()?.lowercase().orEmpty()
                    listOf("стоп", "останов", "заверш", "stop", "finish", "interromp", "ferma", "termina").any { it in t }
                }?.actionIntent
                put(sbn.key, ChronoInfo(sbn.key, sbn.packageName, title, icon(context, sbn.packageName), start, down, sbn.postTime, stop,
                    pausedMs = pausedMs, pause = buttons[ClockAction.PAUSE], resume = buttons[ClockAction.START],
                    reset = buttons[ClockAction.RESET]))
            } else {
                remove(sbn.key)
            }
        }

        // Живое обновление с таймером или прогрессом показывается ими, а не карточкой
        val live = if (call == null && !isMedia) {
            LiveParser.parse(context, sbn, title)?.takeUnless { it.kind == LiveKind.OTHER && (chrono || hasProgress) }
        } else {
            null
        }
        val liveWasKnown = sbn.key in IslandBus.live.value
        IslandBus.live.value = IslandBus.live.value.toMutableMap().apply {
            if (live != null) put(sbn.key, live) else remove(sbn.key)
        }
        // Новая «живая активность» сначала показывается широкой плашкой, потом сворачивается в капсулу
        if (live != null && !liveWasKnown && !initial && SettingsRepo.current.cardLive) {
            LiveParser.postBannerLater(context, sbn.key)
        }
        // Навигатор и такси показываются своей карточкой, а не полоской прогресса или таймером
        if (live != null) {
            IslandBus.progress.value = IslandBus.progress.value - sbn.key
            IslandBus.chrono.value = IslandBus.chrono.value - sbn.key
        }

        val service = context as? NotificationListenerService
        // Висевшие до запуска переписки запоминаем, чтобы при следующем обновлении они не всплыли как новые
        if (initial) Messages.markSeen(sbn)
        if (!initial && service != null && !isMedia && call == null && !chrono && !hasProgress && live == null) {
            // Уведомление об оплате из банка — «Оплачено» с галочкой, а не карточка сообщения
            if (PayMonitor.onNotification(context, sbn)) return
            Messages.maybeShow(service, sbn, appLabel(context, sbn.packageName))
        }
    }

    private fun parseCall(context: Context, sbn: StatusBarNotification, title: String): CallInfo? {
        val n = sbn.notification
        val ex = n.extras
        val ongoing = ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false) && n.`when` > 0
        // CallStyle (Android 12+) кладёт кнопки в extras, у остальных звонилок ищем по подписи на языке телефона
        var answer = pending(ex, "android.answerIntent")
        var decline = pending(ex, "android.declineIntent")
        var hangUp = pending(ex, "android.hangUpIntent")
        n.actions?.forEach { a ->
            val t = a.title?.toString()?.lowercase().orEmpty()
            when {
                answer == null && AnswerWords.any { it in t } -> answer = a.actionIntent
                decline == null && DeclineWords.any { it in t } -> decline = a.actionIntent
                hangUp == null && HangUpWords.any { it in t } -> hangUp = a.actionIntent
            }
        }
        val incoming = !ongoing && answer != null
        if (!incoming && !ongoing) return null

        val person = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            @Suppress("DEPRECATION")
            runCatching { ex.getParcelable<Person>(Notification.EXTRA_CALL_PERSON) }.getOrNull()
        } else {
            null
        }
        val name = person?.name?.toString()?.takeIf { it.isNotBlank() } ?: title
        val avatar = runCatching {
            val icon = person?.icon ?: n.getLargeIcon()
            icon?.loadDrawable(context)?.toBitmap(128, 128)?.asImageBitmap()
        }.getOrNull()
        return CallInfo(
            key = sbn.key,
            pkg = sbn.packageName,
            name = name,
            avatar = avatar,
            incoming = incoming,
            base = n.`when`,
            postTime = sbn.postTime,
            answer = answer,
            decline = decline ?: hangUp,
            hangUp = hangUp ?: decline,
            open = n.contentIntent,
        )
    }

    /** Таймер на паузе или секундомер — по заголовку и каналу; по умолчанию таймер. */
    private fun looksLikeTimer(n: Notification, title: String): Boolean {
        val t = (title + " " + n.channelId.orEmpty()).lowercase()
        return when {
            "секундомер" in t || "stopwatch" in t || "cronometro" in t -> false
            else -> true
        }
    }

    @Suppress("DEPRECATION")
    private fun pending(ex: android.os.Bundle, key: String): PendingIntent? =
        runCatching { ex.getParcelable<PendingIntent>(key) }.getOrNull()

    fun onRemoved(key: String) {
        Messages.forget(key)
        LiveParser.forget(key)
        ClockControl.forget(key)
        LiveDebug.forget(key)
        if (key in IslandBus.calls.value) IslandBus.calls.value = IslandBus.calls.value - key
        if (key in IslandBus.progress.value) IslandBus.progress.value = IslandBus.progress.value - key
        if (key in IslandBus.chrono.value) IslandBus.chrono.value = IslandBus.chrono.value - key
        if (key in IslandBus.live.value) IslandBus.live.value = IslandBus.live.value - key
        if (key in IslandBus.ringingAlarms.value) IslandBus.ringingAlarms.value = IslandBus.ringingAlarms.value - key
    }

    fun icon(context: Context, pkg: String): ImageBitmap? = icons.getOrPut(pkg) {
        runCatching { context.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
    }

    fun appLabel(context: Context, pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}

/**
 * Таймер и секундомер из своей раскладки уведомления (RemoteViews) — на случай, если «Часы» (например, Samsung)
 * присылают их так, а не через EXTRA_SHOW_CHRONOMETER.
 * Раскладку можно развернуть у себя: RemoteViews для того и передаются шторке и слушателям уведомлений.
 */
object ClockRemote {
    /** [base] — начало секундомера или конец таймера по настенным часам, если в раскладке есть Chronometer */
    class Reading(val base: Long?, val countDown: Boolean, val textMs: Long?)

    fun read(context: Context, n: Notification): Reading? {
        val rv = n.contentView ?: n.bigContentView ?: n.headsUpContentView ?: return null
        return runCatching {
            val root = rv.apply(context, FrameLayout(context))
            var chrono: Chronometer? = null
            var textMs: Long? = null
            fun walk(v: View) {
                if (v is Chronometer && chrono == null) chrono = v
                if (v is TextView && textMs == null) textMs = ClockScreenParser.find(v.text)
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(root)
            val c = chrono
            // Chronometer считает от elapsedRealtime — переводим в настенное время
            val base = c?.let { System.currentTimeMillis() + (it.base - SystemClock.elapsedRealtime()) }
            Reading(base, c?.isCountDown == true, textMs)
        }.getOrNull()?.takeIf { it.base != null || it.textMs != null }
    }
}

/** Приложения, чьи постоянные уведомления остров показывает как «живые активности». */
object LiveApps {
    private val nav = setOf(
        "com.google.android.apps.maps",
        "ru.yandex.yandexmaps",
        "ru.yandex.yandexnavi",
        "ru.dublgis.dgismobile",
        "com.waze",
        "net.osmand",
        "net.osmand.plus",
        "com.here.app.maps",
        "cz.seznam.mapy",
        "app.organicmaps",
        "com.mapswithme.maps.pro",
    )
    private val ride = setOf(
        "ru.yandex.taxi",
        "com.yandex.go",
        "com.ubercab",
        "ee.mtakso.client",
        "ru.citymobil",
        "sinet.startup.indriver",
        "com.lyft.android",
    )
    private val delivery = setOf(
        "ru.yandex.eda",
        "ru.foodfox.client",
        "ru.samokat.app",
        "ru.instamart",
        "ru.sbermarket",
        "com.deliveryclub",
        "com.ubercab.eats",
        "ru.ozon.app.android",
        "com.wildberries.ru",
        "ru.beru.android",
    )

    fun kind(pkg: String, category: String?): LiveKind? = when {
        pkg in nav || category == Notification.CATEGORY_NAVIGATION -> LiveKind.NAV
        pkg in ride -> LiveKind.RIDE
        pkg in delivery -> LiveKind.DELIVERY
        else -> null
    }
}

/**
 * Подписи кнопок звонка на разных языках: остров смотрят во Вьетнаме, Индонезии, Таиланде, Бразилии,
 * Латинской Америке и не только — без них ответить и сбросить из острова там не получалось.
 */
private val AnswerWords = listOf(
    "ответ", "принять", "answer", "accept", "trả lời", "nghe", "jawab", "terima", "angkat", "contestar", "responder",
    "atender", "aceptar", "aceitar", "รับสาย", "cevapla", "yanıtla", "kabul", "annehmen", "répondre", "rispondi", "accetta",
    "odbierz", "відповісти", "прийняти", "sagutin", "接听", "接聽", "応答", "응답", "받기",
)
private val DeclineWords = listOf(
    "отклон", "сброс", "decline", "reject", "dismiss", "từ chối", "tolak", "rechazar", "recusar", "rejeitar", "ปฏิเสธ",
    "reddet", "ablehnen", "refuser", "rifiuta", "odrzuć", "відхилити", "tanggihan", "拒接", "拒絕", "拒否", "거절",
)
private val HangUpWords = listOf(
    "заверш", "положить", "hang", "end call", "kết thúc", "gác máy", "akhiri", "colgar", "finalizar", "desligar",
    "encerrar", "วางสาย", "kapat", "auflegen", "beenden", "raccrocher", "riaggancia", "zakończ", "завершити", "ibaba",
    "挂断", "掛斷", "終了", "종료",
)
