# Keep Compose runtime metadata intact.
-keepclassmembers class ** {
    @androidx.compose.runtime.Composable <methods>;
}

# WebView callbacks & JS interfaces must be preserved.
-keepclassmembers class * extends android.webkit.WebViewClient { *; }
-keepclassmembers class * extends android.webkit.WebChromeClient { *; }
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# --- AppsFlyer ---
-keep class com.appsflyer.** { *; }
-dontwarn com.appsflyer.**
-keep class com.android.installreferrer.** { *; }
-dontwarn com.android.installreferrer.**

# --- Firebase Messaging ---
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# --- OkHttp / Okio ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**

# Keep app package (models etc.) reflectable.
-keep class com.thundercrest.thundercrestgame.gray.** { *; }

# General reflection safety.
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
