package com.fourgeailabs.bpwatch.mobile.calibration

/**
 * The honest core of this app.
 *
 * The Galaxy Watch's blood-pressure feature is Samsung-proprietary and only
 * works with Samsung phones. No public API exposes blood pressure (or SpO2)
 * to third-party apps, so instead we do what Samsung's own feature does at a
 * high level: calibrate against a real cuff.
 *
 * Protocol: the user takes N (>=3) cuff readings while the watch measures
 * heart rate at the same time. We fit a least-squares line mapping resting
 * heart rate -> systolic and heart rate -> diastolic. Later watch readings
 * are run through that line to produce an ESTIMATE.
 *
 * This is a wellness estimate, not a medical measurement. Heart rate alone
 * is a weak predictor of blood pressure; the estimate is only as good as
 * the calibration, and it drifts as fitness, medication, or stress change.
 * Always confirm with a cuff before making any health decision.
 */
data class CalibrationPoint(
    val heartRate: Float,
    val sys: Int,
    val dia: Int,
)

data class CalibrationModel(
    val aSys: Double,
    val bSys: Double,
    val aDia: Double,
    val bDia: Double,
    val points: Int,
)

object CalibrationEngine {

    const val MIN_POINTS = 3

    fun fit(points: List<CalibrationPoint>): CalibrationModel? {
        if (points.size < MIN_POINTS) return null
        val sysLine = leastSquares(points.map { it.heartRate.toDouble() to it.sys.toDouble() })
            ?: return null
        val diaLine = leastSquares(points.map { it.heartRate.toDouble() to it.dia.toDouble() })
            ?: return null
        return CalibrationModel(
            aSys = sysLine.first, bSys = sysLine.second,
            aDia = diaLine.first, bDia = diaLine.second,
            points = points.size,
        )
    }

    fun estimate(model: CalibrationModel, heartRate: Float): Pair<Int, Int> {
        val sys = (model.aSys * heartRate + model.bSys).toInt().coerceIn(70, 260)
        val dia = (model.aDia * heartRate + model.bDia).toInt().coerceIn(40, 160)
        return sys to dia
    }

    /** Ordinary least squares: returns (slope, intercept), or null if degenerate. */
    private fun leastSquares(xy: List<Pair<Double, Double>>): Pair<Double, Double>? {
        val n = xy.size.toDouble()
        val meanX = xy.sumOf { it.first } / n
        val meanY = xy.sumOf { it.second } / n
        val denom = xy.sumOf { (it.first - meanX) * (it.first - meanX) }
        if (denom == 0.0) return null // all heart rates identical: can't fit a line
        val slope = xy.sumOf { (it.first - meanX) * (it.second - meanY) } / denom
        return slope to (meanY - slope * meanX)
    }
}
