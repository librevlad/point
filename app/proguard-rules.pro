# Point release keep-rules (#11). Hilt / Compose / ML Kit ship their own consumer
# rules; below are only the seams R8 cannot see through.

# Tesseract4Android and OpenCV talk to native code — JNI looks classes up by name.
-keep class cz.adaptech.tesseract4android.** { *; }
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }
-keep class org.opencv.** { *; }

# PdfBox-Android loads fonts/codecs reflectively.
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**

# JUnRar walks archive entries via reflection-friendly models.
-keep class com.github.junrar.** { *; }
-dontwarn com.github.junrar.**

# Commons-Compress optional codecs we do not bundle.
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**

# Kotlin coroutines debug metadata is safe to drop warnings for.
-dontwarn kotlinx.coroutines.debug.**

# slf4j has no runtime binding on Android (pdfbox/compress log through a no-op).
-dontwarn org.slf4j.**
