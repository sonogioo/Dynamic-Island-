package com.akira.island.ui.screens

import android.Manifest
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.AccessAlarm
import androidx.compose.material.icons.rounded.AirplanemodeActive
import androidx.compose.material.icons.rounded.AlarmOn
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material.icons.rounded.PowerOff
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.akira.island.R
import com.akira.island.core.AlarmInfo
import com.akira.island.core.AppMessage
import com.akira.island.core.Controls
import com.akira.island.core.IslandBus
import com.akira.island.core.MediaInfo
import com.akira.island.core.MsgAction
import com.akira.island.core.Notice
import com.akira.island.core.NoticeStyle
import com.akira.island.core.PayReady
import com.akira.island.core.Tints
import com.akira.island.core.tr
import com.akira.island.data.SettingsRepo
import com.akira.island.island.Act
import com.akira.island.island.CompactContent
import com.akira.island.island.Ios
import com.akira.island.island.activeHeight
import com.akira.island.island.glassSurface
import com.akira.island.island.islandGlow
import com.akira.island.island.islandShape
import com.akira.island.island.rememberHaptic
import com.akira.island.service.NotificationParser
import com.akira.island.service.SystemBanners
import com.akira.island.ui.Group
import com.akira.island.ui.GroupItem
import com.akira.island.ui.ItemIcon
import com.akira.island.ui.Page
import com.akira.island.ui.Perms
import com.akira.island.ui.PillAction
import com.akira.island.ui.Pos
import com.akira.island.ui.Screen
import com.akira.island.ui.SectionHeader
import com.akira.island.ui.Tile
import com.akira.island.ui.ToggleCard
import com.akira.island.ui.Ui
import com.akira.island.ui.UpdateSection
import com.akira.island.update.UpdateChecker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Какие разрешения есть. Проверяются заново каждый раз, когда возвращаемся из системных настроек. */
private class PermState(val accessibility: Boolean, val notifications: Boolean, val calls: Boolean, val bluetooth: Boolean) {
    /** Чего не хватает — для подписи на главной */
    val missing: List<String>
        get() = listOfNotNull(
            tr("спец. возможности", "accessibility").takeIf { !accessibility },
            tr("уведомления", "notifications").takeIf { !notifications },
            tr("звонки", "calls").takeIf { !calls },
            "Bluetooth".takeIf { !bluetooth },
        )
}

/**
 * Значение, которое читается заново каждый раз, когда возвращаемся на экран (например, из настроек системы),
 * и функция «прочитать ещё раз».
 */
@Composable
internal fun <T> rememberOnResume(read: () -> T): Pair<T, () -> Unit> {
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }
    return remember(tick) { read() } to { tick++ }
}

/** Разрешения и функция «проверить ещё раз» — после ответа на системный запрос. */
@Composable
private fun rememberPermState(): Pair<PermState, () -> Unit> {
    val context = LocalContext.current
    return rememberOnResume {
        PermState(Perms.accessibility(context), Perms.notifications(context), Perms.calls(context), Perms.bluetooth(context))
    }
}

/**
 * «Главная»: живой пример, включён ли остров, разрешения и проверка. Всё, что настраивается, —
 * во вкладках «Функции» и «Оформление», версия и помощь — в «О приложении».
 */
