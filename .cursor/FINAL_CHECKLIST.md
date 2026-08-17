# FINAL CHECKLIST — Pre-Release Verification (Thunder Crest)

> Run through this **on a real Android device** before every
> submission. Every item is either a TZ requirement, a
> `.cursor/rules/…` invariant, or a fingerprint-safety rule. If any
> single line fails, the build is not shippable.

---

## Part A — Fingerprint safety (must differ from every prior project)

- [ ] `volt/cipher/VoltCodec.kt` — `SEED_PHRASE` changed to a fresh
      opaque ASCII token
- [ ] `volt/cipher/VoltCodec.kt` — `STREAM_LEN` changed to a new
      value in 16–48
- [ ] `volt/cipher/VoltSecrets.kt` — six byte arrays freshly
      generated **after** the seed change
- [ ] `volt/mark/VoltFacade.kt` — `PACKAGE_ID`, `MARKET_ID`,
      `DISPLAY_NAME` all unique to this project
- [ ] `volt/mark/VoltLegal.kt` — three URLs unique to this
      project (own domain or dedicated path)
- [ ] `app/build.gradle.kts` — `applicationId` + `namespace` match
      `PACKAGE_ID`; `versionCode` bumped from any previous store
      submission
- [ ] `gradle/libs.versions.toml` — at least two library minor
      versions differ from the previous project
- [ ] `app/src/main/kotlin/**/*.kt` — package declaration + folder
      path match `PACKAGE_ID` (renamed atomically across `:app`,
      `:core`, `:engine`, `:game`)
- [ ] `volt/pipe/VoltBeacon.kt` — `CHANNEL_ID` renamed;
      **matches** `com.google.firebase.messaging.default_notification_channel_id`
      in `AndroidManifest.xml`
- [ ] `AndroidManifest.xml` — `android:label` = `@string/app_name`
      which matches `DISPLAY_NAME`
- [ ] `AndroidManifest.xml` — OneLink `android:host` = fresh
      subdomain from AppsFlyer dashboard for this project
- [ ] `res/drawable/ic_volt_flame.xml` — new vector, distinct
      silhouette from the launcher icon
- [ ] `res/mipmap-*/ic_launcher*.png` — new adaptive icon (icon2 for
      the current build)
- [ ] `app/google-services.json` — real file present,
      `package_name` matches `applicationId`
- [ ] `keystore.properties` present + release keystore configured

---

## Part B — First-Launch UX Contract

Test this on a fresh device. Uninstall any prior build first.

- [ ] Add device GAID to AppsFlyer Test Devices before starting
- [ ] Tap the test OneLink (append `&advertising_id=<GAID>`)
- [ ] **Disable Wi-Fi and mobile data** before installing
- [ ] Install the APK / AAB
- [ ] Open the app — **frame ONE shows `VoltOfflineScreen`**, not a
      splash, not a black screen, not the game
- [ ] Portrait background is `volt_offline_portrait.webp`, landscape
      is `volt_offline_landscape.webp`
- [ ] Rotate the device on the offline screen — background swaps
      correctly, Retry button still positioned properly
- [ ] Retry button proportions:
      - Portrait: width ≈ 55 % of screen, height 54 dp, bottom margin
        ≈ 9 % of screen height
      - Landscape: width ≈ 30 % of screen, does NOT cover the
        artwork's illustration
- [ ] Retry button label perfectly centered, no baseline drift
- [ ] Enable Wi-Fi
- [ ] Tap **Retry** — app transitions into `VoltLoadingScreen`
- [ ] Loading bar starts at 0, grows monotonically, reaches 1.0 at
      the exact moment `VoltPaneActivity` takes over — no freeze at
      100 %, no jump backwards, no restart
- [ ] "Loading…" caption cycles dots on a stable rhythm
- [ ] `VoltPaneActivity` opens with the config URL — no game screen
      appears at any point (attribution is Non-organic)
- [ ] Kill the app, relaunch → `VoltPaneActivity` reopens directly
      (savedUrl path), no loading longer than ~2 s

---

## Part C — Full checklist

### 1. Test tracking link
- [ ] OneLink ends with `&advertising_id=<GAID/IDFA>` for the device
      being used
