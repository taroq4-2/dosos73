package com.os73.dosos73

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class AppResourcesTest {
    @Test
    fun systemSplashUsesTheSameNavyCanvas() {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 31)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val theme = context.resources.newTheme().apply { applyStyle(R.style.AppTheme, true) }
        val value = android.util.TypedValue()
        assertTrue(theme.resolveAttribute(android.R.attr.windowSplashScreenBackground, value, true))
        assertEquals(android.graphics.Color.rgb(7, 15, 30), value.data)
    }
    @Test
    fun adaptiveForegroundHasDensityIndependentSize() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resources = context.resources
        val icon = resources.getDrawable(R.drawable.ic_brand_neon, context.theme)
        val widthDp = icon.intrinsicWidth / resources.displayMetrics.density
        assertEquals(108f, widthDp, 1f)
    }

    @Test
    fun advertisedLanguagesMatchShippedTranslations() {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val locales = mutableSetOf<String>()
        resources.getXml(R.xml.locales_config).use { parser ->
            while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (
                    parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG &&
                        parser.name == "locale"
                ) {
                    locales.add(
                        parser.getAttributeValue(
                            "http://schemas.android.com/apk/res/android",
                            "name",
                        )
                    )
                }
                parser.next()
            }
        }
        assertEquals(setOf("ar", "en"), locales)
    }
}