@Composable
fun HomeScreen(open: (Screen) -> Unit) {
    val context = LocalContext.current
    val s by SettingsRepo.state.collectAsState()
    val running by IslandBus.serviceRunning.collectAsState()
    val (perms, _) = rememberPermState()
    val missing = perms.missing

    Page(tr("Динамический остров", "Dynamic Island")) {
        IslandPreview()
        UpdateSection()

        // Без службы спец. возможностей острова нет вовсе — это первый шаг
        if (!perms.accessibility) {
            StartCard(onOpen = { Perms.openAccessibility(context) }, onHelp = { open(Screen.INSTRUCTION) })
        }

        // Сообщение пришло и в остров, и своей плашкой мессенджера — подсказываем один раз, как убрать вторую
        val popUp by SystemBanners.popUp.collectAsState()
        val doubled = popUp.filterValues { it }.keys.firstOrNull()
        if (doubled != null && !s.bannerHintHidden) {
            DoubleBannerCard(
                NotificationParser.appLabel(context, doubled),
                onFix = { open(Screen.BANNERS) },
                onHide = { SettingsRepo.update { copy(bannerHintHidden = true) } },
            )
        }

        ToggleCard(
            Icons.Rounded.PowerSettingsNew,
            tr("Остров включён", "Island on"),
            when {
                !perms.accessibility -> tr("Сначала включите службу в спец. возможностях", "First turn on the service in Accessibility")
                running -> tr("Капсула показывается над камерой", "The capsule shows over the camera")
                else -> tr("Служба запускается…", "The service is starting…")
            },
            s.enabled, color = Tile.Green,
        ) { v -> SettingsRepo.update { copy(enabled = v) } }

        Group {
            GroupItem(
                Pos.First, Icons.Rounded.VerifiedUser, tr("Разрешения", "Permissions"),
                if (missing.isEmpty()) tr("Всё включено", "All granted") else tr("Нужно включить: ", "Needed: ") + missing.joinToString(", "),
                color = if (missing.isEmpty()) Tile.Green else Tile.Orange,
                onClick = { open(Screen.PERMISSIONS) },
            ) { if (missing.isEmpty()) Arrow() else CountBadge(missing.size) }
            // На виду: на Xiaomi, Oppo, Samsung остров иначе пропадает, и люди не знают почему
            KeepAliveItem(Pos.Middle, open)
            // И про банки, которые не пускают, пока остров включён
            BanksItem(Pos.Middle, open)
            GroupItem(
                Pos.Last, Icons.Rounded.Science, tr("Проверка", "Try it"),
                tr("Событие, сообщение, звонок, оплата — прямо в острове", "An event, message, call, payment — right in the island"),
                color = Tile.Purple, onClick = { open(Screen.TESTS) },
            ) { Arrow() }
        }
    }
}

