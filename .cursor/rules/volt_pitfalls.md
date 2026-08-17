# Volt Gray-Part Pitfalls — Native Kotlin Edition

Mistakes that bit during ports of the gray-flow template to native
Android Kotlin (Thunder Crest, Aug 2026). Each section lists the
symptom, the root cause, and the exact fix. Apply these proactively
when scaffolding a new project or fork; do not wait for the error to
appear.

---

## 1. AppsFlyer + Firebase multidex / R8 stripping

### Symptom

At runtime the AppsFlyer callbacks never fire, or Firebase Messaging
throws `NoSuchMethodError` from `FirebaseInstallations`.

### Cause

R8 strips the SDK's reflection-driven classes because we did not add
`-keep` rules — the release build ships without half of the SDK.

### Fix

`app/proguard-rules.pro`:

```
# AppsFlyer
-keep class com.appsflyer.** { *; }
-keep class com.android.installreferrer.** { *; }

# Firebase
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**

# Our messaging service (referenced only from the manifest)
-keep class com.thundercrest.thundercrestgame.volt.gateway.VoltTokenService { *; }

# kotlinx.serialization polymorphism (used by VoltGate body)
-keepclassmembers class **$$serializer { *; }
-keep,includedescriptorclasses class com.thundercrest.thundercrestgame.volt.model.** { *; }
```

---

## 2. VPN makes `VoltOfflineScreen` flash on a healthy connection

### Symptoms

- Switching VPN on/off briefly shows `VoltOfflineScreen` even though
  the network is up.
- With VPN **on** the offline screen never disappears.

### Cause

`ConnectivityManager.NetworkCallback` emits `onLost` for a few hundred
ms while the VPN interface is being brought up, and `Network.hasCapability(NET_CAPABILITY_INTERNET)`
sometimes flips false during the handover.

### Fix

**A. Whitelist VPN as a real network:** in `VoltLink.hasAnyAdapter()`,
accept `NetworkCapabilities.TRANSPORT_VPN` as valid connectivity.

```kotlin
val cm = ctx.getSystemService(ConnectivityManager::class.java)
val n  = cm.activeNetwork ?: return false
val c  = cm.getNetworkCapabilities(n) ?: return false
return c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && (
    c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     ||
    c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
    c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
    c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)      ||
    c.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)
)
```

**B. Probe reachability with a TCP connect to a raw IP, not DNS.**
`VoltLink` splits the question in two: `hasAnyAdapter()` is the cheap
synchronous capability check above, and `isReachable()` opens a short
socket to `1.1.1.1:443`, falling back to `8.8.8.8:53`. Raw IPs are
deliberate — a captive portal or a half-connected VPN answers DNS
happily while routing nothing, so a hostname lookup reports "online" for
a device that cannot reach the config endpoint. Keep the connect timeout
around 7 s; a genuinely dead link fails far faster than that, and the
budget only matters on a slow-but-alive network.

Use `hasAnyAdapter()` for anything on the first-frame path and
`isReachable()` before committing to a decision that depends on the
server actually answering.

**C. Debounce the offline callback by 700 ms.** Any `onLost` that is
followed by an `onAvailable` inside the debounce window is ignored.

```kotlin
private val offlineJob = MutableStateFlow<Job?>(null)
private fun onLost() {
    offlineJob.value?.cancel()
    offlineJob.value = scope.launch {
        delay(700)
        _state.emit(VoltLinkState.Offline)
    }
}
private fun onAvailable() {
    offlineJob.value?.cancel()
    _state.emit(VoltLinkState.Online)
}
```

Do NOT debounce the very first synchronous boot check in
`VoltHubActivity.onCreate()` — that must be instant.

---

## 3. `ERR_NAME_NOT_RESOLVED` shows the native WebView error page first

### Symptom

The Chrome-style "webpage not available" error screen flashes for 2–7 s
before `VoltOfflineScreen` appears.

### Fix

In `WebViewClient.onReceivedError` (`VoltPaneActivity`):

1. **Immediately** show the loading overlay so the native error page
   is hidden.
2. For known DNS / disconnect codes
   (`ERROR_HOST_LOOKUP`, `ERROR_CONNECT`, `ERROR_TIMEOUT`), skip the
   redundant reachability probe and route straight to
   `VoltQuietActivity`, passing the last settled URL as `resumeUrl`
   so Retry returns to the page instead of restarting the flow.

