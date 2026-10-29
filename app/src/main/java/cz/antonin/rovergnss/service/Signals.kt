package cz.antonin.rovergnss.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import cz.antonin.rovergnss.data.Prefs
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Zvuková a vibrační signalizace měření bodu (0.10.0). Rover zatím reproduktor nemá – signalizuje telefon.
 * Zvuk jde kanálem alarmu (nezávisle na hlasitosti médií), hraje i se zhasnutým displejem (služba na popředí).
 * Každý stav má jiný zvuk a vibrace, aby šel rozlišit bez pohledu na displej.
 */
class Signals(private val context: Context, private val prefs: Prefs) {

    /** Druh signálu: tóny (frekvence Hz, délka ms; 0 Hz = ticho) a vibrace (pauza, chvění, pauza, …). */
    enum class Kind(val tones: List<Pair<Int, Int>>, val vibe: LongArray) {
        FIX(listOf(1760 to 120), longArrayOf(0, 120)),
        LOST(listOf(440 to 150, 0 to 100, 440 to 150), longArrayOf(0, 120, 120, 120)),
        DONE(listOf(880 to 150, 0 to 40, 1175 to 150, 0 to 40, 1568 to 260), longArrayOf(0, 600)),
        SUSPECT(listOf(1320 to 150, 880 to 150, 1320 to 150, 880 to 150), longArrayOf(0, 100, 100, 100, 100, 100)),
        FAILED(listOf(330 to 900), longArrayOf(0, 500, 200, 500)),
    }

    @Volatile private var playing: AudioTrack? = null

    fun play(k: Kind) {
        val mode = prefs.signalMode
        if (mode == 1 || mode == 3) Thread({ tone(k) }, "signal").start()
        if (mode == 2 || mode == 3) vibrate(k)
    }

    private fun tone(k: Kind) {
        val rate = 44100
        val total = k.tones.sumOf { it.second } * rate / 1000
        val pcm = ShortArray(total)
        var i = 0
        for ((f, ms) in k.tones) {
            val n = ms * rate / 1000
            val fade = min(n / 4, rate / 200)   // 5 ms náběh a doběh bez lupnutí
            for (j in 0 until n) {
                if (i >= total) break
                if (f > 0) {
                    val env = when {
                        j < fade -> j.toDouble() / fade
                        j > n - fade -> (n - j).toDouble() / fade
                        else -> 1.0
                    }
                    pcm[i] = (sin(2 * PI * f * j / rate) * env * 0.8 * Short.MAX_VALUE).toInt().toShort()
                }
                i++
            }
        }
        try {
            playing?.let { runCatching { it.stop(); it.release() } }
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                )
                .setAudioFormat(
                    AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            t.write(pcm, 0, pcm.size)
            playing = t
            t.play()
            Thread.sleep(k.tones.sumOf { it.second }.toLong() + 100)
            runCatching { t.stop() }
            t.release()
            if (playing === t) playing = null
        } catch (e: Exception) {
            Log.w("Signals", "zvuk: ${e.message}")
        }
    }

    private fun vibrate(k: Kind) {
        try {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (!v.hasVibrator()) return
            // atributy alarmu: vibrace projde i se zhasnutým displejem
            @Suppress("DEPRECATION")
            v.vibrate(
                VibrationEffect.createWaveform(k.vibe, -1),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
            )
        } catch (e: Exception) {
            Log.w("Signals", "vibrace: ${e.message}")
        }
    }
}