/** Первый шаг на главной: пока служба спец. возможностей выключена. */
@Composable
private fun StartCard(onOpen: () -> Unit, onHelp: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Ui.Primary.copy(alpha = 0.14f))
            .border(1.dp, Ui.Primary.copy(alpha = 0.35f), RoundedCornerShape(28.dp))
            .padding(20.dp),
    ) {
        Text(tr("Включите остров", "Turn on the island"), color = Ui.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            tr(
                "Капсулу рядом с камерой может рисовать только служба спец. возможностей. " +
                    "Откройте «Спец. возможности» → «Динамический остров» и включите её.",
                "Only an accessibility service can draw the capsule next to the camera. Open Accessibility → Dynamic " +
                    "Island and turn it on.",
            ),
            color = Ui.Sub, fontSize = 14.sp, lineHeight = 20.sp,
        )
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier.fillMaxWidth().clip(CircleShape).background(Ui.Primary).clickable(onClick = onOpen).padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) { Text(tr("Открыть спец. возможности", "Open Accessibility"), color = Ui.OnPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
        // Android 13+ не даёт включить службу приложению не из Play Маркета, пока не разрешишь «ограниченные настройки»
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Spacer(Modifier.height(16.dp))
            Text(restrictedHelp(), color = Ui.Sub, fontSize = 14.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier.fillMaxWidth().clip(CircleShape).border(1.dp, Ui.Primary, CircleShape)
                    .clickable { Perms.openAppDetails(context) }.padding(vertical = 13.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(tr("Открыть «О приложении»", "Open App info"), color = Ui.Primary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Box(
            Modifier.fillMaxWidth().padding(top = 4.dp).clip(CircleShape).clickable(onClick = onHelp).padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(tr("Переключатель серый? Инструкция", "Switch grayed out? Instructions"), color = Ui.Primary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** Android 13+ не даёт включить службу приложению не из Play Маркета, пока не разрешишь «ограниченные настройки». */
internal fun restrictedHelp(): String = tr(
    "Android пишет «Ограниченная настройка» или «Доступ запрещён»? Так он защищает приложения не из Play Маркета:\n" +
        "1. Попробуйте включить службу один раз — появится это окно, закройте его.\n" +
        "2. Откройте «О приложении» → «⋮» справа вверху → «Разрешить ограниченные настройки».\n" +
        "3. Вернитесь в «Спец. возможности» и включите остров.",
    "Android says “Restricted setting” or “App was denied access”? That's how it protects apps not from Google Play:\n" +
        "1. Try to turn the service on once — that window shows up, close it.\n" +
        "2. Open App info → “⋮” in the top right → “Allow restricted settings”.\n" +
        "3. Go back to Accessibility and turn the island on.",
)

/** «Сообщения приходят дважды?» — когда у мессенджера ещё всплывает своя плашка поверх острова. */
@Composable
private fun DoubleBannerCard(app: String, onFix: () -> Unit, onHide: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Ui.Card).padding(20.dp),
    ) {
        Text(tr("Сообщения приходят дважды?", "Messages show up twice?"), color = Ui.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            tr(
                "Сообщение показывает и остров, и сам $app своей плашкой. Выключите у него «Всплывающие уведомления» — " +
                    "останется только остров, а в шторке уведомление сохранится.",
                "Both the island and $app itself show the message. Turn off its “Pop on screen” — only the island will " +
                    "remain, and the notification stays in the shade.",
            ),
            color = Ui.Sub, fontSize = 14.sp, lineHeight = 20.sp,
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillAction(tr("Настроить", "Set up"), onClick = onFix)
            PillAction(tr("Скрыть", "Hide"), onClick = onHide)
        }
    }
}

/** «Разрешения»: что острову нужно от системы и зачем. */
@Composable
fun PermissionsScreen(onBack: () -> Unit, open: (Screen) -> Unit) {
    val context = LocalContext.current
    val (perms, refresh) = rememberPermState()
    val btLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    val callLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        refresh()
        // Если система больше не показывает запрос — открываем настройки приложения
        if (result.values.none { it } && !Perms.calls(context)) Perms.openAppDetails(context)
    }

    Page(tr("Разрешения", "Permissions"), onBack,
        subtitle = tr("Остров не хранит и не отправляет ваши уведомления, звонки и экраны: разрешения нужны, только чтобы показывать события",
            "The island doesn't store or send your notifications, calls or screens: permissions are only used to show events")) {
        Group {
            GroupItem(
                Pos.First, Icons.Rounded.AccessibilityNew, tr("Спец. возможности", "Accessibility"),
                if (perms.accessibility) tr("Включено — остров рисуется поверх строки состояния", "On — the island draws over the status bar")
                else tr("Нужно включить: без этого острова не будет", "Needed: without it there's no island"),
                onClick = { Perms.openAccessibility(context) }, color = Tile.Blue,
            ) { Status(perms.accessibility) }
            GroupItem(
                Pos.Middle, Icons.Rounded.Notifications, tr("Доступ к уведомлениям", "Notification access"),
                if (perms.notifications) tr("Включено — музыка, сообщения, прогресс и таймеры", "On — music, messages, progress and timers")
                else tr("Нужен для музыки, сообщений, прогресса и таймеров", "Needed for music, messages, progress and timers"),
                onClick = { Perms.openNotifications(context) }, color = Tile.Red,
            ) { Status(perms.notifications) }
            GroupItem(
                Pos.Middle, Icons.Rounded.Call, tr("Звонки", "Calls"),
                if (perms.calls) tr("Входящий вызов, имя из контактов, ответ кнопкой", "Incoming calls, names from contacts, answer button")
                else tr("Чтобы остров показывал входящие звонки", "So the island shows incoming calls"),
                onClick = { if (perms.calls) Perms.openAppDetails(context) else callLauncher.launch(Perms.CALL_PERMISSIONS) },
                color = Tile.Green,
            ) { Status(perms.calls) }
            GroupItem(
                Pos.Last, Icons.Rounded.Bluetooth, "Bluetooth",
                if (perms.bluetooth) tr("Имена наушников при подключении", "Headphone names when they connect")
                else tr("Чтобы видеть подключение наушников", "To see headphones connect"),
                onClick = {
                    if (!perms.bluetooth && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) btLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    else Perms.openAppDetails(context)
                },
                color = Tile.Indigo,
            ) { Status(perms.bluetooth) }
        }

        SectionHeader(tr("Если не включается", "If it doesn't turn on"))
        Group {
            GroupItem(Pos.First, Icons.Rounded.Lightbulb, tr("Инструкция", "Instructions"),
                tr("Серый переключатель на Android 13+, батарея и автозапуск", "Grayed-out switch on Android 13+, battery and autostart"),
                onClick = { open(Screen.INSTRUCTION) }, color = Tile.Yellow) { Arrow() }
            KeepAliveItem(Pos.Middle, open)
            AppDetailsItem(Pos.Last)
        }
    }
}

/** Пример для «Проверки»: значок, название, что увидите, и что делает нажатие. */
private class Demo(val icon: ImageVector, val title: String, val caption: String, val color: Color, val run: () -> Unit)

/** «Проверка»: все примеры в одном месте — нажатие показывает событие в настоящем острове. */
@Composable
fun TestsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val haptic = rememberHaptic()
    val demos = remember { demos(context) }
    Page(tr("Проверка", "Try it"), onBack,
        subtitle = tr("Нажмите — пример появится в острове наверху экрана", "Tap — the example shows up in the island at the top of the screen")) {
        demos.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { d ->
                    DemoTile(d, Modifier.weight(1f).fillMaxHeight()) {
                        haptic()
                        d.run()
                    }
                }
            }
        }
    }
}

@Composable
private fun DemoTile(d: Demo, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(24.dp)).background(Ui.Card).clickable(onClick = onClick).padding(16.dp)) {
        ItemIcon(d.icon, d.color, 40.dp)
        Spacer(Modifier.height(14.dp))
        Text(d.title, color = Ui.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp)
        Spacer(Modifier.height(2.dp))
        Text(d.caption, color = Ui.Sub, fontSize = 13.sp, lineHeight = 17.sp)
    }
}