```kotlin
override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
    if (!req.isForMainFrame) return
    showSpinner()
    when (err.errorCode) {
        ERROR_HOST_LOOKUP, ERROR_CONNECT, ERROR_TIMEOUT -> gotoOfflineDirect()
        else -> guardOffline()
    }
}
```

---

## 4. Cookies do not survive across the WebView redirect

### Symptom

Login inside the partner site persists inside one navigation but is
lost on redirect back from the payment provider.

### Fix

Enable third-party cookies explicitly and flush after every navigation:

```kotlin
CookieManager.getInstance().apply {
    setAcceptCookie(true)
    setAcceptThirdPartyCookies(webView, true)
}
webView.webViewClient = object : WebViewClient() {
    override fun onPageFinished(view: WebView, url: String?) {
        CookieManager.getInstance().flush()
        super.onPageFinished(view, url)
    }
}
```

---

## 5. Kotlin incremental compilation cache fails when project path has spaces

### Symptom

```
Could not close incremental caches in C:\...\My Projects\...
```

### Fix

`gradle.properties`:

```
kotlin.incremental=false
```

Already applied when the project lives under `D:\flutter_proj\` (safe
path) but keep the option ready for forks.

---

## 6. Release `versionCode` / `versionName` must be bumped per build

### Fix

Bump in `app/build.gradle.kts`:

```kotlin
defaultConfig {
    versionCode = 2
    versionName = "1.0.1"
}
```

There is no `pubspec.yaml` here — everything lives in one gradle file.

---

## 7. Stale R class after moving Kotlin package

### Symptom

After renaming `com.thundercrest...` → new package, R.java references
still point to the old namespace and the build fails.

### Fix

```powershell
./gradlew.bat --stop
./gradlew.bat clean
# then rebuild
```

Remember to also update `namespace = ...` in `app/build.gradle.kts`
and `applicationId`.

---

## 8. 16 KB page-size support (Android 15+, mandatory Nov 1 2025)

### Fix

- AGP 8.5.2+ (currently 8.13.0 ✓).
- NDK 27+ (Jetpack Compose brings its own natives — verify each ABI's `.so`
  has `LOAD` segments aligned to `0x4000`).

Verification:

```powershell
apkanalyzer files list app\build\outputs\apk\release\*.apk `
  | Select-String '\.so'
readelf -l <path/to.so> | Select-String LOAD
# Each Align must be 0x4000, never 0x1000.
```

If a `.so` fails the check, bump the offending dependency until it
publishes 16 KB-aligned binaries or drop it.

---

## 9. `VoltInviteScreen` — Skip button barely visible

### Symptom

On some devices the small "Skip" text link disappears against the
background artwork (dark hero image + 85 %-opacity white text = 1.5:1
contrast, fails WCAG). Users misread and burn the single Accept prompt.

### Fix

Skip must render as a **real gradient button** (same corner radius,
same height, muted variant of the Accept gradient). Never `alpha =
0.85` a text link. Concrete rules:

- Skip button height ≥ 48 dp.
- Same corner radius as Accept (`16.dp`).
- Same gradient family — either the identical Accept gold/sky, or a
  slightly darker variant with ≥ 4.5:1 contrast against the
  background.
- Position: directly below Accept, gap 12–18 dp, both centered
  horizontally within the same padding rail.

---

## 10. Button label baseline drift (labels look tilted)

### Symptom

The label inside a button sits visually higher or lower than the
geometric center of the pill.

### Fix

Compose `Text` inside a `Box(contentAlignment = Alignment.Center)`
with `style = TextStyle(lineHeight = fontSize)` — the default
`lineHeight` adds ~1.4× font-size vertical padding that reads as
"tilt" on short labels.

```kotlin
Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Text(
        label,
        style = TextStyle(
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 18.sp,      // ← removes baseline drift
            letterSpacing = 0.3.sp,
            color = Color.White,
        ),
    )
}
```

---

## 11. Landscape safe zone missed on camera / notch (~50 % of builds)

### Symptom

Rotated to landscape on a device with a punch-hole camera or notch on
the LONG edge, the WebView content is drawn UNDER the cutout —
content clipped, buttons unreachable. Portrait works because the
notch is on the short edge and `SafeDrawing` picks it up "for free".

### Fix — WebView only

For `VoltPaneActivity` wrap the WebView in a container that consumes
`WindowInsets.displayCutout` on **all sides**:

