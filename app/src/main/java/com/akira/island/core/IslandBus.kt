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
    /** Идёт ввод ответа на уведомление: окну острова нужен фокус, чтобы показать клавиатуру */
    val reply = MutableStateFlow<ReplyState?>(null)
    /** Наклон телефона от -1 до 1: по нему сдвигаются блики Liquid Glass */
    val glassTilt = MutableStateFlow(0f)
    /** До какого момента (elapsedRealtime) показывать пример активности — пока двигают ползунок ширины */
    val widthPreviewUntil = MutableStateFlow(0L)
    /** Человек читает или отвечает — уведомление не убирается по таймеру */
    val noticeHold = MutableStateFlow(false)
    /** У какого уведомления (id) показаны кнопки: как на iPhone, они появляются, если потянуть баннер вниз или удержать */
    val noticeActions = MutableStateFlow<Long?>(null)
    val battery = MutableStateFlow(BatteryInfo())
    val foregroundPackage = MutableStateFlow<String?>(null)
    val landscape = MutableStateFlow(false)
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
    private val pending = ArrayDeque<Notice>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    /** Показанную карточку обновили новым сообщением той же переписки — время показа считается заново */
    private val refreshed = MutableStateFlow(0L)
    /** Пришло сообщение из другой переписки — показанное сообщение уступает ему место */
    private val hurry = MutableStateFlow(0L)
    private const val MAX_PENDING = 6
    /** Сколько сообщение видно хотя бы, даже если за ним уже ждёт следующее */
    private const val MIN_SHOWN_MS = 1_500L

    init {
        scope.launch {
            while (true) {
                val n = synchronized(pending) { pending.removeFirstOrNull() }
                if (n == null) {
                    wake.receive()
                    continue
                }
                show(n)
                // Короткая пауза между плашками, чтобы они не слипались, — но без заметного ожидания
                delay(110)
            }
        }
    }

    private suspend fun show(n: Notice) {
        notice.value = n
        var shownAt = SystemClock.elapsedRealtime()
        while (true) {
            val stamp = refreshed.value
            val hurried = hurry.value
            val duration = SettingsRepo.current.noticeDurationMs + (notice.value?.extraMs ?: n.extraMs)
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
            }
            break
        }
        // Пока отвечают или листают переписку, карточка остаётся; потом ещё чуть-чуть
        if (notice.value?.id == n.id && noticeHold.value) {
            while (notice.value?.id == n.id && noticeHold.value) delay(200)
            if (notice.value?.id == n.id) withTimeoutOrNull(1500) { notice.first { it?.id != n.id } }
        }
        if (notice.value?.id == n.id) notice.value = null
    }

    fun post(n: Notice) {
        val key = n.message?.key
        if (key != null) {
            val current = notice.value
            if (current?.message?.key == key) {
                // Новое сообщение той же переписки: карточка обновляется на месте, ввод ответа не сбрасывается
                notice.value = n.copy(id = current.id)
                refreshed.value++
                return
            }
        }
        synchronized(pending) {
            val queued = if (key == null) -1 else pending.indexOfFirst { it.message?.key == key }
            if (queued >= 0) {
                pending[queued] = n
                return
            }
            if (pending.size >= MAX_PENDING) pending.removeFirst()
            pending.addLast(n)
        }
        if (key != null && notice.value?.style == NoticeStyle.MESSAGE) hurry.value++
        wake.trySend(Unit)
    }
}

/** Команды службе: скриншот без острова, блокировка экрана, выключение острова (для банков). */
enum class ServiceCommand { SCREENSHOT, LOCK, TURN_OFF }
