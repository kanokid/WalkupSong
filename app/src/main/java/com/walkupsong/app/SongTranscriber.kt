package com.walkupsong.app

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.StringRes
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors

object SongTranscriber {
    const val MODEL = "whisper-large-v3"
    const val ENDPOINT = "https://api.groq.com/openai/v1/audio/transcriptions"

    sealed class Outcome {
        data class Success(val words: List<TimedWord>) : Outcome()
        data class Failure(@StringRes val messageRes: Int, val authFailure: Boolean = false) : Outcome()
    }

    data class Request(
        val url: String,
        val apiKey: String,
        val model: String,
        val responseFormat: String,
        val timestampGranularity: String,
        val fileName: String,
        val mimeType: String,
        val fileBytes: ByteArray,
        val contentType: String,
        val multipartBody: ByteArray
    )

    data class Response(val code: Int, val body: String)

    private data class AudioPayload(val fileName: String, val mimeType: String, val bytes: ByteArray)
    private sealed class PayloadResult {
        data class Ready(val payload: AudioPayload) : PayloadResult()
        data class Error(@StringRes val messageRes: Int) : PayloadResult()
    }

    var activePlayerId: String? = null
        private set
    val isBusy: Boolean get() = activePlayerId != null

    private var onStateChanged: (() -> Unit)? = null
    private var onResult: ((String, Outcome) -> Unit)? = null
    private var pendingResult: Pair<String, Outcome>? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val backgroundExecutor = Executors.newSingleThreadExecutor()

    var executor: Executor = backgroundExecutor
    var transport: (Request) -> Response = ::defaultTransport

    fun attach(stateListener: () -> Unit, resultListener: (String, Outcome) -> Unit) {
        onStateChanged = stateListener
        onResult = resultListener
        pendingResult?.let { (playerId, outcome) ->
            pendingResult = null
            resultListener(playerId, outcome)
        }
    }

    fun detach() {
        onStateChanged = null
        onResult = null
    }

    fun resetForTesting() {
        activePlayerId = null
        pendingResult = null
        onStateChanged = null
        onResult = null
        executor = backgroundExecutor
        transport = ::defaultTransport
    }

    fun start(context: Context, player: Player, apiKey: String) {
        val playerId = player.id ?: return
        if (isBusy) return
        val snapshot = player.copy()
        val appContext = context.applicationContext
        val trimmedKey = apiKey.trim()
        activePlayerId = playerId
        pendingResult = null
        onStateChanged?.invoke()
        executor.execute {
            val outcome = transcribeBlocking(appContext, snapshot, trimmedKey)
            mainHandler.post {
                activePlayerId = null
                onStateChanged?.invoke()
                val callback = onResult
                if (callback != null) {
                    callback(playerId, outcome)
                } else {
                    pendingResult = playerId to outcome
                }
            }
        }
    }

    internal fun transcribeBlocking(context: Context, player: Player, apiKey: String): Outcome {
        if (apiKey.isBlank()) return Outcome.Failure(R.string.invalid_groq_key, authFailure = true)
        val payload = when (val prepared = preparePayload(context, player)) {
            is PayloadResult.Ready -> prepared.payload
            is PayloadResult.Error -> return Outcome.Failure(prepared.messageRes)
        }
        val boundary = "----WalkupSongBoundary${UUID.randomUUID().toString().replace("-", "")}"
        val body = buildMultipartBody(boundary, payload)
        val request = Request(
            url = ENDPOINT,
            apiKey = apiKey,
            model = MODEL,
            responseFormat = "verbose_json",
            timestampGranularity = "word",
            fileName = payload.fileName,
            mimeType = payload.mimeType,
            fileBytes = payload.bytes,
            contentType = "multipart/form-data; boundary=$boundary",
            multipartBody = body
        )
        return try {
            val response = transport(request)
            when (response.code) {
                in 200..299 -> {
                    val words = parseWords(JSONObject(response.body))
                    if (words.isEmpty()) Outcome.Failure(R.string.speech_empty) else Outcome.Success(words)
                }
                401, 403 -> Outcome.Failure(R.string.groq_auth_error, authFailure = true)
                else -> Outcome.Failure(R.string.song_transcribe_error)
            }
        } catch (_: Exception) {
            Outcome.Failure(R.string.song_transcribe_error)
        }
    }

    internal fun parseWords(json: JSONObject): List<TimedWord> {
        val raw = mutableListOf<TimedWord>()
        val wordsArray = json.optJSONArray("words")
        if (wordsArray != null) {
            for (i in 0 until wordsArray.length()) {
                val item = wordsArray.optJSONObject(i) ?: continue
                val text = item.optString("word").trim()
                val start = item.optDouble("start", Double.NaN)
                val end = item.optDouble("end", Double.NaN)
                if (text.isNotEmpty() && !start.isNaN() && !end.isNaN() && end >= start) {
                    raw.add(TimedWord(text, start, maxOf(end, start + 0.1)))
                }
            }
        }
        return groupByTimeframe(raw)
    }