private fun demos(context: Context): List<Demo> = listOf(
    Demo(Icons.Rounded.Bolt, tr("Событие", "Event"), tr("Зарядка 75% во всю ширину", "Charging 75% at full width"), Tile.Green) {
        IslandBus.post(Notice(tr("Зарядка", "Charging"), "75%", Ios.load(context, R.drawable.ios_flash), Tints.Green, battery = 75))
    },
    Demo(Icons.Rounded.NotificationsOff, tr("Режим звука", "Ring mode"),
        tr("Капсула вздрагивает, колокольчик качается", "The capsule shakes, the bell swings"), Tile.Red) {
        IslandBus.post(Notice("", tr("Без звука", "Silent"), Ios.load(context, R.drawable.ios_notifications_off), Tints.Red, style = NoticeStyle.PILL))
    },
    Demo(Icons.Rounded.ChatBubble, tr("Сообщение", "Message"),
        tr("С кнопками «Ответить» и «Пометить прочитанным»", "With Reply and Mark as read buttons"), Tile.Blue) {
        // Пробные кнопки: «Ответить» с полем ввода, как у Telegram и WhatsApp
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val test = PendingIntent.getBroadcast(context, 0, Intent("com.akira.island.TEST").setPackage(context.packageName), flags)
        val input = RemoteInput.Builder("reply").setLabel(tr("Ответить", "Reply")).build()
        IslandBus.post(
            Notice(
                tr("Анна", "Anna"), icon = Ios.load(context, R.drawable.ios_chatbubble_ellipses), tint = Tints.Blue,
                style = NoticeStyle.MESSAGE,
                extraMs = 4_500,
                message = AppMessage("test", context.packageName, tr("Остров", "Island"),
                    tr("Привет! Так выглядит уведомление в острове", "Hi! This is how a notification looks in the island"),
                    null, NotificationParser.icon(context, context.packageName), null,
                    actions = listOf(
                        MsgAction(tr("Ответить", "Reply"), test, listOf(input)),
                        MsgAction(tr("Пометить прочитанным", "Mark as read"), test),
                    ),
                    history = listOf(tr("Как дела?", "How are you?"), tr("Есть минутка?", "Got a minute?"),
                        tr("Привет! Так выглядит уведомление в острове", "Hi! This is how a notification looks in the island")),
                ),
            ),
        )
    },
    Demo(Icons.Rounded.Call, tr("Входящий звонок", "Incoming call"), tr("Можно ответить и сбросить", "You can answer or decline"), Tile.Green) {
        Controls.showTestCall()
    },
    Demo(Icons.Rounded.HourglassBottom, tr("Таймер", "Timer"), tr("Обратный отсчёт одной минуты", "A one-minute countdown"), Tile.Orange) {
        Controls.startTimer(1)
    },
    Demo(Icons.Rounded.Alarm, tr("Будильник", "Alarm"), tr("Время подъёма и сколько осталось спать", "Wake-up time and how long you can sleep"), Tile.Indigo) {
        IslandBus.post(
            Notice(
                title = tr("Будильник", "Alarm"),
                icon = Ios.load(context, R.drawable.ios_alarm),
                tint = Tints.Orange,
                style = NoticeStyle.ALARM,
                alarm = AlarmInfo(System.currentTimeMillis() + (8 * 60 + 15) * 60_000L, null, null),
                extraMs = 2_000,
            ),
        )
    },
    Demo(Icons.Rounded.Nfc, tr("Оплата", "Payment"), tr("«К терминалу», затем «Оплачено» с суммой", "Hold near reader, then Paid with the amount"), Tile.Teal) {
        // Как настоящая оплата: подсказка держится, пока ждём терминал, потом — «Оплачено»
        val pkg = context.packageName
        IslandBus.payReady.value = PayReady(pkg, NotificationParser.icon(context, pkg))
        IslandBus.scope.launch {
            delay(3000)
            if (IslandBus.payReady.value?.pkg == pkg) IslandBus.payReady.value = null
            IslandBus.post(
                Notice(
                    tr("Оплачено", "Paid"), tr("1 250 ₽", "$12.50"), Ios.load(context, R.drawable.ios_checkmark_circle), Tints.Green,
                    caption = tr("Остров", "Island"), style = NoticeStyle.PAY_DONE, extraMs = 900,
                ),
            )
        }
    },
    Demo(Icons.Rounded.Navigation, tr("Навигатор", "Navigation"), tr("Стрелка поворота и метры до неё", "The turn arrow and meters to it"), Tile.Purple) {
        Controls.showTestLive(context)
    },
    Demo(Icons.Rounded.SystemUpdate, tr("Обновление", "Update"),
        tr("Карточка новой версии: загрузка и «Обновлено»", "The new version card: download, then Updated"), Tile.Pink) {
        UpdateChecker.showDemo(context)
    },

    // Остальные события острова — как настоящие, через несколько секунд пропадают сами
    Demo(Icons.Rounded.Wifi, "Wi-Fi", tr("Подключение к сети", "Connected to a network"), Tile.Blue) {
        IslandBus.post(Notice("Wi-Fi", tr("Подключён", "Connected"), Ios.load(context, R.drawable.ios_wifi), Tints.Blue))
    },
    Demo(Icons.Rounded.Headphones, tr("Наушники", "Headphones"), tr("Подключены, с зарядом кейса", "Connected, with battery level"), Tile.Green) {
        IslandBus.post(
            Notice("AirPods", null, Ios.load(context, R.drawable.ios_headset), Tints.Green, ring = 0.8f,
                caption = tr("Подключено", "Connected")),
        )
    },
    Demo(Icons.Rounded.DarkMode, tr("Не беспокоить", "Do Not Disturb"), tr("Включение режима", "Turning the mode on"), Tile.Purple) {
        IslandBus.post(Notice(tr("Не беспокоить", "Do Not Disturb"), tr("Вкл", "On"), Ios.load(context, R.drawable.ios_moon), Tints.Purple))
    },
    Demo(Icons.Rounded.AirplanemodeActive, tr("Режим полёта", "Airplane Mode"), tr("Включение режима", "Turning the mode on"), Tile.Orange) {
        IslandBus.post(Notice(tr("Режим полёта", "Airplane Mode"), tr("Вкл", "On"), Ios.load(context, R.drawable.ios_airplane), Tints.Orange))
    },
    Demo(Icons.Rounded.BatteryAlert, tr("Низкий заряд", "Low Battery"), tr("Красная батарейка, 15%", "Red battery, 15%"), Tile.Red) {
        IslandBus.post(Notice(tr("Низкий заряд", "Low Battery"), "15%", Ios.load(context, R.drawable.ios_battery_dead), Tints.Red, battery = 15))
    },
    Demo(Icons.Rounded.PowerOff, tr("Отключено", "Unplugged"), tr("Зарядку отключили", "The charger was unplugged"), Tile.Orange) {
        IslandBus.post(Notice(tr("Отключено", "Unplugged"), "80%", Ios.load(context, R.drawable.ios_battery_full), Tints.Orange, battery = 80))
    },
    Demo(Icons.Rounded.BatteryChargingFull, tr("Капсула зарядки", "Charging capsule"), tr("Пока телефон заряжается", "While the phone is charging"), Tile.Green) {
        showFor(IslandBus.battery, com.akira.island.core.BatteryInfo(76, true), 8_000)
    },
    Demo(Icons.Rounded.LightMode, tr("Яркость", "Brightness"), tr("Авто или вручную", "Auto or manual"), Tile.Yellow) {
        IslandBus.post(Notice(tr("Яркость", "Brightness"), tr("Авто", "Auto"), Ios.load(context, R.drawable.ios_sunny), Tints.Yellow))
    },
    Demo(Icons.Rounded.Usb, "USB", tr("Подключили устройство", "A device was connected"), Tile.Teal) {
        IslandBus.post(Notice(tr("USB-устройство", "USB device"), tr("Подключено", "Connected"), Ios.load(context, R.drawable.ios_hardware_chip), Tints.Teal))
    },
    Demo(Icons.Rounded.Vibration, tr("Вибрация", "Vibrate"), tr("Пилюля режима звука", "The ring mode pill"), Tile.Purple) {
        IslandBus.post(Notice("", tr("Вибрация", "Vibrate"), Ios.load(context, R.drawable.ios_phone_portrait), Tints.Purple, style = NoticeStyle.PILL))
    },
    Demo(Icons.Rounded.LockOpen, tr("Разблокировка", "Unlock"), tr("Замок открывается", "The lock opens"), Tile.Gray) {
        IslandBus.post(Notice("", null, Ios.load(context, R.drawable.ios_lock_open), Color.White, style = NoticeStyle.PILL))
    },
    Demo(Icons.Rounded.Done, tr("Отправлено", "Sent"), tr("Ответ из острова ушёл", "A reply sent from the island"), Tile.Blue) {
        IslandBus.post(
            Notice(tr("Отправлено", "Sent"), tr("Анна", "Anna"), Ios.load(context, R.drawable.ios_chatbubble_ellipses), Tints.Blue,
                style = NoticeStyle.SENT),
        )
    },
    Demo(Icons.Rounded.PhoneInTalk, tr("Разговор", "Ongoing call"), tr("Время разговора и «Завершить»", "Call time and End"), Tile.Green) {
        val now = System.currentTimeMillis()
        val call = com.akira.island.core.CallInfo(Controls.TEST_CALL, "", tr("Анна", "Anna"), null, false, now - 65_000, now,
            null, null, null, null)
        IslandBus.calls.value = IslandBus.calls.value + (Controls.TEST_CALL to call)
        IslandBus.scope.launch {
            delay(20_000)
            if (IslandBus.calls.value[Controls.TEST_CALL] == call) IslandBus.calls.value = IslandBus.calls.value - Controls.TEST_CALL
        }
    },
    Demo(Icons.Rounded.MusicNote, tr("Музыка", "Music"), tr("Обложка и волна, раскрывается плеер", "Cover and waveform, opens the player"), Tile.Pink) {
        showFor(
            IslandBus.media,
            MediaInfo(
                key = "demo-media", pkg = "", title = tr("Песня", "Song"), artist = tr("Исполнитель", "Artist"), album = null, art = null,
                accent = Tints.Lavender, durationMs = 200_000, positionMs = 40_000, positionTime = SystemClock.elapsedRealtime(),
                speed = 1f, playing = true, canSeek = false,
            ),
            12_000,
        )
    },
    Demo(Icons.Rounded.Download, tr("Загрузка", "Download"), tr("Полоса прогресса до 100%", "A progress bar up to 100%"), Tile.Indigo) {
        val key = "demo-progress"
        val icon = NotificationParser.icon(context, context.packageName)
        IslandBus.scope.launch {
            for (p in 0..100 step 5) {
                IslandBus.progress.value = IslandBus.progress.value +
                    (key to com.akira.island.core.ProgressInfo(key, context.packageName, tr("Загрузка файла", "Downloading file"), icon, p, 100, false, System.currentTimeMillis()))
                delay(400)
            }
            delay(800)
            IslandBus.progress.value = IslandBus.progress.value - key
        }
    },
    Demo(Icons.Rounded.Timer, tr("Секундомер", "Stopwatch"), tr("Время идёт в капсуле", "Time runs in the capsule"), Tile.Orange) {
        showFor(IslandBus.stopwatch, com.akira.island.core.StopwatchState(SystemClock.elapsedRealtime(), 0), 12_000)
    },
    Demo(Icons.Rounded.FiberManualRecord, tr("Запись экрана", "Screen recording"), tr("Красная точка и время", "Red dot and the time"), Tile.Red) {
        val key = "demo-chrono"
        val now = System.currentTimeMillis()
        IslandBus.chrono.value = IslandBus.chrono.value + (key to com.akira.island.core.ChronoInfo(key, context.packageName,
            tr("Запись экрана", "Screen recording"), NotificationParser.icon(context, context.packageName), now, false, now))
        IslandBus.scope.launch {
            delay(12_000)
            IslandBus.chrono.value = IslandBus.chrono.value - key
        }
    },
    Demo(Icons.Rounded.FlashlightOn, tr("Фонарик", "Flashlight"), tr("Значок фонарика в капсуле", "The flashlight icon in the capsule"), Tile.Yellow) {
        showFor(IslandBus.torch, com.akira.island.core.TorchState(on = true), 8_000)
    },
    Demo(Icons.Rounded.AccessAlarm, tr("Скоро будильник", "Upcoming alarm"), tr("Капсула до подъёма", "The capsule before wake-up"), Tile.Indigo) {
        showFor(IslandBus.nextAlarm, AlarmInfo(System.currentTimeMillis() + (7 * 60 + 30) * 60_000L, null, null), 12_000)
    },
    Demo(Icons.Rounded.AlarmOn, tr("Звонит будильник", "Alarm ringing"), tr("Кнопки «Отложить» и «Выключить»", "Snooze and Stop buttons"), Tile.Red) {
        showFor(IslandBus.ringingAlarm, com.akira.island.core.RingingAlarm("demo-alarm", context.packageName, "07:00", null, null, null), 12_000)
    },
)

