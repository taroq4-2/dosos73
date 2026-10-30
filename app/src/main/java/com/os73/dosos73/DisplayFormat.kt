package com.os73.dosos73

import java.text.NumberFormat
import java.util.Locale

private val binaryUnits = arrayOf("B", "KiB", "MiB", "GiB", "TiB", "PiB", "EiB")
private data class DisplayFormats(val locale: Locale, val number: NumberFormat, val percent: NumberFormat)
private val localFormats = ThreadLocal<DisplayFormats>()
private fun formats(locale: Locale): DisplayFormats = localFormats.get()?.takeIf { it.locale == locale }
    ?: DisplayFormats(locale, NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 2 },
        NumberFormat.getPercentInstance(locale).apply { maximumFractionDigits = 0 }).also { localFormats.set(it) }

/** Bounded, thread-confined formatters retain the selected locale without per-progress recreation. */
internal fun bytesLabel(bytes: Long, locale: Locale): String {
    var scaled = bytes.coerceAtLeast(0).toDouble()
    var unit = 0
    while (scaled >= 1024 && unit < binaryUnits.lastIndex) { scaled /= 1024; unit++ }
    return "${formats(locale).number.format(scaled)} ${binaryUnits[unit]}"
}

internal fun percentLabel(fraction: Float, locale: Locale): String =
    formats(locale).percent.format(fraction.coerceIn(0f, 1f).toDouble())
