# The wrapper maps JSON using Jackson reflection and bundles native executables.
-keep class com.yausername.youtubedl_android.** { *; }
-keep class com.yausername.ffmpeg.** { *; }
# Commons Compress registers ZIP extra fields reflectively during native runtime extraction.
# Preserve their concrete types and public no-arg constructors in optimized release builds.
-keep class * implements org.apache.commons.compress.archivers.zip.ZipExtraField { *; }
-keep class com.fasterxml.jackson.** { *; }
-dontwarn org.slf4j.impl.StaticLoggerBinder
# Jackson's optional desktop JavaBeans/XML extensions probe these classes using reflection.
# Android has no java.beans or DOM bootstrap. This app parses JSON, never JavaBeans/XML.
-dontwarn java.beans.ConstructorProperties
-dontwarn java.beans.Transient
-dontwarn org.w3c.dom.bootstrap.DOMImplementationRegistry