/** Подставить пример на [ms] миллисекунд и вернуть, как было, — если за это время его не сменило настоящее событие. */
private fun <T> showFor(flow: kotlinx.coroutines.flow.MutableStateFlow<T>, value: T, ms: Long) {
    val before = flow.value
    flow.value = value
    IslandBus.scope.launch {
        delay(ms)
        if (flow.value === value) flow.value = before
    }
}

@Composable
internal fun Status(ok: Boolean) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (ok) Tints.Green.copy(alpha = 0.18f) else Tints.Orange.copy(alpha = 0.18f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(if (ok) tr("Да", "Yes") else tr("Нет", "No"), color = if (ok) Tints.Green else Tints.Orange, fontSize = 13.sp)
    }
}

/** Сколько разрешений не хватает — оранжевый кружок с числом. */
@Composable
private fun CountBadge(n: Int) {
    Box(Modifier.size(26.dp).clip(CircleShape).background(Tile.Orange), contentAlignment = Alignment.Center) {
        Text("$n", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun Arrow() {
    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Ui.Sub)
}

/** Живой пример капсулы с текущими настройками: толщина, боковые части, цвет и контур. */
@Composable
fun IslandPreview(expanded: Boolean = false) {
    val s by SettingsRepo.state.collectAsState()
    val demo = remember {
        MediaInfo(
            key = "demo", pkg = "", title = tr("Демо", "Demo"), artist = "", album = null, art = null,
            accent = Tints.Lavender, durationMs = 0, positionMs = 0, positionTime = SystemClock.elapsedRealtime(),
            speed = 1f, playing = true, canSeek = false,
        )
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(140.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF3B3860), Color(0xFF1F1E27)))),
        contentAlignment = Alignment.TopCenter,
    ) {
        val act = if (expanded) Act.Idle else Act.MediaA(demo)
        val corner = (if (expanded) s.height.dp else activeHeight(s)) / 2
        val shape = islandShape(corner, s)
        Box(
            Modifier
                // «Как вырез» — капсула от самого верха, как на экране
                .padding(top = if (s.notch) 0.dp else 18.dp)
                // «Свечение обложки» видно и на примере
                .then(if (!expanded && s.fxGlow && !s.reduceEffects) Modifier.drawBehind { islandGlow(demo.accent, 1f, corner.toPx(), s.notch) } else Modifier)
                .clip(shape)
                .then(
                    if (s.liquidGlass) Modifier.glassSurface(shape, Color(s.color), s.glassOpacity / 100f)
                    else Modifier.background(Color(s.color)),
                )
                .then(if (s.outline) Modifier.border(s.outlineWidth.dp, if (s.outlineFromArt) Tints.Lavender else Color(s.outlineColor), shape) else Modifier)
                .animateContentSize(),
        ) {
            CompactContent(act, s, 400.dp)
        }
    }
}
