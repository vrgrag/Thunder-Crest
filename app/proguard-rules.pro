# Keep Compose runtime metadata intact.
-keepclassmembers class ** {
    @androidx.compose.runtime.Composable <methods>;
}
# WebView JS interface is not used, but keep WebView callbacks safe.
-keepclassmembers class * extends android.webkit.WebViewClient { *; }
