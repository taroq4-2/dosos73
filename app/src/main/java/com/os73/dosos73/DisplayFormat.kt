package com.os73.dosos73

import java.text.NumberFormat
import java.util.Locale

/** Binary units with numbers formatted in the currently selected app language. */
internal fun bytesLabel(bytes: Long, locale: Locale): String {
    val value = bytes.coerceAtLeast(0)
    val units = arrayOf("B", "KiB", "MiB", "GiB", "TiB", "PiB", "EiB")
    var scaled = value.toDouble()
    var unit = 0
    while (scaled >= 1024 && unit < units.lastIndex) { scaled /= 1024; unit++ }
    val number = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 2 }
    return "${number.format(scaled)} ${units[unit]}"
}

internal fun percentLabel(fraction: Float, locale: Locale): String =
    NumberFormat.getPercentInstance(locale).apply { maximumFractionDigits = 0 }
        .format(fraction.coerceIn(0f, 1f).toDouble())
