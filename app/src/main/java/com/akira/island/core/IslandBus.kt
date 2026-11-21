package com.akira.island.core

import android.os.SystemClock
import com.akira.island.data.SettingsRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Общее состояние острова. Службы пишут сюда, капсула и приложение читают.
 * Всё живёт в одном процессе, поэтому хватает обычного синглтона.
 */
object IslandBus {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val serviceRunning = MutableStateFlow(false)
    val expanded = MutableStateFlow(false)
    /** id активности, которую пользователь выбрал (например, кликом по пузырю) */
    val focus = MutableStateFlow<String?>(null)

    val media = MutableStateFlow<MediaInfo?>(null)
    val lyrics = MutableStateFlow<Lyrics?>(null)
    /** Как идёт поиск текста для трека с ключом first */
    val lyricsStatus = MutableStateFlow<Pair<String, LyricsStatus>?>(null)
    val progress = MutableStateFlow<Map<String, ProgressInfo>>(emptyMap())
    val chrono = MutableStateFlow<Map<String, ChronoInfo>>(emptyMap())
    val calls = MutableStateFlow<Map<String, CallInfo>>(emptyMap())
    /** Входящий звонок смахнули — в острове его нет, пока он не закончится или не станет разговором */
    val callHidden = MutableStateFlow(false)
    /** Что может сделать только сама служба спец. возможностей; без службы команда теряется */
    val commands = MutableSharedFlow<ServiceCommand>(extraBufferCapacity = 4)
    val live = MutableStateFlow<Map<String, LiveInfo>>(emptyMap())
    /** Экран оплаты телефоном на виду — «Оплата · К терминалу», пока не заплатили или не ушли (PayMonitor) */
    val payReady = MutableStateFlow<PayReady?>(null)
    /** Ближайший будильник любого приложения «Часы» */
    val nextAlarm = MutableStateFlow<AlarmInfo?>(null)
    /** Звонящие будильники и таймеры других приложений («Часы»): ключи их уведомлений */
    val ringingAlarms = MutableStateFlow<Set<String>>(emptySet())
    /** Звонящий будильник с кнопками «Отложить» и «Выключить» — для острова */
    val ringingAlarm = MutableStateFlow<RingingAlarm?>(null)
    val torch = MutableStateFlow(TorchState())
    val timer = MutableStateFlow<TimerState?>(null)
    val stopwatch = MutableStateFlow<StopwatchState?>(null)
    val notice = MutableStateFlow<Notice?>(null)
    /** User expanded the compact notification to read it inside the island. */
    val messageDetail = MutableStateFlow(false)
    /** «История уведомлений»: показанные сообщения, которые ещё ждут в острове */
    val inbox = MutableStateFlow<List<InboxItem>>(emptyList())
    /** Идёт ввод ответа на уведомление: окну острова нужен фокус, чтобы показать клавиатуру */
    val reply = MutableStateFlow<ReplyState?>(null)
    /** Обе оси наклона от -1 до 1, в координатах острова. */
    val glassTilt = MutableStateFlow(GlassTilt.Zero)
    /** До какого момента (elapsedRealtime) показывать пример активности — пока двигают ползунок ширины */
    val widthPreviewUntil = MutableStateFlow(0L)
    /** Человек читает или отвечает — уведомление не убирается по таймеру */
    val noticeHold = MutableStateFlow(false)
    /** У какого уведомления (id) показаны кнопки: как на iPhone, они появляются, если потянуть баннер вниз или удержать */
    val noticeActions = MutableStateFlow<Long?>(null)
    val battery = MutableStateFlow(BatteryInfo())
    /** Режим звука (AudioManager.RINGER_MODE_*) и «Не беспокоить» — для кнопок раскрытого острова */
    val ringer = MutableStateFlow(2)
    val dnd = MutableStateFlow(false)
    val foregroundPackage = MutableStateFlow<String?>(null)
    /** Expanded notification shade/quick settings detected by the accessibility service. */
    val shadeOpen = MutableStateFlow(false)
    val landscape = MutableStateFlow(false)
    /** Поворот экрана для острова «У камеры»: −90 — камера слева, 90 — справа, 0 — портрет */
    val displayRotation = MutableStateFlow(0)
    /** На сколько градусов повёрнут остров в своём окне сейчас (0 — как обычно, сверху) */
    val contentRotation = MutableStateFlow(0)
    /** Вне часов «По расписанию» остров не показывается */
    val offSchedule = MutableStateFlow(false)
    /** Экран включён. Пока он погашен, датчик наклона и чтение экрана навигатора не работают — бережём батарею */
    val screenOn = MutableStateFlow(true)
    /** Включено энергосбережение Android — с «Экономией заряда» остров без живых эффектов */
    val powerSave = MutableStateFlow(false)
    /** Телефон заблокирован PIN-кодом, графическим ключом или паролем: тексты сообщений и кнопки скрыты */
    val locked = MutableStateFlow(false)
    /** На экране — экран блокировки (с PIN-кодом или без): в капсуле замок, если он включён в «Событиях» */
    val keyguard = MutableStateFlow(false)
    /** Открыт экран «Размер и положение»: остров красный, чтобы подогнать его под камеру */
    val calibrating = MutableStateFlow(false)
    /** Играет та самая песня — остров празднует (пасхалка, Egg.kt) */
    val egg = MutableStateFlow(false)
    /** Последнее касание раскрытого острова — чтобы не сворачивать его из-под пальца */
    @Volatile
    var lastTouch = 0L

