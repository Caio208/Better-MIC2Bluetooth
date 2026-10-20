package io.github.thrillfall.mic2bluetooth

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import io.github.thrillfall.mic2bluetooth.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private var pendingMode: LoopbackMode? = null
    private var createdWithMonet = false
    private val handler = Handler(Looper.getMainLooper())

    /** Shows how many dB the normalization is currently pulling the sound down. */
    private val normTicker = object : Runnable {
        override fun run() {
            updateNormLabel()
            if (LoopbackService.running) handler.postDelayed(this, 100)
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = updateOutputLabel()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = updateOutputLabel()
    }

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val mode = pendingMode
        pendingMode = null
        // Notification permission is optional; only mic (and BT for the headset mic) are required
        val required = results.filterKeys { it != Manifest.permission.POST_NOTIFICATIONS }
        if (required.values.all { it } && mode != null) {
            startLoopback()
        } else {
            setStatus(getString(R.string.status_permission_denied))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Appearance.beforeCreate(this)
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        createdWithMonet = prefs.theme == Prefs.THEME_MONET
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        Appearance.applyFont(b.root)

        LoopbackService.gain = prefs.gain
        LoopbackService.norm = prefs.normMode

        // Amplification (live)
        b.gainSlider.value = prefs.gain.coerceIn(1f, 10f)
        b.gainLabel.text = getString(R.string.gain_label, b.gainSlider.value)
        b.gainSlider.addOnChangeListener { _, value, _ ->
            LoopbackService.gain = value
            prefs.gain = value
            b.gainLabel.text = getString(R.string.gain_label, value)
        }

        // Volume normalization (live)
        b.normGroup.check(
            when (prefs.normMode) { 1 -> R.id.normSoft; 2 -> R.id.normStrong; else -> R.id.normOff }
        )
        b.normGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val m = when (id) { R.id.normSoft -> 1; R.id.normStrong -> 2; else -> 0 }
            LoopbackService.norm = m
            prefs.normMode = m
            updateNormLabel()
        }

        // Headset microphone (needs a restart of the stream)
        b.headsetSwitch.isChecked = prefs.headsetMic
        b.headsetSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.headsetMic = checked
            if (LoopbackService.running) stopLoopback()
            updateUi()
        }

        b.micButton.setOnClickListener {
            if (LoopbackService.running) stopLoopback() else ensurePermissionAndStart()
        }
        b.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        if ((prefs.theme == Prefs.THEME_MONET) != createdWithMonet) {
            recreate()
            return
        }
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        am.registerAudioDeviceCallback(deviceCallback, handler)
        LoopbackService.onStateChange = { err ->
            if (err != null) setStatus(getString(R.string.status_error, err))
            updateUi(keepStatus = err != null)
        }
        applyLook()
        updateUi()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(normTicker)
        LoopbackService.onStateChange = null
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        am.unregisterAudioDeviceCallback(deviceCallback)
        b.waveView.active = false
    }

    /** Colors, background, waves and mic button style from the saved settings. */
    private fun applyLook() {
        val pal = Appearance.applyBackground(this, b.root, b.bgImage, b.bgDim)
        Appearance.setupWindow(this, b.content, pal.on)
        Appearance.styleCard(b.controlsCard, pal)

        listOf(b.titleText, b.outputLabel, b.statusLabel, b.gainLabel, b.normLabel)
            .forEach { it.setTextColor(pal.on) }

        b.settingsButton.imageTintList = ColorStateList.valueOf(pal.on)
        b.settingsButton.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ColorUtils.setAlphaComponent(pal.on, 36))
        }

        val accent = ColorStateList.valueOf(pal.accent)
        b.gainSlider.trackActiveTintList = accent
        b.gainSlider.thumbTintList = accent
        b.gainSlider.haloTintList = accent
        b.gainSlider.trackInactiveTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(pal.on, 60))

        b.headsetSwitch.setTextColor(pal.on)
        b.headsetSwitch.thumbTintList = Appearance.stateList(pal.accent, Color.parseColor("#9E9E9E"))
        b.headsetSwitch.trackTintList = Appearance.stateList(
            ColorUtils.setAlphaComponent(pal.accent, 110),
            ColorUtils.setAlphaComponent(pal.on, 50)
        )

        listOf(b.normOff, b.normSoft, b.normStrong).forEach {
            it.setTextColor(pal.on)
            it.strokeColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(pal.on, 120))
            it.backgroundTintList = Appearance.stateList(
                ColorUtils.setAlphaComponent(pal.accent, 100), Color.TRANSPARENT
            )
        }

        b.waveView.colors = Appearance.waveColors(this, prefs)
        b.waveView.visibility = if (prefs.wavesOn) View.VISIBLE else View.GONE
    }

    private fun selectedMode(): LoopbackMode =
        if (b.headsetSwitch.isChecked) LoopbackMode.HEADSET_MIC else LoopbackMode.PHONE_MIC

    private fun ensurePermissionAndStart() {
        val mode = selectedMode()
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (mode == LoopbackMode.HEADSET_MIC) needed += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startLoopback()
        } else {
            pendingMode = mode
            requestPermissions.launch(missing.toTypedArray())
        }
    }

    private fun startLoopback() {
        val intent = Intent(this, LoopbackService::class.java)
            .putExtra(LoopbackService.EXTRA_MODE, selectedMode().name)
            .putExtra(LoopbackService.EXTRA_RAW, prefs.rawMic)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopLoopback() {
        stopService(Intent(this, LoopbackService::class.java))
    }

    private fun updateUi(keepStatus: Boolean = false) {
        val running = LoopbackService.running
        Appearance.styleMicButton(b.micButton, prefs, running)
        b.waveView.active = running
        if (!keepStatus) {
            setStatus(getString(if (running) R.string.status_running else R.string.status_idle))
        }
        updateOutputLabel()
        handler.removeCallbacks(normTicker)
        if (running) handler.post(normTicker) else updateNormLabel()
    }

    private fun updateNormLabel() {
        val r = LoopbackService.reductionDb
        b.normLabel.text =
            if (LoopbackService.running && LoopbackService.norm != 0 && r >= 1.5f)
                getString(R.string.norm_label_active, r)
            else getString(R.string.norm_label)
    }

    private fun updateOutputLabel() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val out = Routing.headphoneOutput(am)
        b.outputLabel.text = if (out == null) {
            getString(R.string.output_none)
        } else {
            val kind = when {
                out.type == AudioDeviceInfo.TYPE_USB_HEADSET || out.type == AudioDeviceInfo.TYPE_USB_DEVICE ->
                    getString(R.string.kind_usb)
                Routing.isWired(out) -> getString(R.string.kind_wired)
                else -> getString(R.string.kind_bluetooth)
            }
            getString(R.string.output_device, out.productName ?: "—", kind)
        }
    }

    private fun setStatus(text: String) {
        b.statusLabel.text = text
    }
}
