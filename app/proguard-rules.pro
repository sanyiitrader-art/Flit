# Flit R8 rules.
# The default "proguard-android-optimize.txt" already covers the Android framework.
# Flit uses no reflection of its own, no JSON/serialization library and no native code.

# Keep readable stack traces for crash reports and logcat.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Archive libraries reference optional compressors and desktop-only classes that Flit does not
# ship (Brotli, Zstd, ASM, java.awt, ...). Those code paths are never used, so silence R8.
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**
-dontwarn com.github.junrar.**
-dontwarn org.slf4j.**
-dontwarn java.awt.**
-dontwarn javax.**
-dontwarn org.osgi.**
-dontwarn aQute.**

# Markdown parser extensions reference optional classes.
-dontwarn org.commonmark.**
-dontwarn org.nibor.autolink.**