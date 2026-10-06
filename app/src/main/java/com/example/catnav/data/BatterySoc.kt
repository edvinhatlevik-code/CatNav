package com.example.catnav.data

object BatterySoc {
    private val curve = listOf(
        3_000 to 0.0,
        3_300 to 5.0,
        3_500 to 10.0,
        3_600 to 20.0,
        3_700 to 35.0,
        3_750 to 50.0,
        3_800 to 65.0,
        3_900 to 80.0,
        4_000 to 90.0,
        4_100 to 95.0,
        4_200 to 100.0
    )

    fun percent(millivolts: Int?, criticalMillivolts: Int): Int? {
        if (millivolts == null) return null
        val threshold = criticalMillivolts.coerceIn(3_000, 4_200)
        if (millivolts <= threshold) return 0

        val atThreshold = standardPercent(threshold)
        val availableCapacity = 100.0 - atThreshold
        if (availableCapacity <= 0.0) return 100

        val normalized = (standardPercent(millivolts.coerceIn(3_000, 4_200)) - atThreshold) *
            100.0 / availableCapacity
        return normalized.toInt().coerceIn(0, 100)
    }

    private fun standardPercent(millivolts: Int): Double {
        if (millivolts <= curve.first().first) return curve.first().second
        if (millivolts >= curve.last().first) return curve.last().second

        val upperIndex = curve.indexOfFirst { it.first >= millivolts }
        val lower = curve[upperIndex - 1]
        val upper = curve[upperIndex]
        val fraction = (millivolts - lower.first).toDouble() / (upper.first - lower.first)
        return lower.second + (upper.second - lower.second) * fraction
    }
}