```kotlin
AndroidView(
    factory = { ctx -> webView },
    modifier = Modifier
        .fillMaxSize()
        .windowInsetsPadding(
            WindowInsets.displayCutout.only(
                WindowInsetsSides.Horizontal + WindowInsetsSides.Top
            )
        ),
)
```

Do NOT add the same inset to `VoltOfflineScreen` or
`VoltInviteScreen` — see §12.

---

## 12. Landscape safe zone MUST BE STRIPPED on Offline / Invite screens

### Symptom (opposite of §11)

When we apply the same cutout inset to the two artwork-based promo
screens, the button column shifts sideways in landscape because the
inset is asymmetric on notched devices. Buttons visually "float off
center" relative to the card that the artwork already drew.

### Fix

`VoltOfflineScreen` and `VoltInviteScreen`:

- **Do NOT call** `Modifier.safeDrawingPadding()`,
  `Modifier.systemBarsPadding()`, `Modifier.windowInsetsPadding(...)`.
- Rely on the background artwork's own margin — it was authored with
  the safe zone in mind.

### Vertical placement: anchor to the card, not to the screen

A percentage of `maxHeight` is **not** good enough, and this is the
second half of the same bug. `ContentScale.Crop` scales the artwork to
fill the shorter axis and shaves the longer one, so on any device whose
aspect ratio differs from the source bitmap the painted card slides up
or down while a percentage-based button stays put. The gap between them
grows with the aspect mismatch and reads as skew.

Derive the position instead. `VoltArtAnchor.kt` replays the same
scale-and-centre maths Crop uses and returns the card's bottom edge in
layout coordinates:

```kotlin
val band = card.projectInto(maxWidth, maxHeight)
Modifier.align(Alignment.TopCenter)
    .padding(top = band.offsetFor(blockHeight))
```

The horizontal axis needs no such treatment: the cards are centred in
all four bitmaps and Crop keeps a centred bitmap centred, so centring
against the container is exact.

### After replacing the artwork

The card bounds in `VoltArtMetrics` are measured off the shipped
bitmaps. **Re-measure them whenever the art changes** — stale numbers
put the buttons in the wrong place on every device at once, not just
the odd aspect ratio. Detect the card by masking its deep-blue body and
taking the largest connected component; a naive colour mask also catches
the coin badges and reports a card twice the real width.

QA on Pixel 6+, Samsung S23+, any punch-hole device: rotate to
landscape on the offline + invite screens; the buttons must sit
directly under the card the artwork paints and share its centre line.
Check a tablet too — that is where a percentage-based layout fails
most visibly.

---

## 13. Notification icon must be recognisably branded

### Symptom

Reviewers / users perceive the app as "generic" or "spammy" because
the small notification icon is a bell / dot / question mark
placeholder, or the launcher icon shrunk down. Alternatively the icon
disappears against the system tint because it has no defined
`fillColor`.

### Fix

`res/drawable/ic_volt_flame.xml` must:

- Be a `<vector>` with a 24×24 dp viewport.
- Have a domain-appropriate silhouette (flame / bolt / torch / gem)
  that reads at 24×24 dp on both light and dark system-tray
  backgrounds.
- Explicitly set `android:fillColor="#FFFFFF"` (white) on every
  `<path>`. Android tints the notification, so the *shape* matters,
  not the source color.
- Have a silhouette clearly different from the launcher icon.
- Keep a ~1 dp margin: fit the artwork into 22×22 inside the 24 dp
  viewport, otherwise the tray clips it.

Do NOT ship the launcher icon shrunk down.

### Converting source artwork (SVG) into the icon

A tray icon is an **alpha mask** — the colours are discarded. When the
source art is built from several stacked shapes (a typical flame is an
outer body plus an inner highlight), do not fill them all white: they
merge into one unreadable blob. Put the shapes in a single `pathData`
and set `android:fillType="evenOdd"` so the inner shape is subtracted
from the outer one and the internal detail survives the tint.

Judge the result at the size it is actually drawn — 24 dp is 48 px on
an xhdpi device and 63 px at 420 dpi. Rasterise the candidate at those
sizes before committing; detail that looks fine at 128 px often
disappears.

### Tint colour

Set an accent so the monochrome icon is not flat grey:

- `res/values/colors.xml` → one colour lifted from the brand artwork.
- `NotificationCompat.Builder.setColor(...)` for notifications the app
  builds itself in `VoltBeacon.render`.
- `com.google.firebase.messaging.default_notification_color` meta-data
  for the ones the system tray renders while the app is backgrounded.
  Miss this one and background pushes look different from foreground
  pushes.

