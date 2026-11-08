package ru.lazytv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.media3.common.PlaybackException
import org.json.JSONException
import java.io.EOFException
import java.io.FileNotFoundException
import java.net.ConnectException
import java.net.HttpRetryException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.util.concurrent.ExecutionException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLProtocolException
import kotlin.math.abs

/**
 * Checks whether the device clock is off by more than a day.
 * When a TV box loses power and resets its clock, HTTPS handshakes fail everywhere with certificate errors.
 */
object ClockCheck {
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    @Volatile
    var skewed: Boolean = false
        private set

    const val MESSAGE = "На устройстве сбились дата и время: защищённые сайты (HTTPS) могут не открываться. Проверьте часы в настройках."

    /** Compares the HTTP Date header (UTC millis, 0 when absent) with the system clock. */
    fun observe(serverDateMs: Long) {
        if (serverDateMs <= 0L) return
        skewed = abs(System.currentTimeMillis() - serverDateMs) > DAY_MS
    }

    /**
     * Plain HTTP head check on start: works even when the device clock is in 1970 and every HTTPS connection fails
     * during the TLS handshake before any HTTP header arrives.
     */
    fun checkOnce() {
        runCatching {
            val c = URL("http://connectivitycheck.gstatic.com/generate_204").openConnection() as HttpURLConnection
            c.requestMethod = "HEAD"
            c.instanceFollowRedirects = false
            c.connectTimeout = 5000
            c.readTimeout = 5000
            c.connect()
            observe(c.date)
            c.disconnect()
        }
    }
}

/** One recovery action offered next to an error message. */
data class ErrorAction(val caption: String, val run: (Activity) -> Unit)

/**
 * Turns exceptions and player error codes into short Russian explanations with a suggested action,
 * so the user never sees raw class names, stack traces or HTTP codes.
 */
object Errors {
    const val GEO_BLOCKED = "Недоступно в вашем регионе. Попробуйте другой источник или торренты"

    private fun unwrap(t: Throwable): Throwable {
        var cur = t
        while ((cur is ExecutionException || cur.javaClass.name.endsWith("InvocationTargetException")) && cur.cause != null) {
            cur = cur.cause!!
        }
        return cur
    }

    private fun chain(t: Throwable): Sequence<Throwable> = generateSequence(unwrap(t)) { it.cause }

    fun isSsl(t: Throwable): Boolean = ClockCheck.skewed || chain(t).any { e ->
        e is SSLHandshakeException || e is SSLProtocolException || e is SSLException ||
            e is CertificateException || e is CertPathValidatorException ||
            e.message.orEmpty().let { m ->
                m.contains("CertPathValidator", true) || m.contains("Trust anchor", true) ||
                    m.contains("SSL", true) || m.contains("certificate", true)
            }
    }

    fun isGeoOrForbidden(t: Throwable): Boolean = chain(t).any { e ->
        val m = e.message.orEmpty()
        (e is HttpRetryException && (e.responseCode() == 403 || e.responseCode() == 451)) ||
            m.contains("response code: 403", true) || m.contains("response code: 451", true) ||
            m.contains("регион", true) || m.contains("правообладател", true) ||
            m.contains("заблокирован для вашей страны", true)
    }

    fun isNetwork(t: Throwable): Boolean = chain(t).any { e ->
        e is UnknownHostException || e is ConnectException || e is SocketTimeoutException ||
            e is NoRouteToHostException || e is SocketException
    }

    fun isServerOrFormat(t: Throwable): Boolean = chain(t).any { e ->
        val m = e.message.orEmpty()
        e is JSONException || e is EOFException || e is FileNotFoundException ||
            Regex("response code: 5\\d\\d").containsMatchIn(m)
    }

    /** Short technical tail for a catalog that did not load: the exception and the state of the bypass, to read off a photo of the screen. */
    fun details(t: Throwable, host: String): String {
        val root = unwrap(t)
        val reason = when (root) {
            is SocketTimeoutException -> "таймаут"
            is UnknownHostException -> "адрес не найден (DNS)"
            is ConnectException -> "отказ соединения"
            else -> root.javaClass.simpleName + root.message?.let { ": " + it.take(60) }.orEmpty()
        }
        return "$reason · " + com.lmd.dpi.DpiBypass.status(host)
    }

    /** Human-readable explanation of [t], one or two calm sentences without technical codes. */
    fun explain(t: Throwable): String {
        val root = unwrap(t)
        val msg = root.message.orEmpty().trim()
        if (isGeoOrForbidden(root)) return GEO_BLOCKED
        if (isSsl(root)) {
            return if ( android.os.Build.VERSION.SDK_INT <= 23) {
                "Ошибка защищённого соединения. Проверьте дату и время на устройстве (на Android 6 и ниже также могли устареть сертификаты)"
            } else {
                "Не сходятся дата и время на устройстве. Проверьте время и перезагрузите приставку"
            }
        }
        if (isNetwork(root)) {
            return "Сайт не отвечает. Включите обход блокировок в настройках или выберите другой источник"
        }
        if (isServerOrFormat(root)) {
            return "Сайт временно не работает. Попробуйте позже или другой источник"
        }
        // Keep intentional Russian messages thrown by the app itself (Backup, TorrServe, RemoteConfig)
        if ((root is IllegalStateException || root is IllegalArgumentException) &&
            msg.any { it in 'А'..'я' || it == 'ё' || it == 'Ё' }
        ) {
            return msg
        }
        return "Сайт временно не работает. Попробуйте позже или другой источник"
    }

    /** Optional recovery button for the error (date settings for SSL, network settings for timeouts/blocks). */
    fun actionOf(t: Throwable): ErrorAction? = when {
        isSsl(t) -> ErrorAction("Открыть настройки даты") { openDateSettings(it) }
        isNetwork(t) -> ErrorAction("Настройки сети") { openNetworkSettings(it) }
        else -> null
    }

    fun openDateSettings(context: Context) {
        runCatching {
            context.startActivity(Intent(android.provider.Settings.ACTION_DATE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            runCatching {
                context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure {
                Toast.makeText(context, "Откройте настройки даты и времени в меню приставки", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun openNetworkSettings(context: Context) {
        context.startActivity(Intent(context, SettingsActivity::class.java).putExtra("section", "Сеть"))
    }

    /** Human-readable explanation for an ExoPlayer error. */
    fun explainPlayer(error: PlaybackException): String {
        val cause = error.cause
        if (cause != null && isSsl(cause)) {
            return "Поток не открылся: не сходятся дата и время на устройстве. Проверьте часы в настройках"
        }
        if (cause != null && isGeoOrForbidden(cause)) {
            return "Поток не открылся: доступ закрыт для вашего региона или по требованию правообладателя"
        }
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                "Поток не открылся: сервер отклонил запрос (доступ ограничен или ссылка устарела)"
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                "Поток не найден на сервере. Попробуйте другой источник или торренты"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                "Поток не открылся: нет связи с сервером видео. Проверьте интернет или обход блокировок"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_TIMEOUT ->
                "Поток не открылся: сервер видео слишком долго не отвечает"
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
                "Видео или звук не поддерживаются встроенным плеером. Попробуйте открыть во внешнем плеере"
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->
                "Формат потока не распознан встроенным плеером. Попробуйте внешний плеер или другой источник"
            else ->
                "Поток не открылся. Попробуйте другое качество, внешний плеер или торренты"
        }
    }
}