    /**
     * Очередь плашек. Сообщения одной переписки не копятся друг за другом: новое заменяет карточку
     * на экране или в очереди (в ней и так вся переписка), а сообщение из другой переписки
     * поторапливает показанное — как баннеры iPhone, где свежее уведомление не ждёт старых.
     */
    private data class QueuedNotice(val notice: Notice, val revision: Long)
    private val pending = ArrayDeque<QueuedNotice>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    /** Показанную карточку обновили новым сообщением той же переписки — время показа считается заново */
    private val refreshed = MutableStateFlow(0L)
    /** Пришло сообщение из другой переписки — показанное сообщение уступает ему место */
    private val hurry = MutableStateFlow(0L)
    /** Сколько событий отправлено в очередь — по нему плашка «пока не смахнёте» узнаёт, что пришло следующее */
    private val posted = MutableStateFlow(0)
    private const val MAX_PENDING = 6
    /** Сколько сообщение видно хотя бы, даже если за ним уже ждёт следующее */
    private const val MIN_SHOWN_MS = 1_500L
    /** Уведомление убрали в шторке — его копии в очереди больше не показываются */
    private val messageRevisions = object : LinkedHashMap<String, Long>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 256
    }

    private fun revisionOf(key: String?): Long = if (key == null) 0L else synchronized(messageRevisions) { messageRevisions[key] ?: 0L }

    init {
        scope.launch {
            while (true) {
                val queued = synchronized(pending) { pending.removeFirstOrNull() }
                if (queued == null) {
                    wake.receive()
                    continue
                }
                val n = queued.notice
                val key = n.message?.key
                fun removed() = key != null && revisionOf(key) != queued.revision
                if (removed()) continue
                val s = SettingsRepo.current
                // «Задержка появления»; подтверждения того, что вы только что сделали (отправлено, оплачено), — сразу
                if (s.noticeDelayMs > 0 && n.style != NoticeStyle.SENT && n.style != NoticeStyle.PAY_DONE) delay(s.noticeDelayMs.toLong())
                if (removed()) continue
                show(n)
                // Короткая пауза между плашками, чтобы они не слипались, — но без заметного ожидания
                delay(110)
            }
        }
    }

    private fun durationOf(n: Notice): Long {
        val s = SettingsRepo.current
        val message = n.style == NoticeStyle.MESSAGE
        return if (message && s.messageSeconds > 0) s.messageSeconds * 1000L else s.noticeDurationMs + n.extraMs
    }

    private suspend fun show(n: Notice) {
        messageDetail.value = false
        noticeHold.value = false
        notice.value = n
        var shownAt = SystemClock.elapsedRealtime()
        while (true) {
            val stamp = refreshed.value
            val hurried = hurry.value
            val duration = durationOf(notice.value ?: n)
            // Уведомление уходит по времени или раньше, если его смахнули кликом
            withTimeoutOrNull(duration) {
                merge(notice.filter { it?.id != n.id }, refreshed.filter { it != stamp }, hurry.filter { it != hurried }).first()
            }
            if (notice.value?.id != n.id) return
            if (refreshed.value != stamp) {
                shownAt = SystemClock.elapsedRealtime()
                continue
            }
            if (hurry.value != hurried && !noticeHold.value) {
                val left = MIN_SHOWN_MS - (SystemClock.elapsedRealtime() - shownAt)
                if (left > 0) withTimeoutOrNull(left) { notice.first { it?.id != n.id } }
                // Пока ждали, пришло ещё сообщение этой же переписки — оно важнее очереди
                if (refreshed.value != stamp && notice.value?.id == n.id) {
                    shownAt = SystemClock.elapsedRealtime()
                    continue
                }
                break
            }
            // «Пока не смахнёте»: плашка ждёт, пока её не уберут; пришло следующее событие — уступает ему
            if (n.style == NoticeStyle.MESSAGE && SettingsRepo.current.messageSeconds < 0 && waitingIsEmpty()) {
                val seen = posted.value
                combine(notice, posted, refreshed) { cur, p, r -> cur?.id != n.id || p != seen || r != stamp }.first { it }
                if (notice.value?.id != n.id) return
                if (refreshed.value != stamp) {
                    shownAt = SystemClock.elapsedRealtime()
                    continue
                }
            }
            break
        }
        // Пока отвечают или листают переписку, карточка остаётся; потом ещё чуть-чуть
        if (notice.value?.id == n.id && noticeHold.value) {
            combine(notice, noticeHold) { current, holding -> current?.id != n.id || !holding }.first { it }
            if (notice.value?.id == n.id) withTimeoutOrNull(1500) { notice.first { it?.id != n.id } }
        }
        if (notice.value?.id == n.id) notice.value = null
    }

    fun post(n: Notice) {
        val key = n.message?.key
        posted.value++
        if (key != null) {
            val current = notice.value
            if (current?.message?.key == key) {
                // Новое сообщение той же переписки: карточка обновляется на месте, ввод ответа не сбрасывается
                notice.value = n.copy(id = current.id)
                refreshed.value++
                return
            }
        }
        val queued = QueuedNotice(n, revisionOf(key))
        synchronized(pending) {
            val index = if (key == null) -1 else pending.indexOfFirst { it.notice.message?.key == key }
            if (index >= 0) {
                pending[index] = queued
                return
            }
            if (pending.size >= MAX_PENDING) pending.removeFirst()
            pending.addLast(queued)
        }
        if (key != null && notice.value?.style == NoticeStyle.MESSAGE) hurry.value++
        wake.trySend(Unit)
    }

    /** Invalidate queued copies too, so a notification dismissed in the shade cannot reappear later. */
    fun removeMessage(key: String) {
        synchronized(messageRevisions) { messageRevisions[key] = (messageRevisions[key] ?: 0L) + 1L }
        synchronized(pending) { pending.removeAll { it.notice.message?.key == key } }
        if (notice.value?.message?.key == key) {
            notice.value = null
            messageDetail.value = false
            noticeHold.value = false
            reply.value = null
        }
    }

    private fun waitingIsEmpty(): Boolean = synchronized(pending) { pending.isEmpty() }
}

/** Команды службе: скриншот без острова, блокировка, выключение и прокрутка по явному жесту. */
enum class ServiceCommand { SCREENSHOT, LOCK, TURN_OFF, SCROLL_TOP }
