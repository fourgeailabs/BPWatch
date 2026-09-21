package com.fourgeailabs.bpwatch.mobile.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UserProfileTest {

    @Test
    fun `bmi is null when height or weight is missing`() {
        assertNull(UserProfile().bmi)
        assertNull(UserProfile(heightCm = 180f).bmi)
        assertNull(UserProfile(weightKg = 80f).bmi)
    }

    @Test
    fun `bmi is null for non-positive height`() {
        assertNull(UserProfile(heightCm = 0f, weightKg = 80f).bmi)
        assertNull(UserProfile(heightCm = -180f, weightKg = 80f).bmi)
    }

    @Test
    fun `bmi computes weight over height squared`() {
        // 80 kg at 180 cm -> 80 / 1.8^2 = 24.691...
        val bmi = UserProfile(heightCm = 180f, weightKg = 80f).bmi
        assertEquals(24.69, bmi!!.toDouble(), 0.01)
    }

    @Test
    fun `bmi ignores age and sex`() {
        val a = UserProfile(heightCm = 180f, weightKg = 80f, age = 30, sex = "Male")
        val b = UserProfile(heightCm = 180f, weightKg = 80f, age = 60, sex = "Female")
        assertEquals(a.bmi!!, b.bmi!!, 0.0f)
    }

    @Test
    fun `bmiLabel is null without a bmi`() {
        assertNull(UserProfile().bmiLabel)
    }

    @Test
    fun `bmiLabel classifies standard boundaries`() {
        // Height 200 cm keeps the arithmetic exact: bmi = weight / 4.
        fun labelFor(weightKg: Float) =
            UserProfile(heightCm = 200f, weightKg = weightKg).bmiLabel

        assertEquals("Underweight", labelFor(70f)) // 17.5
        assertEquals("Healthy", labelFor(74f)) // exactly 18.5 -> healthy band
        assertEquals("Healthy", labelFor(99.6f)) // 24.9
        assertEquals("Overweight", labelFor(100f)) // exactly 25.0
        assertEquals("Overweight", labelFor(119.6f)) // 29.9
        assertEquals("Obese", labelFor(120f)) // exactly 30.0
    }
}
