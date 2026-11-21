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
import android.content.ComponentName
import android.os.Handler
import android.os.Looper
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
import com.akira.island.core.RingingAlarm
import com.akira.island.data.SettingsRepo

/**
 * Доступ к уведомлениям даёт две вещи: медиасессии всех плееров
 * и уведомления с прогрессом или секундомером (загрузки, Часы, звонки).
 */
class NotifListener : NotificationListenerService() {
    private var media: MediaMonitor? = null
    private val handler = Handler(Looper.getMainLooper())
    private var reconnectAttempts = 0
    private val reconnect = Runnable {
        requestReconnect(this)
    }

    override fun onListenerConnected() {
        SettingsRepo.init(this)
        handler.removeCallbacks(reconnect)
        reconnectAttempts = 0
        connected = this
        media?.stop()
        media = MediaMonitor(applicationContext).also { it.start() }
        // Уже висящие уведомления разбираем без показа сообщений — они не новые
        val existing = runCatching { activeNotifications }.getOrNull()
        runCatching {
            if (existing != null) HotspotMonitor.rescan(this, existing.toList())
            else HotspotMonitor.notificationsUnavailable()
        }
        existing?.forEach { postSafely(it, initial = true) }
    }

    override fun onListenerDisconnected() {
        if (connected === this) HotspotMonitor.notificationsUnavailable()
        if (connected === this) connected = null
        media?.stop()
        media = null
        IslandBus.progress.value = emptyMap()
        IslandBus.chrono.value = emptyMap()
        IslandBus.calls.value = emptyMap()
        IslandBus.live.value = emptyMap()
        IslandBus.ringingAlarms.value = emptySet()
        // The platform may unbind the listener after pressure or a package update. Rebind without
        // revoking access or asking the user to reinstall; cap retries to avoid a restart loop.
        if (reconnectAttempts < 3) {
            reconnectAttempts++
            handler.postDelayed(reconnect, reconnectAttempts * 2_000L)
        }
    }

    override fun onDestroy() {
        if (connected === this) HotspotMonitor.notificationsUnavailable()
        if (connected === this) connected = null
        handler.removeCallbacksAndMessages(null)
        media?.stop()
        media = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var connected: NotifListener? = null
        private var lastReconnectAt = -30_000L

        val isConnected: Boolean get() = connected != null

        /** Called by keep-alive/on-resume only while notification access is already granted. */
        @Synchronized
        fun requestReconnect(context: Context): Boolean {
            if (isConnected) return false
            val now = SystemClock.elapsedRealtime()
            if (now - lastReconnectAt < 30_000L) return false
            lastReconnectAt = now
            return runCatching {
                requestRebind(ComponentName(context, NotifListener::class.java))
                true
            }.getOrDefault(false)
        }

        /** Never offer cancellation for foreground services, calls, or other non-clearable entries. */
        fun canDismiss(key: String): Boolean = runCatching {
            connected?.getActiveNotifications(arrayOf(key))?.any { it.key == key && it.isClearable } == true
        }.getOrDefault(false)

        private val actions = NotificationActions(
            isClearable = ::canDismiss,
            cancel = { key ->
                val service = connected ?: error("Notification listener is disconnected")
                service.cancelNotification(key)
                IslandBus.removeMessage(key)
                Inbox.remove(key)
            },
        )

        /** Android remains the authority: cancellation may be refused by system policy. */
        fun dismiss(key: String): Boolean = actions.dismiss(key)

        fun dismissAll(keys: List<String>): Int = actions.dismissAll(keys)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        postSafely(sbn)
    }

    private fun postSafely(sbn: StatusBarNotification, initial: Boolean = false) {
        runCatching { HotspotMonitor.onPosted(this, sbn) }
        // Third-party extras/icons can be malformed. One bad notification must not take down
        // the notification listener and the accessibility overlay sharing this process.
        runCatching { media?.onNotificationPosted(sbn) }
        runCatching { NotificationParser.onPosted(this, sbn, initial) }.onFailure {
            NotifDebug.add(sbn.packageName, com.akira.island.core.tr("Не удалось прочитать уведомление", "Could not read notification"))
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        HotspotMonitor.onRemoved(sbn.key)
        runCatching { media?.onNotificationRemoved(sbn.key) }
        runCatching { NotificationParser.onRemoved(sbn.key) }
    }
}

object NotificationParser {
    private val icons = HashMap<String, ImageBitmap?>()

