package com.os73.dosos73

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class AppResourcesTest {
    @Test fun adaptiveForegroundHasDensityIndependentSize() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resources = context.resources
        val icon = resources.getDrawable(R.drawable.ic_brand_neon, context.theme)
        val widthDp = icon.intrinsicWidth / resources.displayMetrics.density
        assertEquals(108f, widthDp, 1f)
    }
    @Test fun advertisedLanguagesMatchShippedTranslations() {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val locales = mutableSetOf<String>()
        resources.getXml(R.xml.locales_config).use { parser ->
            while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "locale") {
                    locales.add(parser.getAttributeValue("http://schemas.android.com/apk/res/android", "name"))
                }
                parser.next()
            }
        }
        assertEquals(setOf("ar", "en"), locales)
    }
}

