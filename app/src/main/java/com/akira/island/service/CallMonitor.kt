package com.akira.island.service

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import com.akira.island.core.CallInfo
import com.akira.island.core.IslandBus
import com.akira.island.core.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Звонки по состоянию телефона, а не по уведомлению: многие звонилки (Xiaomi, Samsung…)
 * показывают входящий своим окном без обычного уведомления, и по нему звонок не увидеть.
 */
class CallMonitor(private val context: Context, private val scope: CoroutineScope) {
    private var lastState: String? = null
    private var number: String? = null
    private var since = 0L
    private var lookupJob: Job? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = handle(intent)
    }

    fun start() {
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED,
        )
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
        remove()
    }

    private fun handle(intent: Intent) {
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        // Номер приходит только с разрешением «Журнал звонков», вторым таким же сообщением
        @Suppress("DEPRECATION")
        val num = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        if (!num.isNullOrBlank()) number = num
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> publish(incoming = true)
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                if (lastState != TelephonyManager.EXTRA_STATE_OFFHOOK) since = System.currentTimeMillis()
                publish(incoming = false)
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                number = null
                remove()
            }
        }
        lastState = state
    }

    private fun publish(incoming: Boolean) {
        val num = number
        val base = if (incoming) System.currentTimeMillis() else since
        put(CallInfo(KEY, dialer(), num?.let(::format) ?: tr("Звонок", "Call"), null, incoming, base, System.currentTimeMillis(),
            null, null, null, null))
        // Имя и фото из контактов подставляем, когда найдутся
        lookupJob?.cancel()
        if (num != null && granted(Manifest.permission.READ_CONTACTS)) {
            lookupJob = scope.launch {
                val contact = withContext(Dispatchers.IO) { lookup(num) } ?: return@launch
                val cur = IslandBus.calls.value[KEY] ?: return@launch
                put(cur.copy(name = contact.first, avatar = contact.second ?: cur.avatar))
            }
        }
    }

    private fun put(c: CallInfo) {
        IslandBus.calls.value = IslandBus.calls.value + (KEY to c)
    }

    private fun remove() {
        lookupJob?.cancel()
        if (KEY in IslandBus.calls.value) IslandBus.calls.value = IslandBus.calls.value - KEY
    }

    private fun lookup(num: String): Pair<String, ImageBitmap?>? = contactByNumber(context, num)

    private fun format(num: String): String =
        PhoneNumberUtils.formatNumber(num, Locale.getDefault().country) ?: num

    private fun dialer(): String = runCatching {
        context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
    }.getOrNull() ?: ""

    private fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val KEY = "telephony"

        /** Имя и фото контакта по номеру. */
        fun contactByNumber(context: Context, num: String): Pair<String, ImageBitmap?>? = runCatching {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(num))
            val cols = arrayOf(ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.DISPLAY_NAME)
            context.contentResolver.query(uri, cols, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val name = c.getString(1) ?: return@use null
                name to photoById(context, c.getLong(0))
            }
        }.getOrNull()

        /** Фото контакта с таким именем — когда звонилка дала только имя, без номера. */
        fun photoByName(context: Context, name: String): ImageBitmap? = runCatching {
            if (name.isBlank()) return@runCatching null
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts._ID),
                "${ContactsContract.Contacts.DISPLAY_NAME} = ?", arrayOf(name), null,
            )?.use { c -> if (c.moveToFirst()) photoById(context, c.getLong(0)) else null }
        }.getOrNull()

        /** Фото по ссылке из Person звонилки: «tel:…» или ссылка на контакт. */
        fun photoByUri(context: Context, uri: String?): ImageBitmap? = runCatching {
            if (uri.isNullOrBlank()) return@runCatching null
            val u = Uri.parse(uri)
            when (u.scheme) {
                "tel" -> u.schemeSpecificPart?.let { contactByNumber(context, it)?.second }
                "content" -> {
                    val contact = runCatching { ContactsContract.Contacts.lookupContact(context.contentResolver, u) }.getOrNull() ?: u
                    ContactsContract.Contacts.openContactPhotoInputStream(context.contentResolver, contact, true)
                        ?.use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
                }
                else -> null
            }
        }.getOrNull()

        /** Большое фото контакта, а если его нет — миниатюра. */
        private fun photoById(context: Context, id: Long): ImageBitmap? = runCatching {
            val contact = android.content.ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id)
            ContactsContract.Contacts.openContactPhotoInputStream(context.contentResolver, contact, true)
                ?.use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
        }.getOrNull()

        fun granted(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }
}