- [ ] Device GAID added to AppsFlyer Test Devices list

### 2. Deep link parameters
- [ ] OneLink created on the **dedicated** AppsFlyer account
- [ ] Deep-link install populates `deep_link_value`, `match_type`,
      `is_deferred` etc. in the config request body

### 3. Test offer resource
- [ ] `https://web.team-s.club/` loads correctly in the WebView
- [ ] All flows exercised on that resource work

### 4. App size
- [ ] Release AAB **< 100 MB**
- [ ] Ideally < 40 MB

### 5. Privacy policy
- [ ] Privacy Policy URL (`https://thunndercrest.com/privacy-policy.html`)
      loads a real, permanent page
- [ ] Settings screen "Privacy Policy" button opens it inside the
      shell (or the system browser as fallback)

### 6. API levels
- [ ] `app/build.gradle.kts` targetSdk = **36**, compileSdk = **36**,
      minSdk = **24**

### 7. Adaptive icon
- [ ] Launcher icon fills the mask — no empty borders, no clipping
- [ ] Foreground artwork sits inside the 66 % safe zone
- [ ] Adaptive background is a full-bleed PNG
- [ ] Cold-start splash shows NO white / grey rectangle around the icon

### 8. Loading screen
- [ ] Animated "Loading…" caption + animated progress bar
- [ ] Progress bar 0 → 100 % synced with real boot time
- [ ] Portrait AND landscape orientations both look correct
- [ ] Total boot time on normal Wi-Fi < 10 s

### 9. Push permission screen
- [ ] Shown BEFORE the WebView on the first entry into gray mode
- [ ] Portrait AND landscape backgrounds correct
- [ ] Accept → system dialog appears (API 33+)
- [ ] Skip → screen hidden for exactly 3 days
- [ ] System-level deny → screen never shown again
- [ ] **Skip button is a real gradient button** (§9 pitfalls)
- [ ] Button labels perfectly centered (§10 pitfalls)
- [ ] **NO safe-area / systemBars padding** on either orientation
      (§12 pitfalls) — buttons stay centered on the card artwork
      even on notched devices in landscape

### 10. WebView launch
- [ ] With `af_status: "Non-organic"` → WebView opens
- [ ] With `af_status: "Organic"` → native game opens; no gray
      content leaks through

### 11. User-Agent
- [ ] Contains Chrome major 149 and current WebKit fragment
- [ ] Contains a real Android model / build id sourced from `Build.*`
- [ ] Does NOT contain `wv/`, any package name or SDK identifier
- [ ] Same UA on OkHttp AND on the WebView
- [ ] **Slot game (Zeus theme)** — UA ends with
      a plain Chrome-on-Android string with **no** `appid/` or
      `appname/` suffix (see `volt_user_agent.md` §2)

### 12. WebView within Safe Area
- [ ] Portrait: WebView not covered by camera notch / punch-hole
- [ ] Landscape: WebView respects side cutout on BOTH long edges
      (rotate 180° and check again)
- [ ] Bottom of WebView reaches the bottom edge (no unnecessary
      inset)
- [ ] `VoltOfflineScreen` + `VoltInviteScreen`: buttons stay
      centered on the artwork card even on cutout devices in
      landscape (see §12 pitfalls)

### 13. Screen rotation
- [ ] Auto-rotate works on loading + offline + invite + WebView
- [ ] Game (`MainActivity`, `BattleActivity`) stays locked to
      `sensorLandscape` — this is a game design choice, unchanged

### 14. Back navigation (WebView)
- [ ] Android back gesture → WebView goes one page back
- [ ] `webView.canGoBack() == false` on the first page → back
      gesture does NOTHING (WebView is not closed)

### 15. Too-many-redirects recovery
- [ ] Redirect-loop → up to 3 automatic retries, then graceful load
      of the last known URL

### 16. JavaScript enabled
- [ ] `webView.settings.javaScriptEnabled = true`
- [ ] Payment gateways / OAuth pop-ups render normally

### 17. Cookies
- [ ] `CookieManager.setAcceptThirdPartyCookies(webView, true)`
- [ ] Login persists across page reloads within the WebView

### 18. Sessions
- [ ] Session cookies survive between navigation events
- [ ] Killing + reopening the app resumes the last URL with session
      intact

