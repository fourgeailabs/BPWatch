package com.fourgeailabs.bpwatch.mobile.profile

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Basic body profile: height, weight, age, sex.
 *
 * Honest note on what this does and doesn't do: BPWatch estimates blood
 * pressure from YOUR OWN cuff calibration, so your individual physiology —
 * including body size — is already baked into your personal calibration
 * curve. Height and weight don't change the estimate. They're collected for
 * BMI, general health context, and so the app can show a fuller picture
 * alongside each reading. Resting heart rate (from your calibration data) is
 * what actually drives the stress baseline.
 */
data class UserProfile(
    val heightCm: Float? = null,
    val weightKg: Float? = null,
    val age: Int? = null,
    val sex: String? = null, // "Female", "Male", "Other", "Prefer not to say"
) {
    /** Body-mass index, or null when height/weight aren't both set. */
    val bmi: Float?
        get() {
            val h = heightCm
            val w = weightKg
            if (h == null || w == null || h <= 0f) return null
            val m = h / 100f
            return w / (m * m)
        }

    val bmiLabel: String?
        get() = when (val b = bmi) {
            null -> null
            in 0f..<18.5f -> "Underweight"
            in 18.5f..<25f -> "Healthy"
            in 25f..<30f -> "Overweight"
            else -> "Obese"
        }
}

class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("bpwatch_profile", Context.MODE_PRIVATE)

    private val _profile = MutableStateFlow(load())
    val profile: StateFlow<UserProfile> = _profile.asStateFlow()

    private fun load(): UserProfile = UserProfile(
        heightCm = prefs.getFloatOrNull(KEY_HEIGHT),
        weightKg = prefs.getFloatOrNull(KEY_WEIGHT),
        age = prefs.getIntOrNull(KEY_AGE),
        sex = prefs.getString(KEY_SEX, null),
    )

    fun save(profile: UserProfile) {
        prefs.edit()
            .putFloatOrNull(KEY_HEIGHT, profile.heightCm)
            .putFloatOrNull(KEY_WEIGHT, profile.weightKg)
            .putIntOrNull(KEY_AGE, profile.age)
            .putString(KEY_SEX, profile.sex)
            .apply()
        _profile.value = profile
    }

    private fun android.content.SharedPreferences.getFloatOrNull(key: String): Float? =
        if (contains(key)) getFloat(key, 0f) else null

    private fun android.content.SharedPreferences.getIntOrNull(key: String): Int? =
        if (contains(key)) getInt(key, 0) else null

    private fun android.content.SharedPreferences.Editor.putFloatOrNull(
        key: String, value: Float?,
    ): android.content.SharedPreferences.Editor =
        if (value == null) remove(key) else putFloat(key, value)

    private fun android.content.SharedPreferences.Editor.putIntOrNull(
        key: String, value: Int?,
    ): android.content.SharedPreferences.Editor =
        if (value == null) remove(key) else putInt(key, value)

    companion object {
        private const val KEY_HEIGHT = "height_cm"
        private const val KEY_WEIGHT = "weight_kg"
        private const val KEY_AGE = "age"
        private const val KEY_SEX = "sex"
    }
}
