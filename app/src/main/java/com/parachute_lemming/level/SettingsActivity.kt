package com.parachute_lemming.level

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.parachute_lemming.level.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            insets
        }

        val prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)

        binding.switchInvertRoll.isChecked = prefs.getBoolean(Prefs.INVERT_ROLL, false)
        binding.switchInvertPitch.isChecked = prefs.getBoolean(Prefs.INVERT_PITCH, false)
        binding.switchInvertRoll.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(Prefs.INVERT_ROLL, checked).apply()
        }
        binding.switchInvertPitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(Prefs.INVERT_PITCH, checked).apply()
        }

        val rollTol = prefs.getFloat(Prefs.ROLL_TOLERANCE, Prefs.DEFAULT_TOLERANCE_DEG)
        val pitchTol = prefs.getFloat(Prefs.PITCH_TOLERANCE, Prefs.DEFAULT_TOLERANCE_DEG)
        binding.sliderRollTol.value = rollTol.coerceIn(Prefs.MIN_TOLERANCE_DEG, Prefs.MAX_TOLERANCE_DEG)
        binding.sliderPitchTol.value = pitchTol.coerceIn(Prefs.MIN_TOLERANCE_DEG, Prefs.MAX_TOLERANCE_DEG)
        binding.rollTolValue.text = formatTolerance(binding.sliderRollTol.value)
        binding.pitchTolValue.text = formatTolerance(binding.sliderPitchTol.value)

        binding.sliderRollTol.addOnChangeListener { _, value, _ ->
            binding.rollTolValue.text = formatTolerance(value)
            prefs.edit().putFloat(Prefs.ROLL_TOLERANCE, value).apply()
        }
        binding.sliderPitchTol.addOnChangeListener { _, value, _ ->
            binding.pitchTolValue.text = formatTolerance(value)
            prefs.edit().putFloat(Prefs.PITCH_TOLERANCE, value).apply()
        }

        binding.btnRollMinus.setOnClickListener { stepSlider(binding.sliderRollTol, -0.01f) }
        binding.btnRollPlus.setOnClickListener { stepSlider(binding.sliderRollTol, +0.01f) }
        binding.btnPitchMinus.setOnClickListener { stepSlider(binding.sliderPitchTol, -0.01f) }
        binding.btnPitchPlus.setOnClickListener { stepSlider(binding.sliderPitchTol, +0.01f) }

        binding.btnBack.setOnClickListener { finish() }
    }

    private fun stepSlider(slider: com.google.android.material.slider.Slider, delta: Float) {
        val raw = slider.value + delta
        val snapped = (Math.round(raw * 100f) / 100f)
            .coerceIn(Prefs.MIN_TOLERANCE_DEG, Prefs.MAX_TOLERANCE_DEG)
        if (snapped != slider.value) slider.value = snapped
    }

    private fun formatTolerance(deg: Float): String = String.format("±%.2f°", deg)
}
