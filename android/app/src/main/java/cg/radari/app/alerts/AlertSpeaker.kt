package cg.radari.app.alerts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Glasovni i zvucni dojav.
 * - Audio fokus (USAGE_ASSISTANCE_NAVIGATION_GUIDANCE) se trazi kad krene prvi dojav
 *   i pusta tek kad se red isprazni — tako se ne prekida sam sebi i ne curi fokus
 *   kad beep i govor idu jedan za drugim.
 * - Beep je pravi ton (AudioTrack, 880/1320/660 Hz kao u PWA), ne izgovoren tekst.
 * - Radi iz foreground servisa i uz ugasen ekran, kroz Bluetooth auta.
 */
class AlertSpeaker(context: Context) {

    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val seq = AtomicInteger(0)
    private val tones = Executors.newSingleThreadExecutor()

    @Volatile private var ttsReady = false
    @Volatile private var released = false

    /** Broj dojava koji jos svira ili ceka u redu; fokus se drzi dok je > 0. */
    private val pending = AtomicInteger(0)
    private val focusLock = Any()
    private var focus: AudioFocusRequest? = null

    var enabled = true

    private val navAttrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val tts: TextToSpeech = TextToSpeech(appContext) { status ->
        ttsReady = status == TextToSpeech.SUCCESS
        if (!ttsReady) return@TextToSpeech
        tts.setAudioAttributes(navAttrs)
        tts.language = Locale("sr", "ME")
        if (tts.voice == null || tts.language?.language != "sr") tts.language = Locale("hr", "HR")
        if (tts.voice == null) tts.language = Locale("bs", "BA")
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finishOne()
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finishOne()

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finishOne()
            override fun onError(utteranceId: String?, errorCode: Int) = finishOne()
        })
    }

    // ---- audio fokus ----

    /** Registruje jedan dojav u red; vraca false ako fokus nije dobijen. */
    private fun beginOne(): Boolean {
        synchronized(focusLock) {
            if (focus == null) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(navAttrs)
                    .setOnAudioFocusChangeListener { }
                    .build()
                if (audio.requestAudioFocus(req) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
                focus = req
            }
        }
        pending.incrementAndGet()
        return true
    }

    private fun finishOne() {
        if (pending.decrementAndGet() > 0) return
        synchronized(focusLock) {
            focus?.let { audio.abandonAudioFocusRequest(it) }
            focus = null
        }
    }

    // ---- govor ----

    /**
     * Izgovori poruku. Prije inicijalizacije TTS-a poziv se tiho preskace.
     * `important=true` prekida tekuci govor (npr. "usporite"), inace ide u red.
     */
    fun speak(text: String, important: Boolean = false) {
        if (!enabled || !ttsReady || released) return
        if (!beginOne()) return
        val id = "r${seq.incrementAndGet()}"
        val mode = if (important) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        // Kod QUEUE_FLUSH prekinute poruke dobijaju onStop, pa se brojac sam poravna.
        if (tts.speak(text, mode, null, id) != TextToSpeech.SUCCESS) finishOne()
    }

    /** Kratki zvucni signal: `count` tonova zadate frekvencije (kao beep() u PWA). */
    fun beep(count: Int, freqHz: Int) {
        if (!enabled || released) return
        if (!beginOne()) return
        tones.execute {
            try {
                playTone(count, freqHz.toDouble())
            } catch (_: Exception) {
                // Zvuk nije kriticno; upozorenje i dalje ide govorom.
            } finally {
                finishOne()
            }
        }
    }

    private fun playTone(count: Int, freqHz: Double) {
        val sr = 22050
        val beepN = sr * BEEP_MS / 1000
        val gapN = sr * GAP_MS / 1000
        val total = count * beepN + (count - 1) * gapN
        val buf = ShortArray(total)
        val fadeN = (sr * 0.006).toInt().coerceAtLeast(1)
        var i = 0
        for (k in 0 until count) {
            for (n in 0 until beepN) {
                val env = min(1.0, min(n, beepN - n).toDouble() / fadeN)
                buf[i++] = (sin(2 * PI * freqHz * n / sr) * env * 0.55 * Short.MAX_VALUE).toInt().toShort()
            }
            if (k < count - 1) repeat(gapN) { buf[i++] = 0 }
        }

        val track = AudioTrack.Builder()
            .setAudioAttributes(navAttrs)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sr)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(total * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        try {
            track.write(buf, 0, total)
            track.play()
            // MODE_STATIC ne blokira, pa cekamo trajanje sekvence prije oslobadjanja.
            Thread.sleep((count * BEEP_MS + (count - 1) * GAP_MS + 80).toLong())
            track.stop()
        } finally {
            track.release()
        }
    }

    fun shutdown() {
        released = true
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
        tones.shutdownNow()
        pending.set(0)
        synchronized(focusLock) {
            focus?.let { audio.abandonAudioFocusRequest(it) }
            focus = null
        }
    }

    companion object {
        private const val BEEP_MS = 90
        private const val GAP_MS = 70

        /** Frekvencije 1:1 iz PWA. */
        const val TONE_APPROACH = 880
        const val TONE_CLOSE = 1320
        const val TONE_LOW = 660
    }
}