---

## 14. Launcher icon crops / upscales / has white borders on splash

### Fix — three separate parts, all mandatory

**A. Foreground artwork must sit inside the 66 % safe zone.**

`res/mipmap-*/ic_launcher_foreground.png` should have its important
content inside the middle 66 % of the canvas (source PNG ≥ 512×512;
important pixels in the central 340×340 area).

**B. Adaptive background must be full-bleed.**

`res/mipmap-*/ic_launcher_background.png` should be either a solid
colour or a gradient PNG that fills the full canvas edge-to-edge with
no transparent margin.

**C. Kill the white flash on cold start.**

`res/values/themes.xml` `LaunchTheme` must not draw a white window.
The current theme uses `@color/olympus_night` — keep the
`android:windowBackground` set to a full-bleed drawable or a solid
dark brand colour on every fork.

Regenerate icons after every change, then clear the launcher cache on
the test device:

```powershell
adb shell pm clear com.google.android.apps.nexuslauncher
```

---

## 15. Push tap opens `ERR_CLEARTEXT_NOT_PERMITTED` / browser error

### Fix

**Preferred: promote the scheme.** `VoltUrlGuard.sanitize` rewrites
`http://` to `https://`, and every URL entering the flow — config
answer, push payload, deep link — passes through it, so one choke point
covers all of them. `VoltPaneActivity.shouldOverrideUrlLoading` does
the same for main-frame hops so a redirect mid-chain cannot land on the
error page either.

This works because partners put these links behind CDNs that serve both
schemes (the AppsFlyer short domains sit on Cloudflare). Verify before
assuming:

```powershell
curl.exe -sS -o NUL -D - "https://the-domain/path"
```

Promotion is preferable to whitelisting: it keeps
`cleartextTrafficPermitted="false"` app-wide, so nothing else in the
app silently gains permission to talk over plain HTTP.

Two things it does not fix. Sub-resources (`<img src="http://…">`)
are still blocked — that is `mixedContentMode` plus the network config,
and it is the page's problem, not ours. And a host that genuinely has no
TLS will now fail on the handshake instead; ask for an `https://` link.

**If cleartext must be allowed** (rare, ask the manager first):

`res/xml/network_security_config.xml`:

```xml
<network-security-config>
    <base-config cleartextTrafficPermitted="false" />
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">partner-domain.example</domain>
    </domain-config>
</network-security-config>
```

Reference it from `AndroidManifest.xml`:

```xml
<application android:networkSecurityConfig="@xml/network_security_config" ...>
```

**Secondary cause:** the URL scheme is `intent://` / `market://` and
was accidentally loaded into the WebView instead of being handed off
to the system. The hand-off lives in `VoltPaneActivity`'s
`shouldOverrideUrlLoading`, which must catch every non-http(s) scheme
(`intent://` goes through `Intent.parseUri(…, URI_INTENT_SCHEME)`).
Anything arriving from *outside* the page — a push payload, a deep
link, the config answer — is additionally screened by
`VoltUrlGuard.sanitize` before it can reach `loadUrl` at all.

---

## 16. `VoltOfflineScreen` — buttons oversized or misplaced

### Symptom

Retry button appears comically large in portrait, or shrunk to
almost nothing in landscape.

### Cause

`Modifier.fillMaxWidth()` inside a `Column` with no cap → the button
spans the full 1920 px in landscape.

### Fix

Two layouts gated on `LocalConfiguration.current.orientation`:

```kotlin
val landscape = LocalConfiguration.current.orientation ==
    Configuration.ORIENTATION_LANDSCAPE
val card = if (landscape) VoltArtMetrics.OfflineLandscape
           else VoltArtMetrics.OfflinePortrait

BoxWithConstraints(Modifier.fillMaxSize()) {
    val band = card.projectInto(maxWidth, maxHeight)
    Box(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = band.offsetFor(54.dp))
            .width(if (landscape) maxWidth * 0.30f else maxWidth * 0.55f)
            .height(54.dp),
    ) { RetryButton(...) }
}
```

Width is capped by orientation; the vertical offset comes from the card
rather than from `maxHeight` — see §12 for why the percentage version
drifts.

Verification:

- Portrait: Retry width ≈ 55 % of screen, height 54 dp.
- Landscape: Retry width ≈ 30 % of screen, does NOT cover the
  artwork's illustration.
