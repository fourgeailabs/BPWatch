package com.fourgeailabs.bpwatch.mobile.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CalibrationEngineTest {

    private fun perfectLinePoints() = listOf(
        // sys = 1.0 * hr + 60, dia = 0.5 * hr + 50
        CalibrationPoint(heartRate = 60f, sys = 120, dia = 80),
        CalibrationPoint(heartRate = 70f, sys = 130, dia = 85),
        CalibrationPoint(heartRate = 80f, sys = 140, dia = 90),
    )

    @Test
    fun `fit returns null with fewer than 3 points`() {
        assertNull(CalibrationEngine.fit(emptyList()))
        assertNull(
            CalibrationEngine.fit(
                listOf(CalibrationPoint(70f, 130, 85)),
            ),
        )
        assertNull(
            CalibrationEngine.fit(
                listOf(
                    CalibrationPoint(70f, 130, 85),
                    CalibrationPoint(80f, 140, 90),
                ),
            ),
        )
    }

    @Test
    fun `fit recovers an exact line from perfect data`() {
        val model = CalibrationEngine.fit(perfectLinePoints())
        assertNotNull(model)
        model!!
        assertEquals(3, model.points)
        assertEquals(1.0, model.aSys, 1e-9)
        assertEquals(60.0, model.bSys, 1e-9)
        assertEquals(0.5, model.aDia, 1e-9)
        assertEquals(50.0, model.bDia, 1e-9)
    }

    @Test
    fun `fit is order-independent`() {
        val model = CalibrationEngine.fit(perfectLinePoints().shuffled())
        assertNotNull(model)
        model!!
        assertEquals(1.0, model.aSys, 1e-9)
        assertEquals(60.0, model.bSys, 1e-9)
    }

    @Test
    fun `fit returns null when all heart rates are identical`() {
        // Degenerate: zero variance in x, no line can be fit.
        val points = listOf(
            CalibrationPoint(70f, 120, 80),
            CalibrationPoint(70f, 130, 85),
            CalibrationPoint(70f, 140, 90),
        )
        assertNull(CalibrationEngine.fit(points))
    }

    @Test
    fun `fit averages out noisy points`() {
        // Symmetric noise around sys = hr + 60 (deviations +2,-2,-2,+2 at
        // x-offsets -15,-5,+5,+15 cancel in the slope numerator): the fit
        // recovers the line exactly.
        val points = listOf(
            CalibrationPoint(60f, 122, 80),
            CalibrationPoint(70f, 128, 85),
            CalibrationPoint(80f, 138, 90),
            CalibrationPoint(90f, 152, 95),
        )
        val model = CalibrationEngine.fit(points)
        assertNotNull(model)
        model!!
        assertEquals(1.0, model.aSys, 1e-9)
        assertEquals(60.0, model.bSys, 1e-9)
    }

    @Test
    fun `estimate maps heart rate through the model`() {
        val model = CalibrationEngine.fit(perfectLinePoints())!!
        val (sys, dia) = CalibrationEngine.estimate(model, 70f)
        assertEquals(130, sys)
        assertEquals(85, dia)
        val (sys2, dia2) = CalibrationEngine.estimate(model, 100f)
        assertEquals(160, sys2)
        assertEquals(100, dia2)
    }

    @Test
    fun `estimate truncates fractional results toward zero`() {
        val model = CalibrationModel(
            aSys = 1.5, bSys = 0.0,
            aDia = 1.5, bDia = 0.0,
            points = 3,
        )
        // 1.5 * 81 = 121.5 -> toInt() drops the fraction.
        val (sys, dia) = CalibrationEngine.estimate(model, 81f)
        assertEquals(121, sys)
        assertEquals(121, dia)
    }

    @Test
    fun `estimate clamps to physiological bounds`() {
        val steep = CalibrationModel(
            aSys = 10.0, bSys = 0.0,
            aDia = 10.0, bDia = 0.0,
            points = 3,
        )
        val (sysHigh, diaHigh) = CalibrationEngine.estimate(steep, 100f)
        assertEquals(260, sysHigh)
        assertEquals(160, diaHigh)

        val (sysLow, diaLow) = CalibrationEngine.estimate(steep, 1f)
        assertEquals(70, sysLow)
        assertEquals(40, diaLow)
    }

    @Test
    fun `estimate clamps negative-going models at the floor`() {
        val falling = CalibrationModel(
            aSys = -1.0, bSys = 100.0,
            aDia = -1.0, bDia = 60.0,
            points = 3,
        )
        val (sys, dia) = CalibrationEngine.estimate(falling, 200f)
        assertEquals(70, sys)
        assertEquals(40, dia)
    }

    @Test
    fun `fit handles a zero heart rate without crashing`() {
        val points = listOf(
            CalibrationPoint(0f, 100, 70),
            CalibrationPoint(70f, 130, 85),
            CalibrationPoint(80f, 140, 90),
        )
        assertNotNull(CalibrationEngine.fit(points))
    }

    @Test
    fun `fit records the point count`() {
        val model = CalibrationEngine.fit(
            perfectLinePoints() + CalibrationPoint(90f, 150, 95),
        )
        assertNotNull(model)
        assertEquals(4, model!!.points)
    }
}