    fun onPosted(context: Context, sbn: StatusBarNotification, initial: Boolean = false) {
        if (sbn.packageName == context.packageName) return
        val n = sbn.notification ?: return
        val ex = n.extras ?: return
        val isMedia = mediaNotificationEvidence(n.category,
            ex.containsKey(Notification.EXTRA_MEDIA_SESSION),
            runCatching { ex.getString(Notification.EXTRA_TEMPLATE) }.getOrNull())
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
                val detailText = listOf(Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT, Notification.EXTRA_SUB_TEXT)
                    .mapNotNull { key -> ex.getCharSequence(key)?.takeIf { it.length <= 1024 }?.toString() }
                    .distinct().joinToString("\n")
                put(sbn.key, ProgressInfo(sbn.key, sbn.packageName, title, icon(context, sbn.packageName), value, max, indeterminate,
                    sbn.postTime, TransferDetailsParser.parse(detailText)))
            } else {
                remove(sbn.key)
            }
        }

        val call = when {
            isMedia -> null
            n.category == Notification.CATEGORY_CALL -> parseCall(context, sbn, title)
            // Discord, Zoom, Teams и др. звонят без «звонковой» категории — узнаём их по кнопкам
            VoipApps.isVoip(sbn.packageName) -> parseVoip(context, sbn, title)
            else -> null
        }
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
        // Кнопки звонящего будильника — чтобы отложить или выключить его прямо в острове
        if (ringing) {
            val alarmActions = n.actions.orEmpty()
            fun pick(words: List<String>): PendingIntent? = alarmActions.firstOrNull { a ->
                val t = a.title?.toString()?.lowercase().orEmpty()
                words.any { it in t }
            }?.actionIntent
            IslandBus.ringingAlarm.value = RingingAlarm(sbn.key, sbn.packageName, title, pick(SnoozeWords), pick(DismissAlarmWords), n.contentIntent)
        } else if (IslandBus.ringingAlarm.value?.key == sbn.key) {
            IslandBus.ringingAlarm.value = null
        }
        IslandBus.calls.value = IslandBus.calls.value.toMutableMap().apply {
            if (call != null) put(sbn.key, call) else remove(sbn.key)
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
        // Идущий таймер или секундомер «Часов», у которого время только в тексте (каждую секунду новое), а не
        // системным отсчётом: так у части оболочек (Xiaomi, vivo и др.) — остров ведёт отсчёт сам от этого времени
        val texts = listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_SUB_TEXT, Notification.EXTRA_BIG_TEXT)
        val runningMs = if (clock && !ticking && pausedMs == null && call == null && !isMedia && ClockAction.PAUSE in buttons) {
            texts.firstNotNullOfOrNull { ClockScreenParser.find(ex.getCharSequence(it)) }
        } else {
            null
        }
        // Многие диктофоны дают только время/паузу. Android 14+ также допускает FGS-плашку без ongoing.
        // The recording name may contain a date/time; prefer the live status over its title.
        val recordingTexts = listOf(Notification.EXTRA_TEXT, Notification.EXTRA_SUB_TEXT, Notification.EXTRA_BIG_TEXT,
            Notification.EXTRA_INFO_TEXT, Notification.EXTRA_SUMMARY_TEXT)
            .mapNotNull { ex.getCharSequence(it)?.toString() } +
            ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES).orEmpty().map(CharSequence::toString)
        val recordingElapsed = recordingTexts.firstNotNullOfOrNull { ClockScreenParser.find(it) }
            ?: ClockScreenParser.parse(title.trim())
        val recordingActions = n.actions.orEmpty().filter { it.actionIntent != null }
        val recordingPause = recordingActions.firstOrNull { isPauseRecordingAction(it.title?.toString().orEmpty()) }?.actionIntent
        val recordingResume = recordingActions.firstOrNull { isResumeRecordingAction(it.title?.toString().orEmpty()) }?.actionIntent
        val recordingStop = recordingActions.firstOrNull { isFinishRecordingAction(sbn.packageName, it.title?.toString().orEmpty()) }?.actionIntent
        val recording = classifyRecording(sbn.packageName, title,
            recordingTexts.joinToString(" "), ongoing, recordingStop != null,
            hasChronometer = ticking, hasElapsedTime = recordingElapsed != null,
            hasPauseAction = recordingPause != null, hasResumeAction = recordingResume != null,
            foregroundService = n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0, hasProgress = hasProgress,
            mediaNotification = isMedia,
            hasRecordingAction = recordingActions.any { isSpecificRecordingAction(it.title?.toString().orEmpty()) })
        val recorder = call == null && recording.recording
        // Неактивный (на паузе) таймер, который остров уже убрал, снова не показываем
        val chrono = call == null && !recording.playback &&
            (recorder || !isMedia && (ticking || running || pausedMs != null || runningMs != null)) &&
            !ClockControl.wasDismissed(sbn.key, pausedMs)
        if (clock && chrono) ClockControl.reader?.onNotificationChrono(sbn.packageName)
        if (pausedMs != null && chrono) ClockControl.dropPausedLater(sbn.key)
        IslandBus.chrono.value = IslandBus.chrono.value.toMutableMap().apply {
            if (chrono) {
                val down = when {
                    recorder -> false
                    ticking -> ex.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false)
                    remote?.base != null -> remote.countDown
                    else -> looksLikeTimer(n, title)
                }
                val stop = if (recorder) recordingStop ?: n.contentIntent.takeIf { recording.stopOnTap } else n.actions?.firstOrNull { a ->
                    val t = a.title?.toString()?.lowercase().orEmpty()
                    isStopRecordingAction(t) || listOf("interromp", "ferma", "termina").any { it in t }
                }?.actionIntent
                val now = System.currentTimeMillis()
                val textMs = remote?.textMs
                // Отсчёт: системный — от Notification.when; из раскладки «Часов» — от её Chronometer; из текста — от
                // прочитанного времени; у записи без отсчёта — от времени в тексте или с момента, как появилось уведомление
                val base = when {
                    recorder -> n.`when`.takeIf { ticking && it in 1..now }
                        ?: recordingElapsed?.let { now - it }
                        ?: IslandBus.chrono.value[sbn.key]?.takeIf { it.recordingKind == recording.kind }?.let { previous ->
                            if (!recording.paused && previous.pausedMs != null) now - previous.pausedMs else previous.base
                        }
                        ?: n.`when`.takeIf { it in 1..now } ?: sbn.postTime
                    ticking -> n.`when`
                    remote?.base != null -> remote.base
                    runningMs != null -> if (down) now + runningMs else now - runningMs
                    running && textMs != null -> if (down) now + textMs else now - textMs
                    else -> n.`when`
                }
                put(sbn.key, ChronoInfo(sbn.key, sbn.packageName, title, icon(context, sbn.packageName), base, down, sbn.postTime, stop,
                    pausedMs = if (recorder && recording.paused) recordingElapsed
                        ?: IslandBus.chrono.value[sbn.key]?.elapsed(now) ?: (now - base).coerceAtLeast(0) else pausedMs,
                    pause = if (recorder) recordingPause else buttons[ClockAction.PAUSE],
                    resume = if (recorder) recordingResume else buttons[ClockAction.START],
                    reset = buttons[ClockAction.RESET], recordingKind = recording.kind.takeIf { recorder }, open = n.contentIntent))
            } else {
                remove(sbn.key)
            }
        }

        // Живое обновление с таймером или прогрессом показывается ими, а не карточкой
        val live = if (call == null && !isMedia && !recorder) {
            LiveParser.parse(context, sbn, title)?.takeUnless { it.kind == LiveKind.OTHER && (chrono || hasProgress) }
        } else {
            null
        }
        val liveWasKnown = sbn.key in IslandBus.live.value
        IslandBus.live.value = IslandBus.live.value.toMutableMap().apply {
            if (live != null) put(sbn.key, live) else remove(sbn.key)
        }
        // Новая «живая активность» сначала показывается широкой плашкой, потом сворачивается в капсулу
        if (live != null && !liveWasKnown && !initial && SettingsRepo.current.cardLive && SettingsRepo.current.liveBanner) {
            LiveParser.postBannerLater(context, sbn.key)
        }
        if (recorder) IslandBus.progress.value = IslandBus.progress.value - sbn.key
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
        val ticking = ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false) && n.`when` > 0
        // CallStyle (Android 12+) кладёт кнопки в extras, у остальных звонилок ищем по подписи на языке телефона;
        // «Присоединиться» и «Отключиться» — у звонков в Discord и похожих приложениях
        var answer = pending(ex, "android.answerIntent")
        var decline = pending(ex, "android.declineIntent")
        var hangUp = pending(ex, "android.hangUpIntent")
        var mute: PendingIntent? = null
        var speaker: PendingIntent? = null
        n.actions?.forEach { a ->
            val t = a.title?.toString()?.lowercase().orEmpty()
            when {
                answer == null && (AnswerWords.any { it in t } || JoinWords.any { it in t }) -> answer = a.actionIntent
                decline == null && DeclineWords.any { it in t } -> decline = a.actionIntent
                hangUp == null && (HangUpWords.any { it in t } || LeaveWords.any { it in t }) -> hangUp = a.actionIntent
                mute == null && MuteWords.any { it in t } -> mute = a.actionIntent
                speaker == null && SpeakerWords.any { it in t } -> speaker = a.actionIntent
            }
        }
        // Идёт разговор: системный отсчёт или постоянное уведомление с кнопкой «Завершить» и без «Ответить»
        val ongoing = ticking || (n.flags and Notification.FLAG_ONGOING_EVENT != 0 && hangUp != null && answer == null)
        val incoming = !ongoing && answer != null
        if (!incoming && !ongoing) return null

        // Chi chiama: nome, foto e contatto (Person esiste da Android 12 nelle notifiche di chiamata)
        var personName: String? = null
        var personUri: String? = null
        var personIcon: android.graphics.drawable.Icon? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            @Suppress("DEPRECATION")
            val person = runCatching { ex.getParcelable<Person>(Notification.EXTRA_CALL_PERSON) }.getOrNull()
            personName = person?.name?.toString()
            personUri = person?.uri
            personIcon = person?.icon
        }
        val name = personName?.takeIf { it.isNotBlank() } ?: title
        fun load(icon: android.graphics.drawable.Icon?) = runCatching {
            icon?.loadDrawable(context)?.toBitmap(192, 192)?.asImageBitmap()
        }.getOrNull()
        val contacts = CallMonitor.granted(context)
        // Настоящее фото из контактов — первым: у многих звонилок значок уведомления — просто буква на круге
        val avatar = (if (contacts) CallMonitor.photoByUri(context, personUri) else null)
            ?: (if (contacts) CallMonitor.photoByName(context, name) else null)
            ?: load(personIcon)
            ?: load(n.getLargeIcon())
        return CallInfo(
            key = sbn.key,
            pkg = sbn.packageName,
            name = name,
            avatar = avatar,
            incoming = incoming,
            // Без системного отсчёта время разговора идёт с момента, как появилось уведомление
            base = if (ticking) n.`when` else n.`when`.takeIf { it in 1..System.currentTimeMillis() } ?: sbn.postTime,
            postTime = sbn.postTime,
            answer = answer,
            decline = decline ?: hangUp,
            hangUp = hangUp ?: decline,
            open = n.contentIntent,
            mute = mute,
            speaker = speaker,
        )
    }

    /**
     * Звонок в Discord, Zoom, Teams и др.: у них нет категории звонка, поэтому узнаём по кнопкам. Входящий —
     * есть «Принять» («Присоединиться») и «Отклонить»; идёт разговор — постоянное уведомление с «Отключиться»
     * («Выйти», «Завершить»).
     */
    private fun parseVoip(context: Context, sbn: StatusBarNotification, title: String): CallInfo? {
        val n = sbn.notification
        var answer: PendingIntent? = null
        var decline: PendingIntent? = null
        var hangUp: PendingIntent? = null
        n.actions?.forEach { a ->
            val t = a.title?.toString()?.lowercase().orEmpty()
            when {
                answer == null && (AnswerWords.any { it in t } || JoinWords.any { it in t }) -> answer = a.actionIntent
                decline == null && DeclineWords.any { it in t } -> decline = a.actionIntent
                hangUp == null && (HangUpWords.any { it in t } || LeaveWords.any { it in t }) -> hangUp = a.actionIntent
            }
        }
        val ongoingFlag = n.flags and Notification.FLAG_ONGOING_EVENT != 0
        val incoming = answer != null && (decline != null || hangUp != null)
        val talking = !incoming && hangUp != null && ongoingFlag
        if (!incoming && !talking) return null
        val avatar = runCatching { n.getLargeIcon()?.loadDrawable(context)?.toBitmap(128, 128)?.asImageBitmap() }.getOrNull()
        val now = System.currentTimeMillis()
        return CallInfo(
            key = sbn.key,
            pkg = sbn.packageName,
            name = title.ifBlank { appLabel(context, sbn.packageName) },
            avatar = avatar,
            incoming = incoming,
            base = n.`when`.takeIf { it in 1..now } ?: sbn.postTime,
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
        // Прочитали в самом приложении — из «Истории уведомлений» уходит, а плашка, которую держат дольше, — тоже
        Inbox.remove(key)
        Messages.onRemoved(key)
        LiveParser.forget(key)
        ClockControl.forget(key)
        LiveDebug.forget(key)
        if (key in IslandBus.calls.value) IslandBus.calls.value = IslandBus.calls.value - key
        if (key in IslandBus.progress.value) IslandBus.progress.value = IslandBus.progress.value - key
        if (key in IslandBus.chrono.value) IslandBus.chrono.value = IslandBus.chrono.value - key
        if (key in IslandBus.live.value) IslandBus.live.value = IslandBus.live.value - key
        if (key in IslandBus.ringingAlarms.value) IslandBus.ringingAlarms.value = IslandBus.ringingAlarms.value - key
        if (IslandBus.ringingAlarm.value?.key == key) IslandBus.ringingAlarm.value = null
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

/** Приложения звонков без «звонковой» категории уведомлений: звонок узнаётся по кнопкам. */
object VoipApps {
    private val known = setOf(
        "com.discord",
        "us.zoom.videomeetings",
        "com.microsoft.teams",
        "com.skype.raider",
        "com.google.android.apps.meetings",
        "com.google.android.apps.tachyon",
        "jp.naver.line.android",
        "com.viber.voip",
    )

    fun isVoip(pkg: String) = pkg in known
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
    "ответ", "принять", "жауап", "қабылдау", "answer", "accept", "trả lời", "nghe", "jawab", "terima", "angkat", "contestar", "responder",
    "atender", "aceptar", "aceitar", "รับสาย", "cevapla", "yanıtla", "kabul", "annehmen", "répondre", "rispondi", "accetta",
    "odbierz", "відповісти", "прийняти", "sagutin", "接听", "接聽", "応答", "응답", "받기",
)
private val DeclineWords = listOf(
    "отклон", "сброс", "қабылдамау", "бас тарту", "decline", "reject", "dismiss", "từ chối", "tolak", "rechazar", "recusar", "rejeitar", "ปฏิเสธ",
    "reddet", "ablehnen", "refuser", "rifiuta", "ignora", "odrzuć", "відхилити", "tanggihan", "拒接", "拒絕", "拒否", "거절",
)
private val HangUpWords = listOf(
    "заверш", "положить", "hang", "end call", "kết thúc", "gác máy", "akhiri", "colgar", "finalizar", "desligar",
    "encerrar", "วางสาย", "kapat", "auflegen", "beenden", "raccrocher", "riaggancia", "termina", "chiudi chiamata", "attacca", "zakończ", "завершити", "ibaba",
    "挂断", "掛斷", "終了", "종료", "аяқтау",
)

/** «Присоединиться» к звонку (Discord и др.) — как «Принять» */
private val JoinWords = listOf("join", "присоедин", "gabung", "entrar", "participar", "қосылу", "beitreten", "rejoindre", "unirse")

/** «Отключиться», «Выйти» из звонка (Discord, Zoom и др.) — как «Завершить» */
private val LeaveWords = listOf(
    "disconnect", "leave", "отключ", "выйти", "покинуть", "putus", "keluar", "tinggalkan", "desconectar", "sair", "ажырату", "шығу",
    "trennen", "verlassen", "quitter", "salir",
)

/** Кнопки «Без звука» и «Динамик» в уведомлении идущего разговора */
private val MuteWords = listOf(
    "mute", "muto", "microfono", "без звука", "микрофон", "silenciar", "stumm", "micro", "sessiz",
)
private val SpeakerWords = listOf(
    "speaker", "vivavoce", "altoparlante", "динамик", "громк", "altavoz", "alto-falante", "lautsprecher", "haut-parleur", "hoparlör",
)

/** Кнопки звонящего будильника «Часов» */
private val SnoozeWords = listOf(
    "отлож", "повтор", "snooze", "posponi", "rimanda", "ripeti", "posponer", "aplazar", "adiar", "soneca", "schlummer", "répéter", "ertele",
)
private val DismissAlarmWords = listOf(
    "выключ", "отклон", "стоп", "dismiss", "stop", "turn off", "ferma", "disattiva", "ignora", "chiudi", "descartar", "detener", "parar",
    "desativar", "beenden", "aus", "arrêt", "kapat",
)
