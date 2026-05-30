package com.parachute_lemming.level

object Prefs {
    const val NAME = "truelevel_prefs"

    const val HAS_ROUND_CAL = "has_round_cal"
    const val CAL_ROLL = "cal_roll"
    const val CAL_PITCH = "cal_pitch"
    const val HAS_TORPEDO_CAL = "has_torpedo_cal"
    const val CAL_TILT = "cal_tilt"

    const val INVERT_ROLL = "invert_roll"
    const val INVERT_PITCH = "invert_pitch"

    const val ROLL_TOLERANCE = "roll_tolerance_deg"
    const val PITCH_TOLERANCE = "pitch_tolerance_deg"

    const val DEFAULT_TOLERANCE_DEG = 0.2f
    const val MIN_TOLERANCE_DEG = 0.01f
    const val MAX_TOLERANCE_DEG = 3.0f
}