### 19. Inline autoplay video
- [ ] `webView.settings.mediaPlaybackRequiresUserGesture = false`
- [ ] Video on test resource plays inline without tap-to-start

### 20. Protected Media (DRM)
- [ ] `WebChromeClient.onPermissionRequest` grants
      `PROTECTED_MEDIA_ID`
- [ ] DRM-protected streams play without permission modals

### 21. Parameter forwarding
- [ ] Config request body contains **all seven** device-side fields:
      `af_id`, `bundle_id`, `os`, `store_id`, `locale`, `push_token`,
      `firebase_project_id` (unless FCM not initialised — then last
      two omitted, never null)
- [ ] Every field from AppsFlyer conversion data passed through
      verbatim
- [ ] `os` value is exactly `"Android"`
- [ ] `locale` in RFC 3066 format (`en`, `en_US`, `ru`, …)

### 22. File upload
- [ ] Site `<input type="file">` opens the native chooser
- [ ] Camera + gallery both offered
- [ ] No app-wide filesystem permission dialog appears
- [ ] Picked file uploads successfully

### 23. Keyboard does not cover inputs
- [ ] Focused email / password input scrolls above the keyboard
- [ ] No jitter, no double-jump

### 24. Push notifications
- [ ] Test push from Firebase Console shows on the device
- [ ] Notification uses the `ic_volt_flame` icon and the icon is
      a branded silhouette
- [ ] Icon silhouette is DIFFERENT from the launcher icon
- [ ] Icon is not clipped by the tray and its internal detail survives
      the tint (check a real device, not the 128 px source art)
- [ ] Accent tint identical for foreground and background pushes
      (`setColor` + `default_notification_color` meta-data)
- [ ] Notification shows an image (`BigPictureStyle`)
- [ ] Cold-start tap → app boots and opens the push URL in
      `VoltPaneActivity`
- [ ] Push URL is **HTTPS**. If HTTP was received, escalate
- [ ] Warm tap → live URL loaded, NOT persisted as `savedUrl`
- [ ] On the next launch after a cold-start push, the standard
      config URL is used
- [ ] All three states tested: app killed / backgrounded /
      foregrounded

### 25. Deep links inside WebView
- [ ] `tel:` / `mailto:` / `intent://` / `whatsapp://` / `tg://`
      links open the corresponding system app

### 26. Native game (offline invariant)
- [ ] With **Wi-Fi + mobile data OFF** on a fresh install where the
      config gate returned `{ok:false}`, the game
      (`MainActivity` → `MainActivity` → `BattleActivity`)
      launches and is fully playable
- [ ] No network calls are made from the game path (verified via
      logcat or a proxy)

---

## Part D — Backend contract (spot-check via logcat)

- [ ] Config request logged with `[VoltGate] request body: …`
- [ ] Body is a **flat** JSON object
- [ ] Response logged with `[VoltGate] response: 200 …`
- [ ] On `{ok:true}` → `savedUrl` + `expires` written to
      `VoltVault`
- [ ] On `{ok:false}` first launch → `VoltMode.native` written
      **once**; no subsequent config request fires this install
- [ ] `expires` respected on returning launch
- [ ] Token refresh (`VoltTokenService.onNewToken`) triggers a
      fresh config POST

---

## Part E — Build hygiene

- [ ] `./gradlew.bat --stop` before every clean
- [ ] Release build:
      `./gradlew.bat :app:bundleRelease`
- [ ] `mapping.txt` NOT committed to git
- [ ] `google-services.json` NOT committed
- [ ] `keystore.properties` NOT committed
- [ ] 16 KB page-size support verified on Android 15+ device
      (`adb shell getconf PAGE_SIZE` returns `16384`)

---

## Part F — Store submission

- [ ] Play Console listing description does NOT reference the
      WebView or the partner site
- [ ] Screenshots show the native game, never the WebView
- [ ] Privacy Policy URL live before submission
      (`https://thunndercrest.com/privacy-policy.html`)
- [ ] Support URL live (`https://thunndercrest.com/support.html`)
- [ ] Data Safety form declares only what the app actually collects
      (attribution ID, push token, device locale)
- [ ] `versionCode` bumped from the previous store version
