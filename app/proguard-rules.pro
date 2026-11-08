-keep class com.github.barteksc.pdfviewer.** { *; }
-keep class com.shockwave.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
