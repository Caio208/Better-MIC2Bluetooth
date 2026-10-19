package io.github.thrillfall.mic2bluetooth

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import kotlin.math.max

enum class LoopbackMode { PHONE_MIC, HEADSET_MIC }

/** Finds headphones / headset mics (wired, USB, Bluetooth). */
object Routing {
    private val WIRED_OUT = intArrayOf(
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE
    )
    private val BT_OUT = intArrayOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    )
    private val WIRED_IN = intArrayOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE
    )

    /** Preferred output for the amplified sound: wired/USB first, then Bluetooth. Null = none. */
    fun headphoneOutput(am: AudioManager): AudioDeviceInfo? {
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        for (t in WIRED_OUT) outs.firstOrNull { it.type == t }?.let { return it }
        for (t in BT_OUT) outs.firstOrNull { it.type == t }?.let { return it }
        return null
    }

    fun wiredMic(am: AudioManager): AudioDeviceInfo? =
        am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type in WIRED_IN }

    fun scoDevice(am: AudioManager): AudioDeviceInfo? =
        am.availableCommunicationDevices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }

    fun isWired(d: AudioDeviceInfo) = d.type in WIRED_OUT
}

class AudioLoopback(private val ctx: Context) {

    @Volatile private var running = false
    private var thread: Thread? = null
    private var savedAudioMode: Int = AudioManager.MODE_NORMAL
    private var routedComm = false

    val isRunning: Boolean get() = running

    @SuppressLint("MissingPermission")
    fun start(
        mode: LoopbackMode,
        raw: Boolean,
        onError: (String) -> Unit,
        onEnded: () -> Unit = {}
    ) {
        if (running) return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Safety: without headphones the sound would come out of the speaker and howl.
        val headphones = Routing.headphoneOutput(am)
        if (headphones == null) {
            onError(ctx.getString(R.string.err_no_headphones))
            return
        }

        val nativeRate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.toIntOrNull()?.takeIf { it > 0 } ?: 48_000
        val unprocessedOk =
            am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"

        val sampleRate: Int
        val source: Int
        val usage: Int
        val contentType: Int
        var inputDevice: AudioDeviceInfo? = null
        var outputDevice: AudioDeviceInfo? = null

        val wiredMic = if (mode == LoopbackMode.HEADSET_MIC) Routing.wiredMic(am) else null
        if (mode == LoopbackMode.HEADSET_MIC && wiredMic == null) {
            // Bluetooth headset mic (SCO / LE): narrowband voice link, this is a Bluetooth limit.
            val sco = Routing.scoDevice(am)
            if (sco == null) {
                onError(ctx.getString(R.string.err_no_headset_mic))
                return
            }
            savedAudioMode = am.mode
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            if (!am.setCommunicationDevice(sco)) {
                am.mode = savedAudioMode
                onError(ctx.getString(R.string.err_sco))
                return
            }
            routedComm = true
            sampleRate = 16_000
            source = MediaRecorder.AudioSource.VOICE_COMMUNICATION
            usage = AudioAttributes.USAGE_VOICE_COMMUNICATION
            contentType = AudioAttributes.CONTENT_TYPE_SPEECH
        } else {
            sampleRate = nativeRate
            source = if (raw && unprocessedOk) MediaRecorder.AudioSource.UNPROCESSED
                     else MediaRecorder.AudioSource.MIC
            usage = AudioAttributes.USAGE_MEDIA
            contentType = AudioAttributes.CONTENT_TYPE_MUSIC
            inputDevice = wiredMic          // null = built-in phone mic
            outputDevice = headphones
        }

        running = true
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

            val inChannel = AudioFormat.CHANNEL_IN_MONO
            val outChannel = AudioFormat.CHANNEL_OUT_MONO
            val encoding = AudioFormat.ENCODING_PCM_16BIT

            val minIn = AudioRecord.getMinBufferSize(sampleRate, inChannel, encoding)
            val minOut = AudioTrack.getMinBufferSize(sampleRate, outChannel, encoding)
            if (minIn <= 0 || minOut <= 0) {
                running = false
                cleanupRouting()
                onError("Unsupported audio config")
                onEnded()
                return@Thread
            }
            // Each blocking read waits for a full chunk, so chunk size = delay. ~5 ms.
            val chunkFrames = max(sampleRate / 200, 64)
            val burst = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
                ?.toIntOrNull()?.takeIf { it > 0 } ?: 192
            val recBytes = max(minIn, chunkFrames * 4)

            var record: AudioRecord? = null
            var track: AudioTrack? = null
            try {
                record = AudioRecord(source, sampleRate, inChannel, encoding, recBytes)
                if (record.state != AudioRecord.STATE_INITIALIZED &&
                    source == MediaRecorder.AudioSource.UNPROCESSED
                ) {
                    record.release()
                    record = AudioRecord(
                        MediaRecorder.AudioSource.MIC, sampleRate, inChannel, encoding, recBytes
                    )
                }
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    onError("AudioRecord init failed")
                    return@Thread
                }
                record.setPreferredDevice(inputDevice) // null = built-in phone mic

                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(usage)
                            .setContentType(contentType)
                            .build()
                    )
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setEncoding(encoding)
                            .setChannelMask(outChannel)
                            .build()
                    )
                    .setBufferSizeInBytes(minOut) // capacity; active size is tuned below
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track.setPreferredDevice(outputDevice)  // null = system decides (SCO call route)

                // Start with a tiny active buffer; grow it only when underruns (crackle) occur
                track.bufferSizeInFrames = burst * 2
                Log.i(
                    TAG, "rate=$sampleRate chunk=${chunkFrames}f burst=$burst " +
                        "trackBuf=${track.bufferSizeInFrames}/${track.bufferCapacityInFrames}f " +
                        "src=$source"
                )
                var lastUnderruns = 0
                val dsp = Dsp(sampleRate)

                record.startRecording()
                track.play()

                val buf = ShortArray(chunkFrames)
                while (running) {
                    val n = record.read(buf, 0, buf.size)
                    if (n > 0) {
                        dsp.process(buf, n, LoopbackService.gain, LoopbackService.norm)
                        track.write(buf, 0, n, AudioTrack.WRITE_BLOCKING)
                        val u = track.underrunCount
                        if (u > lastUnderruns) {
                            lastUnderruns = u
                            track.bufferSizeInFrames =
                                minOf(track.bufferCapacityInFrames, track.bufferSizeInFrames + burst)
                            Log.i(TAG, "underrun -> trackBuf=${track.bufferSizeInFrames}f")
                        }
                    } else if (n < 0) {
                        Log.w(TAG, "AudioRecord.read returned $n")
                        break
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Loopback error", t)
                onError(t.message ?: t.javaClass.simpleName)
            } finally {
                runCatching { record?.stop() }
                runCatching { record?.release() }
                runCatching { track?.stop() }
                runCatching { track?.release() }
                cleanupRouting()
                running = false
                onEnded()
            }
        }, "audio-loopback").also {
            it.start()
        }
    }

    fun stop() {
        running = false
        thread?.join(1500)
        thread = null
    }

    private fun cleanupRouting() {
        if (!routedComm) return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        runCatching { am.clearCommunicationDevice() }
        runCatching { am.mode = savedAudioMode }
        routedComm = false
    }

    companion object { private const val TAG = "AudioLoopback" }
}
