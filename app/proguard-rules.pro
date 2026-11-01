# Flit R8 rules.
# The default "proguard-android-optimize.txt" already covers the Android framework.
# Flit uses no reflection, no JSON/serialization library, no native code and no
# third-party SDKs, so no -keep rules are required.

# Keep readable stack traces for crash reports and logcat.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile