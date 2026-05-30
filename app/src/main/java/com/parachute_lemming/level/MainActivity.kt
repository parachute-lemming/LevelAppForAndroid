package com.parachute_lemming.level

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.Surface
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.parachute_lemming.level.databinding.ActivityMainBinding
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Bubble-level activity.
 *
 * Sensor convention (Android, device in natural portrait): when the phone is at rest,
 * the accelerometer/gravity sensor reports proper acceleration (= -gravity in device
 * frame), so the readings point along the device axis pushed up against gravity:
 *   flat on back, screen up   → (0, 0, +g)
 *   portrait upright (top up) → (0, +g, 0)
 *   right long edge down      → (-g, 0, 0)
 *   left long edge down       → (+g, 0, 0)
 *
 * Mode selection:
 *   ROUND   when |gz| dominates (phone flat-ish)
 *   TORPEDO when |gx| dominates (phone on a long edge)
 *   With hysteresis so mid-tilt jitter doesn't flicker.
 *
 * Calibration:
 *   Stores per-mode angle offsets (degrees). Applied by subtracting from the live angles.
 *   Small-angle approximation is fine for picture-frame work.
 */
class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var sensorManager: SensorManager
    private var gravitySensor: Sensor? = null
    private var accelerometerFallback: Sensor? = null

    private var gx = 0f
    private var gy = 0f
    private var gz = 9.81f
    private var hasFirstSample = false

    private var mode: LevelView.Mode = LevelView.Mode.ROUND
    private var pendingMode: LevelView.Mode = LevelView.Mode.ROUND
    private var pendingCount = 0

    private var calRoll = 0f
    private var calPitch = 0f
    private var calTilt = 0f
    private var hasRoundCal = false
    private var hasTorpedoCal = false

    private var invertRoll = false
    private var invertPitch = false

    private var rollToleranceDeg = Prefs.DEFAULT_TOLERANCE_DEG
    private var pitchToleranceDeg = Prefs.DEFAULT_TOLERANCE_DEG

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        inflateAndWire()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        if (gravitySensor == null) {
            accelerometerFallback = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // configChanges handles the rotation; reinflate the layout so the system can
        // pick the correct layout (portrait vs landscape) and rebind listeners.
        inflateAndWire()
        if (hasFirstSample) updateUi()
    }

    private fun inflateAndWire() {
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val pad = (16f * resources.displayMetrics.density).toInt()
            v.setPadding(sys.left + pad, sys.top + pad, sys.right + pad, sys.bottom + pad)
            insets
        }

        binding.btnCalibrate.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            calibrate()
        }
        binding.btnReset.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.REJECT)
            resetCalibration()
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    /**
     * Force the activity orientation to match the detected mode so the round level always
     * renders in the portrait layout and the torpedo always renders in the landscape
     * layout — independent of where the system thinks the device is pointed.
     */
    private fun applyOrientationForMode() {
        val target = when (mode) {
            LevelView.Mode.ROUND -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            LevelView.Mode.TORPEDO -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        if (requestedOrientation != target) requestedOrientation = target
    }

    override fun onResume() {
        super.onResume()
        loadPrefs()
        val sensor = gravitySensor ?: accelerometerFallback
        if (sensor != null) {
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent) {
        val rawX = event.values[0]
        val rawY = event.values[1]
        val rawZ = event.values[2]

        if (!hasFirstSample) {
            gx = rawX; gy = rawY; gz = rawZ
            hasFirstSample = true
        } else {
            // Heavy exponential moving average — ~400 ms response window at
            // SENSOR_DELAY_GAME. Levels don't need a fast bubble; a real spirit-level
            // bubble migrates through liquid with visible lag too. Fixed value, no
            // user-facing knob: kill jitter, accept a soft response.
            val a = SMOOTHING_ALPHA
            gx = gx * (1 - a) + rawX * a
            gy = gy * (1 - a) + rawY * a
            gz = gz * (1 - a) + rawZ * a
        }

        updateMode()
        updateUi()
    }

    private fun updateMode() {
        val absX = abs(gx); val absZ = abs(gz)
        val enterRound = absZ > 7.5f
        val enterTorpedo = absX > 7.5f && absZ < 5.5f
        val candidate = when {
            enterRound && mode != LevelView.Mode.ROUND -> LevelView.Mode.ROUND
            enterTorpedo && mode != LevelView.Mode.TORPEDO -> LevelView.Mode.TORPEDO
            else -> mode
        }
        if (candidate != mode) {
            if (candidate == pendingMode) pendingCount++ else { pendingMode = candidate; pendingCount = 1 }
            if (pendingCount >= 6) {
                mode = candidate
                pendingCount = 0
            }
        } else {
            pendingMode = mode
            pendingCount = 0
        }
        // Re-assert orientation every pass so first launch — where the system may have
        // inflated layout-land while the phone is actually flat — snaps to the layout
        // matching the detected mode. The setter inside is a no-op when already aligned.
        applyOrientationForMode()
    }

    private fun updateUi() {
        val rollSign = if (invertRoll) -1f else 1f
        val pitchSign = if (invertPitch) -1f else 1f

        when (mode) {
            LevelView.Mode.ROUND -> {
                val rollDeg = rollSign * Math.toDegrees(atan2(gx.toDouble(), gz.toDouble())).toFloat()
                val pitchDeg = pitchSign * Math.toDegrees(atan2(gy.toDouble(), gz.toDouble())).toFloat()

                val effRoll = if (hasRoundCal) rollDeg - calRoll else rollDeg
                val effPitch = if (hasRoundCal) pitchDeg - calPitch else pitchDeg

                binding.modeChip.setText(R.string.mode_round)
                binding.rollLabel.setText(R.string.roll_label)
                binding.pitchLabel.setText(R.string.pitch_label)
                binding.readouts.visibility = android.view.View.VISIBLE
                binding.pitchSlot.visibility = android.view.View.VISIBLE

                binding.rollValue.text = formatDegrees(effRoll)
                binding.pitchValue.text = formatDegrees(effPitch)

                val bx = (sin(Math.toRadians(effRoll.toDouble())) * BUBBLE_GAIN).toFloat()
                val by = (-sin(Math.toRadians(effPitch.toDouble())) * BUBBLE_GAIN).toFloat()

                binding.levelView.mode = LevelView.Mode.ROUND
                binding.levelView.bubbleX = bx
                binding.levelView.bubbleY = by
                binding.levelView.rollCentered = abs(effRoll) <= rollToleranceDeg
                binding.levelView.pitchCentered = abs(effPitch) <= pitchToleranceDeg
                binding.levelView.tiltCentered = false
                binding.levelView.invalidate()

                binding.calibrationStatus.visibility =
                    if (hasRoundCal) android.view.View.VISIBLE else android.view.View.INVISIBLE
            }

            LevelView.Mode.TORPEDO -> {
                val tiltDeg = pitchSign * Math.toDegrees(
                    atan2(gy.toDouble(), hypot(gx.toDouble(), gz.toDouble()))
                ).toFloat()
                val effTilt = if (hasTorpedoCal) tiltDeg - calTilt else tiltDeg

                binding.modeChip.setText(R.string.mode_torpedo)
                // Show one slot of the readouts row for Tilt; pitch slot is hidden so the
                // visible slot takes the full readout width.
                binding.readouts.visibility = android.view.View.VISIBLE
                binding.pitchSlot.visibility = android.view.View.GONE
                binding.rollLabel.setText(R.string.tilt_label)
                binding.rollValue.text = formatDegrees(effTilt)

                // Bubble offset along the level's long (phone Y) axis. Positive effTilt
                // means top of phone is up, so the bubble should move toward the top of
                // the phone. Whether that ends up on screen as +X, -X, +Y or -Y depends
                // on how the system rotated the display.
                val rawOffset = (sin(Math.toRadians(effTilt.toDouble())) * BUBBLE_GAIN).toFloat()
                val rotation = currentRotation()
                when (rotation) {
                    Surface.ROTATION_0 -> {
                        binding.levelView.bubbleX = 0f
                        binding.levelView.bubbleY = -rawOffset
                    }
                    Surface.ROTATION_180 -> {
                        binding.levelView.bubbleX = 0f
                        binding.levelView.bubbleY = rawOffset
                    }
                    Surface.ROTATION_90 -> {
                        // ROTATION_90 = device rotated 90° CCW from natural; LEFT long edge
                        // down; top of phone → user's left. Top-up should drive bubble left.
                        binding.levelView.bubbleX = -rawOffset
                        binding.levelView.bubbleY = 0f
                    }
                    Surface.ROTATION_270 -> {
                        // ROTATION_270 = device rotated 90° CW; RIGHT long edge down;
                        // top of phone → user's right. Top-up should drive bubble right.
                        binding.levelView.bubbleX = rawOffset
                        binding.levelView.bubbleY = 0f
                    }
                }
                binding.levelView.mode = LevelView.Mode.TORPEDO
                binding.levelView.tiltCentered = abs(effTilt) <= pitchToleranceDeg
                binding.levelView.rollCentered = false
                binding.levelView.pitchCentered = false
                binding.levelView.invalidate()

                binding.calibrationStatus.visibility =
                    if (hasTorpedoCal) android.view.View.VISIBLE else android.view.View.INVISIBLE
            }
        }
    }

    private fun calibrate() {
        // Calibrate from RAW angles (no invert, no gain) so a sign flip in Settings
        // doesn't invalidate the stored baseline.
        when (mode) {
            LevelView.Mode.ROUND -> {
                calRoll = Math.toDegrees(atan2(gx.toDouble(), gz.toDouble())).toFloat()
                calPitch = Math.toDegrees(atan2(gy.toDouble(), gz.toDouble())).toFloat()
                if (invertRoll) calRoll = -calRoll
                if (invertPitch) calPitch = -calPitch
                hasRoundCal = true
            }
            LevelView.Mode.TORPEDO -> {
                calTilt = Math.toDegrees(
                    atan2(gy.toDouble(), hypot(gx.toDouble(), gz.toDouble()))
                ).toFloat()
                if (invertPitch) calTilt = -calTilt
                hasTorpedoCal = true
            }
        }
        savePrefs()
        updateUi()
    }

    private fun resetCalibration() {
        when (mode) {
            LevelView.Mode.ROUND -> { calRoll = 0f; calPitch = 0f; hasRoundCal = false }
            LevelView.Mode.TORPEDO -> { calTilt = 0f; hasTorpedoCal = false }
        }
        savePrefs()
        updateUi()
    }

    private fun savePrefs() {
        getSharedPreferences(Prefs.NAME, MODE_PRIVATE).edit().apply {
            putBoolean(Prefs.HAS_ROUND_CAL, hasRoundCal)
            putFloat(Prefs.CAL_ROLL, calRoll)
            putFloat(Prefs.CAL_PITCH, calPitch)
            putBoolean(Prefs.HAS_TORPEDO_CAL, hasTorpedoCal)
            putFloat(Prefs.CAL_TILT, calTilt)
        }.apply()
    }

    private fun loadPrefs() {
        val p = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
        hasRoundCal = p.getBoolean(Prefs.HAS_ROUND_CAL, false)
        calRoll = p.getFloat(Prefs.CAL_ROLL, 0f)
        calPitch = p.getFloat(Prefs.CAL_PITCH, 0f)
        hasTorpedoCal = p.getBoolean(Prefs.HAS_TORPEDO_CAL, false)
        calTilt = p.getFloat(Prefs.CAL_TILT, 0f)
        invertRoll = p.getBoolean(Prefs.INVERT_ROLL, false)
        invertPitch = p.getBoolean(Prefs.INVERT_PITCH, false)
        rollToleranceDeg = p.getFloat(Prefs.ROLL_TOLERANCE, Prefs.DEFAULT_TOLERANCE_DEG)
        pitchToleranceDeg = p.getFloat(Prefs.PITCH_TOLERANCE, Prefs.DEFAULT_TOLERANCE_DEG)
    }

    @Suppress("DEPRECATION")
    private fun currentRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display?.rotation ?: Surface.ROTATION_0
        else windowManager.defaultDisplay.rotation

    private fun formatDegrees(v: Float): String {
        val safe = if (v.isFinite()) v else 0f
        return String.format("%+.2f°", safe)
    }

    companion object {
        // Bubble visual gain. Pure sin(angle) makes small tilts nearly invisible; bumping
        // the offset multiplier lets typical picture-frame tilts (1–5°) show clear motion.
        // Readouts are NOT scaled — they remain true degrees.
        private const val BUBBLE_GAIN = 5f

        // EMA alpha. At SENSOR_DELAY_GAME (~20 ms): tau = -20/ln(0.95) ≈ 390 ms.
        private const val SMOOTHING_ALPHA = 0.05f
    }
}