- Button label perfectly centered.

---

## 17. WebView back gesture closes the whole activity

### Symptom

System back exits the app instead of stepping back one page.

### Fix

`VoltPaneActivity` overrides the back callback:

```kotlin
onBackPressedDispatcher.addCallback(this) {
    if (webView.canGoBack()) webView.goBack() else finish()
}
```

Do NOT override `onBackPressed()` — that API is deprecated on API 33+.

---

## 18. File chooser triggers permission dialog

### Symptom

Tapping `<input type="file">` shows an app-wide filesystem permission
dialog.

### Fix

Use `WebChromeClient.onShowFileChooser` → `ActivityResultContracts.
GetMultipleContents()` → return URIs to the WebView. No
`READ_EXTERNAL_STORAGE` permission required — content URIs are granted
per-URI by the system chooser.

The launcher lives in `VoltPaneActivity` rather than a helper class,
because `registerForActivityResult` has to run before the Activity
reaches `STARTED`.

One dependency trap comes with it: `play-services-basement` drags in
`androidx.fragment:1.1.0`, whose `FragmentActivity` predates the
Activity Result contracts, and `lintVital` fails the *release* build
with `InvalidFragmentVersionForActivityResult` even though nothing here
uses fragments. Pin a modern `androidx.fragment` explicitly in
`libs.versions.toml` and declare it in `app/build.gradle.kts`.

---

## TL;DR checklist before first release

- [ ] ProGuard `-keep` rules for AppsFlyer + Firebase +
      `VoltTokenService` present
- [ ] `compileSdk = 36`, `targetSdk = 36`, `minSdk = 24` in
      `app/build.gradle.kts`
- [ ] `kotlin.incremental=false` in `gradle.properties` (Windows /
      paths with spaces)
- [ ] `VoltLink.hasAnyAdapter()` whitelist includes `TRANSPORT_VPN`
- [ ] `VoltLink.isReachable()` probes a raw IP over TCP, never a
      hostname; connect timeout ≈ 7 s
- [ ] Connectivity-drop stream debounced ≥ 700 ms outside boot
- [ ] `onReceivedError` covers WebView with spinner immediately, and
      DNS/disconnect codes skip the redundant probe
- [ ] `versionCode` / `versionName` bumped from any previous release
- [ ] AGP 8.5.2+, NDK 27+ (16 KB page-size support)
- [ ] `VoltInviteScreen` Skip button rendered as a real gradient
      button (§9)
- [ ] All button labels use `lineHeight = fontSize` (§10)
- [ ] WebView handles cutout inset on all sides in landscape (§11)
- [ ] `VoltOfflineScreen` + `VoltInviteScreen` have NO safe-area
      inset — buttons stay centered on artwork (§12)
- [ ] Buttons anchored via `VoltArtMetrics`, and those bounds
      re-measured against the artwork currently shipping (§12)
- [ ] `ic_volt_flame.xml` is a branded vector, not the launcher
      icon (§13)
- [ ] Adaptive icon fits inside the 66 % safe zone; no white
      window-background flash on cold start (§14)
- [ ] Push URL is HTTPS-only, or cleartext whitelisted per domain
      (§15)
- [ ] `VoltOfflineScreen` buttons capped in width, orientation-aware
      positioning (§16)
- [ ] WebView back gesture returns one page inside, does not close the
      activity (§17)
- [ ] File chooser opens without a permission dialog (§18)
- [ ] `VoltBoot` is the manifest `android:name`, and it calls
      `FirebaseApp.initializeApp` + `VoltAttribution.prime()` before
      any Activity exists (§19)
- [ ] `AppsFlyerLib.start` (`ignite`) fires only after connectivity is
      confirmed, never in `Application.onCreate`
- [ ] No GCD v4.0 recheck anywhere — the endpoint is retired and
      answers `403`
- [ ] A config request that times out leaves the mode `pending`;
      only an actual backend "no" (`VoltReply.answered`) commits
      `native`
- [ ] `VoltBeacon.extractUrl` reads both `EXTRA_URL` and the raw
      `url` / `link` / `target_url` extras (§32)
- [ ] `onNewToken` persists the FCM token to the vault
- [ ] A `native` user never receives a tap URL and is never handed to
      the shell by a push
- [ ] `VoltRelay` delivers to a live shell instead of relaunching the
      router; nothing about a warm URL is persisted
- [ ] Screen artwork is genuinely WebP — check the magic bytes, not the
      file extension
