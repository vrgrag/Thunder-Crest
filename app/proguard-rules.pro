# Keep Compose runtime metadata intact.
-keepclassmembers class ** {
    @androidx.compose.runtime.Composable <methods>;
}
# WebView JS interface is not used, but keep WebView callbacks safe.
-keepclassmembers class * extends android.webkit.WebViewClient { *; }

# Gray flow entry points are referenced from AndroidManifest.xml / Firebase.
-keep class com.thundercrest.thundercrestgame.volt.VoltBoot { *; }
-keep class com.thundercrest.thundercrestgame.volt.VoltHubActivity { *; }
-keep class com.thundercrest.thundercrestgame.volt.VoltPaneActivity { *; }
-keep class com.thundercrest.thundercrestgame.volt.VoltPermitActivity { *; }
-keep class com.thundercrest.thundercrestgame.volt.VoltQuietActivity { *; }
-keep class com.thundercrest.thundercrestgame.WebActivity { *; }
-keep class com.thundercrest.thundercrestgame.volt.pipe.VoltPushService { *; }

# Keep serialized config reply shapes stable.
-keep class com.thundercrest.thundercrestgame.volt.kind.** { *; }
-keepclassmembers class com.thundercrest.thundercrestgame.volt.kind.** {
    public static ** serializer(...);
}

# Third-party SDKs used by attribution and push.
-keep class com.appsflyer.** { *; }
-dontwarn com.appsflyer.**
-keep class com.google.firebase.messaging.** { *; }
-dontwarn com.google.firebase.**
