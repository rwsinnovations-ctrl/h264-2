# This is a configuration file for ProGuard.
# http://proguard.sourceforge.net/index.html#manual/usage.html
# ProGuard is disabled by default in Android Studio.

# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the SDK, but you can change their order by adding the -keepattributes line below:
# -keepattributes SourceFile,LineNumberTable
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Keep AndroidX classes
-keep class androidx.** { *; }
-keep interface androidx.** { *; }

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}