    internal fun groupByTimeframe(words: List<TimedWord>): List<TimedWord> {
        if (words.isEmpty()) return emptyList()
        val grouped = mutableListOf<TimedWord>()
        for (item in words) {
            val last = grouped.lastOrNull()
            if (last != null && kotlin.math.floor(last.start).toInt() == kotlin.math.floor(item.start).toInt()) {
                grouped[grouped.lastIndex] = TimedWord(
                    word = "${last.word} ${item.word}",
                    start = last.start,
                    end = maxOf(last.end, item.end)
                )
            } else {
                grouped.add(item)
            }
        }
        return grouped
    }

    private fun preparePayload(context: Context, player: Player): PayloadResult {
        val path = player.songPath ?: return PayloadResult.Error(R.string.no_clip)
        val uri = Uri.parse(path)
        val raw = try {
            val stream = context.contentResolver.openInputStream(uri)
                ?: return PayloadResult.Error(R.string.file_error)
            stream.use { it.readBytes() }
        } catch (_: Exception) {
            return PayloadResult.Error(R.string.file_error)
        }
        if (raw.isEmpty()) return PayloadResult.Error(R.string.file_error)
        val (ext, mime) = resolveFormat(player.songTitle, uri, try {
            context.contentResolver.getType(uri)
        } catch (_: Exception) {
            null
        })
        return PayloadResult.Ready(AudioPayload(
            fileName = normalizeFileName(player.songTitle, ext),
            mimeType = mime,
            bytes = raw
        ))
    }

    private fun resolveFormat(title: String?, uri: Uri, mimeType: String?): Pair<String, String> {
        val supported = mapOf(
            "flac" to "audio/flac",
            "mp3" to "audio/mpeg",
            "mp4" to "audio/mp4",
            "mpeg" to "audio/mpeg",
            "mpga" to "audio/mpeg",
            "m4a" to "audio/mp4",
            "ogg" to "audio/ogg",
            "wav" to "audio/wav",
            "webm" to "audio/webm"
        )
        val extFromName = listOfNotNull(title, uri.lastPathSegment)
            .map { it.substringAfterLast('.', "").lowercase(Locale.ROOT) }
            .firstOrNull { it in supported }
        if (extFromName != null) return extFromName to supported.getValue(extFromName)
        return when (mimeType?.lowercase(Locale.ROOT)) {
            "audio/wav", "audio/x-wav", "audio/wave" -> "wav" to "audio/wav"
            "audio/flac", "audio/x-flac" -> "flac" to "audio/flac"
            "audio/ogg", "application/ogg" -> "ogg" to "audio/ogg"
            "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a" to "audio/mp4"
            "audio/webm" -> "webm" to "audio/webm"
            else -> "mp3" to "audio/mpeg"
        }
    }

    private fun normalizeFileName(title: String?, ext: String): String {
        val cleaned = (title ?: "clip").trim().replace(Regex("[^A-Za-z0-9._-]"), "_")
        val base = cleaned.substringBeforeLast('.', cleaned).trim('_').ifBlank { "clip" }
        return "$base.$ext"
    }

    private fun buildMultipartBody(boundary: String, payload: AudioPayload): ByteArray {
        val out = ByteArrayOutputStream(payload.bytes.size + 512)
        fun writeAscii(text: String) = out.write(text.toByteArray(Charsets.UTF_8))
        writeAscii("--$boundary\r\n")
        writeAscii("Content-Disposition: form-data; name=\"model\"\r\n\r\n")
        writeAscii("$MODEL\r\n")
        writeAscii("--$boundary\r\n")
        writeAscii("Content-Disposition: form-data; name=\"response_format\"\r\n\r\n")
        writeAscii("verbose_json\r\n")
        writeAscii("--$boundary\r\n")
        writeAscii("Content-Disposition: form-data; name=\"timestamp_granularities[]\"\r\n\r\n")
        writeAscii("word\r\n")
        writeAscii("--$boundary\r\n")
        writeAscii("Content-Disposition: form-data; name=\"file\"; filename=\"${payload.fileName}\"\r\n")
        writeAscii("Content-Type: ${payload.mimeType}\r\n\r\n")
        out.write(payload.bytes)
        writeAscii("\r\n--$boundary--\r\n")
        return out.toByteArray()
    }

    private fun defaultTransport(request: Request): Response {
        val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${request.apiKey}")
            setRequestProperty("Content-Type", request.contentType)
            setRequestProperty("Accept", "application/json")
            setFixedLengthStreamingMode(request.multipartBody.size)
        }
        try {
            connection.outputStream.use { it.write(request.multipartBody) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return Response(code, body)
        } finally {
            connection.disconnect()
        }
    }
}
