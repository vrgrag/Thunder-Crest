# Volt Gray Flow — Full Integration Guide (Kotlin Native)
## For AI agents evolving the gray-part Kotlin Android app in one shot

This document is the **single source of truth** for the gray-part flow
in Thunder Crest. Read top-to-bottom before writing any code; every
section is a hard invariant unless it says otherwise.

The project is a **native Android (Kotlin + Jetpack Compose + Jetpack Compose)**
port of the Flutter template at `D:\flutter_proj\gray_part_flow\`. Do
not import Flutter names, folder layouts or plugin idioms — the port
uses **fresh Kotlin class names, package structure and dependency
choices** so the compiled binary fingerprint differs from every
Flutter build of the same template.

---

## How to Use This Document (for AI agents)

You will typically receive one customer message containing a brief +
partial credentials. Your job is to produce a buildable native Android
project matching this guide. Do it in this order:

1. **Parse the brief against §"Inputs You Need Before Generating".** If
   anything is missing that cannot be sensibly defaulted, ask the user
   for it in a single batched question — do not scaffold with placeholders
   that will silently break later (endpoint, AppsFlyer key, Firebase
   config).
2. **Scaffold the file tree from §"Project Structure".** Rename services
   and folders per §"Code Mixing" — never ship two projects with the
   same class/folder names.
3. **Fill the config layer**
   (`volt/mark/VoltFacade.kt`, `volt/mark/VoltLegal.kt`,
   `volt/cipher/VoltCodec.kt`, `volt/cipher/VoltSecrets.kt`)
   using the XOR-encoded byte arrays produced by the packer routine.
4. **Implement gateways in this dependency order:**
   `VoltCodec` → `VoltUaForge` → `VoltVault` →
   `VoltLink` → `VoltAttribution` → `VoltBeacon` →
   `VoltGate`. Each gateway depends only on those listed before it.
5. **Wire the state machine from §"Volt Flow State Machine".** All
   routing lives in `VoltHubActivity`. Do not scatter it across
   the tree.
6. **Build the two custom Compose screens** —
   `VoltInviteScreen` and `VoltOfflineScreen` — matching the
   layouts in §"Screen Layout" below.
7. **Keep the game module (`:game`, `:engine`, `:core`) unchanged.**
   Do not add network calls, AppsFlyer references or Firebase symbols
   inside the game path — the game must remain fully offline.
8. **Configure Android:** `AndroidManifest.xml`,
   `app/build.gradle.kts`, `google-services.json`, notification icon,
   OneLink host, `POST_NOTIFICATIONS` permission.
9. **Apply every fix from `volt_pitfalls.md`** — proguard rules for
   AppsFlyer / Firebase, WebView safe area, VPN in the connectivity
   whitelist, etc.
10. **Verify with the checklist in §"Testing Guide"** and the TL;DR at
    the bottom of `volt_pitfalls.md` before declaring done.

Companion rule files, all inside `.cursor/rules/`:

- `volt_pitfalls.md` — battle-tested Android fixes (must apply)
- `volt_webview_safe_area.md` — WebView CSS-injection contract
- `volt_user_agent.md` — UA identity suffix (slot vs crash themes)

---

## Inputs You Need Before Generating

Refuse to scaffold without the items marked ★. Everything else has a safe
default, but ask if the brief looks incomplete.

### App identity
- ★ `packageId` — e.g. `com.thundercrest.thundercrestgame`
- ★ `displayName` — human-readable name shown in the launcher / store

### Backend
- ★ Config endpoint URL (from manager) — see §"Config Request Contract" §1

### AppsFlyer
- ★ Dev Key
- ★ OneLink host / subdomain (from AppsFlyer dashboard → App Settings → OneLink)

### Firebase
- ★ `google-services.json` (placed at `app/google-services.json`, git-ignored)
- ★ Firebase project number (or Project ID) — sent as `firebase_project_id`

### Legal / policy
- ★ Privacy policy URL (public, permanent)
- ★ Support URL

### Obfuscation
- ★ Codec seed — a short ASCII phrase (6–12 chars) unique to this project.
  Never reuse across projects. Re-run the packer routine after every seed
  change.

### Assets
- ★ Notification icon — vector drawable at
  `res/drawable/ic_volt_flame.xml` (flame or an equivalent
  domain-appropriate silhouette; MUST differ from the launcher icon)
- ★ Push-permission screen backgrounds — portrait + landscape
  (`res/drawable-nodpi/volt_invite_portrait.webp` /
  `volt_invite_landscape.webp`)
- ★ No-internet screen backgrounds — portrait + landscape
  (`volt_offline_portrait.webp` / `volt_offline_landscape.webp`)
- ★ Loading screen backgrounds — portrait + landscape
  (`splash_portrait.webp` / `splash_landscape.webp`)
- Adaptive launcher icon that fills the icon shape without clipping

### Theme flag
- Game theme category — slot vs crash (see `volt_user_agent.md`).
  Thunder Crest is currently classified as **slot** (Zeus theme).

---

## What Is the Volt (Gray) Flow?

A **gray flow** is a dual-mode native Android app:

- **Volt Gate mode (online, non-organic):** Shows a full-screen
  `WebView` with a URL received from a remote backend. The app acts as
  a thin shell.
- **Native Game mode (offline, organic):** Shows the Thunder Crest
  native game (`MainActivity` → `MainActivity` → `BattleActivity`).

The backend decides which mode each user sees based on their
**AppsFlyer attribution** — install source, campaign, ad network. Paid
(non-organic) users get the WebView. Organic users get the game.

**Why this architecture exists:** One APK serves two entirely different
experiences, determined at runtime. The game provides a legitimate
Store listing with real content. The WebView delivers partner content
to targeted users only.

---

## Why Stealth Matters

Store reviewers and automated scanners look for:
- Apps that are "just WebViews" with no real content
- Apps that behave differently during review vs. production
- Known affiliate/tracking domains in the binary
- Structural fingerprints shared across multiple submissions from the same developer

**What happens if caught:** App removal, developer account ban,
associated accounts banned. Because this developer ships 15+ apps, one
banned app cascades into all the sibling accounts — fingerprint
diversity is the *primary* defensive control, not an optimisation.

**Defense strategy:**
1. Real game = real review content (scanner and human reviewer see the game)
2. All sensitive strings XOR-encoded (API domain, AppsFlyer key, Firebase ID)
3. Attribution gate = only paid installs see the WebView (reviewer gets organic install = game)
4. Unique binary fingerprint per project (different codec seed, class names, folder structure, library versions)
5. Real device User-Agent on all requests (no `wv/` `Version/` WebView markers)

---

## Project Structure

The Kotlin package stays `com.thundercrest.game` so the game's existing
`R` and `BuildConfig` references keep resolving; only the
`applicationId` is `com.thundercrest.thundercrestgame`. Do not conflate
the two — the namespace is a compile-time detail, the applicationId is
the Play Store identity.

```
app/src/main/kotlin/com/thundercrest/game/
├── volt/                             — everything gray-flow
│   ├── VoltBoot.kt                ★ Application class. Firebase init + AppsFlyer prime().
│   ├── VoltHubActivity.kt         ★ LAUNCHER. Routing engine / state machine.
│   ├── VoltPaneActivity.kt           WebView shell (gray mode UI)
│   ├── VoltPermitActivity.kt         Push opt-in step — own Activity, not a Portal stage
│   ├── VoltQuietActivity.kt        No-connection step — own Activity, not a Portal stage
│   ├── ui/
│   │   ├── VoltLoadingScreen.kt      Loading composable (progress + dots)
│   │   ├── VoltInviteScreen.kt       Push opt-in promo composable
│   │   ├── VoltOfflineScreen.kt      No-connection composable
│   │   ├── VoltShellTheme.kt         Colors / typography for shell screens
│   │   └── util/Dots.kt                Animated "Loading . . ." caption
│   ├── config/
│   │   ├── VoltFacade.kt             packageId / marketId / displayName / timing knobs
│   │   └── VoltLegal.kt              Privacy / support URLs (plain), config endpoint (encoded)
│   ├── crypt/
│   │   ├── VoltCodec.kt              XOR keystream decoder (seed + stream length)
│   │   └── VoltSecrets.kt            Encoded byte arrays (endpoint, key, project id, UA fragments)
│   ├── gateway/
│   │   ├── VoltUaForge.kt            Device UA forger + OkHttp client
│   │   ├── VoltVault.kt              EncryptedSharedPreferences (URLs) + plain prefs (flags)
│   │   ├── VoltLink.kt               Adapter presence + TCP reachability probe
│   │   ├── VoltAttribution.kt        AppsFlyer bridge: prime() / ignite() / retrace()
│   │   ├── VoltBeacon.kt             Firebase Messaging + notification channel
│   │   ├── VoltRelay.kt              In-process warm push hand-off to a live shell
│   │   └── VoltGate.kt               POST to config endpoint + cache
│   ├── model/
│   │   ├── VoltMode.kt               pending / online / native (persisted)
│   │   └── VoltReply.kt              { allowed, link, ttl, note, answered }
│   └── util/
│       └── VoltUrlGuard.kt           http/https + non-blank host gate for external URLs

app/src/main/kotlin/com/thundercrest/game/{ui,util,…}
    → unchanged native game (MainActivity, MainActivity, BattleActivity, …)

app/src/main/res/
├── drawable/
│   └── ic_volt_flame.xml            ★ Vector notification icon (flame)
├── drawable-nodpi/
│   ├── splash_portrait.webp            Loading backgrounds (existing)
│   ├── splash_landscape.webp
│   ├── volt_offline_portrait.webp    ★ No-wifi backgrounds
│   ├── volt_offline_landscape.webp
│   ├── volt_invite_portrait.webp     ★ Push-invite backgrounds
│   └── volt_invite_landscape.webp
├── mipmap-*/
│   └── ic_launcher*.png                ★ Adaptive launcher icons
└── xml/
    └── network_security_config.xml     Cleartext whitelist (per-domain only)

app/google-services.json                ★ Firebase config (git-ignored)
app/build.gradle.kts                    applicationId, minSdk=24, targetSdk=36
gradle/libs.versions.toml               Central dependency versions
```

Two structural rules that earlier revisions got wrong:

- **Screen artwork must really be WebP.** The four gray backgrounds
  arrived as JPEG bytes carrying a `.png` extension; `aapt2` reads magic
  bytes, not names, and fails the release resource step. Convert them,
  never just rename.
- **`VoltUpload.kt` and `VoltDeepLinks.kt` no longer exist.** The
  file-chooser bridge and the `intent://` hand-off live inside
  `VoltPaneActivity`, because the `ActivityResultLauncher` they need
  must be registered on the Activity before `onStart` anyway.

---

## Setup Checklist (new fork of Thunder Crest)

### Step 1 — App identity

Edit `volt/mark/VoltFacade.kt`:
```kotlin
const val PACKAGE_ID   = "com.yourcompany.yourapp"
const val MARKET_ID    = "com.yourcompany.yourapp"  // same as PACKAGE_ID on Android
const val DISPLAY_NAME = "Your App Name"
```

Edit `app/build.gradle.kts`:
```kotlin
namespace = "com.yourcompany.yourapp"
defaultConfig {
    applicationId = "com.yourcompany.yourapp"
}
```

Move the Kotlin source tree:
```
app/src/main/kotlin/com/yourcompany/yourapp/
```
Update the `package` declaration on every `.kt` file. Do the same in
`:core`, `:engine`, `:game`.

Update `res/values/strings.xml` → `<string name="app_name">Your App Name</string>`.

### Step 2 — Encode secrets

Edit `volt/mark/VoltLegal.kt` and the packer stub at the bottom
of `volt/cipher/VoltSecrets.kt` — fill in the plaintext for:
- Config endpoint URL
- AppsFlyer Dev Key
- Firebase project number
- Chrome / WebKit version fragments for User-Agent (Chrome major 149)

Run the packer with a plain JVM `kotlin` script (see the block
"Packer script" at the bottom of this file) or just re-derive the
arrays by hand — the XOR is symmetric, so any correct implementation
works.

Paste the printed arrays into `volt/cipher/VoltSecrets.kt` — six
`intArrayOf(...)` entries.

**⚠️ Never commit the plaintext values.** They belong in a scratch
file that stays local.

### Step 3 — Change codec seed

Edit `volt/cipher/VoltCodec.kt` — change `SEED_PHRASE` and
`STREAM_LEN` to fresh unique values. Re-run the packer after every
seed change.

### Step 4 — Firebase

Add `app/google-services.json` (from Firebase Console → Project
Settings → Android app). `package_name` in the file must match
`applicationId` exactly. Ensure `google-services.json` is in
`.gitignore`.

### Step 5 — AppsFlyer OneLink

In `app/src/main/AndroidManifest.xml`, update the OneLink host inside
the `VoltHubActivity` intent-filter:
```xml
<data android:scheme="https" android:host="yourapp.onelink.me" />
```
Get the OneLink subdomain from AppsFlyer dashboard → App Settings → OneLink.

### Step 6 — Notification icon

Replace `res/drawable/ic_volt_flame.xml` with a vector drawable
matching the domain silhouette (flame, star, gem, torch, …). Rules:

- Vector Drawable XML (`<vector>`), 24×24 dp viewport, artwork fitted
  into 22×22 so nothing is clipped
- Silhouette must be visually different from the launcher icon
- Solid colour fills (single `<path android:fillColor="#FFFFFF"/>`)
  work best because Android tints monochrome notifications
- Multi-shape artwork goes into one `pathData` with
  `android:fillType="evenOdd"` — see `volt_pitfalls.md` §13
- Pick the accent colour in `res/values/colors.xml` and wire it both in
  `VoltBeacon` (`setColor`) and in the manifest meta-data
  `com.google.firebase.messaging.default_notification_color`

### Step 7 — Firebase service account (push system)

In Firebase Console → Project Settings → Users and permissions →
Advanced permission settings (opens GCP), the shared push-testing
service account must have Owner role.

Without this, the push notification system cannot send messages.

### Step 8 — Legal URLs

Edit `volt/mark/VoltLegal.kt`:
```kotlin
const val PRIVACY_URL = "https://your-privacy-policy.com"
const val SUPPORT_URL = "https://your-support.com"
const val HOME_URL    = "https://your-site.com"
```

### Step 9 — Code Mixing (mandatory)

See §"Code Mixing" below. Every fork must have a unique structure.

### Step 10 — Build & verify

```powershell
./gradlew.bat --stop
./gradlew.bat clean
./gradlew.bat :app:assembleDebug
./gradlew.bat :app:bundleRelease
```

---

## Volt Flow State Machine

Everything below runs after `VoltBoot.onCreate` has already called
`FirebaseApp.initializeApp` and `VoltAttribution.prime()`. Offline and
push-invite are **separate Activities**, not stages inside the Portal —
that is what makes a return trip from either of them a clean relaunch of
the router rather than a resurrected half-state.

```
VoltMode.pending (FIRST LAUNCH)
  ├── Push tap with a usable URL → stash it, keep routing (never short-circuit)
  ├── !VoltLink.hasAnyAdapter() → VoltQuietActivity(resumeUrl = null)
  │     └── Retry once reachable → VoltHubActivity (fresh run)
  └── Reachable
        ├── VoltAttribution.ignite()          ← first network touch, never earlier
        ├── await [installData (attribution budget), deepLink (short budget)]
        ├── buildRequestBody(locale, pushToken)  ← token via withTimeoutOrNull, never blocking
        ├── VoltGate.query(body)
        ├── reply.allowed && url ok  → writeMode(online) → invite gate → VoltPaneActivity
        ├── reply.answered && !allowed → writeMode(native) → MainActivity   (backend said no)
        └── !reply.answered            → stay pending → MainActivity this session only
                                          (server unreachable — re-ask next launch)

VoltMode.online (RETURNING, WAS WEBVIEW)
  ├── Warm push while shell is alive → VoltRelay.offer(url), Portal not involved
  ├── !reachable && cachedLink → VoltQuietActivity(resumeUrl = cachedLink)
  ├── Push URL stashed in vault → VoltPaneActivity(pushUrl)   ← highest priority
  ├── Ask the backend first (ignite + query), cache is the fallback, not the shortcut
  ├── reply ok → VoltPaneActivity(new url)
  └── reply failed + cachedLink → VoltPaneActivity(cachedLink)
       └── no cachedLink → VoltQuietActivity

VoltMode.native (RETURNING, WAS GAME)
  └── MainActivity — always, no network, no attribution, no push routing
      → MainActivity → BattleActivity
```

**Key insight 1 — the backend decides conversion, not the client.**
Once the mode is `native`, the WebView is never shown again, even if the
device later gets a perfect connection.

**Key insight 2 — "no" and "no answer" are different facts.**
`VoltReply.answered` exists solely to keep a dropped connection from
being recorded as a backend rejection. A `silence()` reply sends the user
to the game *for this session* and leaves the mode `pending`, so the next
launch asks again. Committing `native` on a timeout permanently burns a
paid install, which is the most expensive bug this flow can have.

**Key insight 3 — NATIVE mode is fully isolated.** No push URL, no deep
link and no config answer may pull a `native` user into the shell.
`VoltBeacon` checks the mode before it even builds a notification.

---

## First-Launch UX Contract (OneLink + Offline Install)

This is a hard invariant for the *very first launch* after installing
via a OneLink (paid attribution present, but the device may have no
connectivity yet). Failing any bullet below is a QA-blocking bug — do
not skip.

### Canonical scenario (must be reproducible on every build)

1. User taps the OneLink on the target device.
2. **Wi-Fi / mobile data is turned OFF** before the install completes.
3. User installs and opens the app.
4. → App must show `VoltOfflineScreen` **IMMEDIATELY**, on the very
   first frame. No splash flicker, no default Android black window
   — the No-Wi-Fi screen is the first pixel the user sees.
5. User turns Wi-Fi / mobile data ON.
6. User taps **Retry** / **Reconnect** on `VoltOfflineScreen`.
7. → App transitions into `VoltLoadingScreen` and then routes to
   `VoltPaneActivity` (WebView with the config `url`).
   - Loading screen must complete its animation (see §"Screen Layout:
     VoltLoadingScreen" §Timing contract).
   - No return to `VoltOfflineScreen` mid-way, no game screen shown
     at any point in this scenario (attribution is Non-organic →
     WebView).

### Implementation rules

- **Connectivity gate runs before AppsFlyer init.** Check the Android
  `ConnectivityManager` state *before* `AppsFlyerLib.init()`. If
  offline, replace the initial `VoltLoadingScreen` composable
  content with `VoltOfflineScreen` on the very first frame. Do not
  `await` the SDK first — that call can hang for tens of seconds
  without connectivity.
- **No pre-check splash flicker.** The route decision must be resolved
  in the first Compose frame after `setContent`. Use a synchronous
  `VoltMode.pending` bootstrap that inspects connectivity *before*
  showing the loading tree. If offline, show `VoltOfflineScreen`
  directly as the initial composition.
- **Retry restarts the full portal pipeline.** Tapping Retry must
  finish `VoltHubActivity` and re-`start` it, which then re-runs
  the connectivity check → AppsFlyer init → attribution wait →
  `VoltGate.query()` → route. The pipeline is idempotent by design.
- **`VoltMode` stays `pending` throughout.** Because we never
  received a config response, the mode has not been committed to
  `online` or `native` yet — do NOT persist `native` merely because
  of the initial network failure.
- **No game fallback on offline boot.** Do not preload game assets on
  the offline path — the moment internet returns and the fetch
  succeeds, we go to `VoltPaneActivity`, not the game.

---

## Config Request Contract (AUTHORITATIVE)

Every rule below is a hard invariant. Deviating from any of them breaks
routing, tests, or the push subsystem. When editing generate/change
code, treat this section as the API spec and mirror it exactly in
`VoltGate.kt` and `VoltAttribution.kt`.

### 1. Endpoint

| Field | Value |
|---|---|
| URL | Provided by the project manager, stored XOR-encoded in `VoltSecrets.CONFIG_ENDPOINT_BYTES` |
| Method | `POST` |
| Headers | `Content-Type: application/json`, `Accept: application/json` |
| Timeout | 15 seconds |

Never hard-code the endpoint at a call site — always deref through
`VoltLegal.configEndpoint()` so a single edit propagates everywhere.

For Thunder Crest specifically the plaintext endpoint is
`https://thunndercrest.com/config.php` (encode via `VoltCodec`
before pasting into `VoltSecrets`).

### 2. Request body — merge order

The body is a single flat JSON object built by merging three sources.
First-write-wins on key collision (`putIfAbsent`), then device-side
fields are added last and **overwrite** duplicates:

| Priority | Source | Overwrite rule |
|---|---|---|
| 1 | `onConversionDataSuccess` (AppsFlyer install attribution) | writes all keys as-is |
| 2 | `onAppOpenAttribution` (returning-user attribution) | putIfAbsent |
| 3 | `onDeepLinking` (UDL data — see §4) | putIfAbsent |
| 4 | Device-side fields (see §3) | overwrites |

**Hard rules:**

- ❌ **NEVER** filter, rename, drop, or mutate any key/value received
  from AppsFlyer. The list of parameters varies per install source —
  pass it through unchanged even if a key looks unfamiliar.
- ❌ Do not JSON-nest. The body must be one flat object.
- ✅ In debug builds, log the final body via `android.util.Log` for QA.

### 3. Device-side fields (added last, always overwrite)

| Key | Type | Source | Notes |
|---|---|---|---|
| `af_id` | string | `AppsFlyerLib.getInstance().getAppsFlyerUID(ctx)` | |
| `bundle_id` | string | `VoltFacade.PACKAGE_ID` | e.g. `com.thundercrest.thundercrestgame` |
| `os` | string | `"Android"` | Case-sensitive |
| `store_id` | string | Same as `bundle_id` on Android | |
| `locale` | string | Device primary locale in **RFC 3066** | `Locale.getDefault().toLanguageTag().replace('-', '_')` — e.g. `en_US`, `ru` |
| `push_token` | string | `FirebaseMessaging.getInstance().token.await()` | **Omit key entirely if FCM not initialised** |
| `firebase_project_id` | string | Firebase project number | **Omit key entirely if FCM not initialised** |

Never send `push_token: ""` or `push_token: null` — omit the key.

### 4. UDL / Deep-link fields (from `onDeepLinking`)

If the SDK delivers deep-link data, merge every field it returns
(`putIfAbsent`, i.e. do not overwrite keys already provided by
conversion data). The SDK's field list is authoritative — pass through
whatever it delivers.

### 5. Firebase Messaging not initialised

If FCM fails to initialise (missing `google-services.json`, Play
Services absent, permission denied, first-launch race), the request
must be sent **without both** `push_token` **and**
`firebase_project_id`. Skip the keys entirely; do not substitute empty
strings or `null`.

Recovery path — no manual retry, driven by SDK events:

1. Register a `FirebaseMessagingService` (`VoltTokenService`) whose
   `onNewToken` fires when a token arrives / rotates.
2. When the callback fires, **immediately** re-POST the config request
   with the full body including the new token.

Never poll `token` in a loop — the refresh callback is the contract.

### 6. Example request body

```json
{
  "adset": "s1s3",
  "af_adset": "mm3",
  "af_status": "Non-organic",
  "campaign": "OlympusSurge_US_Facebook_2026",
  "campaign_id": "6068535534218",
  "media_source": "Facebook Ads",
  "is_first_launch": true,
  "af_id": "1688042316289-7152592750959506765",
  "bundle_id": "com.thundercrest.thundercrestgame",
  "os": "Android",
  "store_id": "com.thundercrest.thundercrestgame",
  "locale": "en_US",
  "push_token": "dl28EJC...",
  "firebase_project_id": "8934278530"
}
```

### 7. Response — success (HTTP 200)

```json
{ "ok": true, "url": "https://link.example/...", "expires": 1689002181 }
```

- `url` — load into the WebView **unchanged**. No query-string
  rewriting, no domain substitution, no scheme upgrades.
- `expires` — Unix timestamp in seconds. Persist alongside `url`.
- On subsequent launches, before making any network call, compare
  `System.currentTimeMillis() / 1000` against `expires`. If not
  expired → load `savedUrl` immediately.

### 8. Response — failure

Any of:

- HTTP status ≠ 200
- `ok: false` in a 200 body
- Socket / DNS / timeout error

is a **NEGATIVE** answer.

### 8a. What this backend actually routes on (measured)

`https://thunndercrest.com/config.php` was probed directly with hand-built
bodies. It keys off **one field, `af_status`**, and ignores everything
else when deciding:

| Body | Answer |
|---|---|
| `af_status: "Non-organic"` + full campaign params | `200 {"ok":true,"url":"https://web.team-s.club?...","expires":…}` |
| `af_status: "Non-organic"`, nothing but `af_id` / `bundle_id` / `os` / `store_id` / `locale` | `200` + URL |
| `af_status: "Organic"` + full campaign params | `404 {"ok":false,"message":"No data"}` |
| Campaign params, `af_status` absent | `404 {"ok":false,"message":"No data"}` |
| `{}` | `404 {"ok":false,"message":"No data"}` |

The other fields are not gates — they are payload. The backend echoes
them into the destination URL as `sub_id_*` / `extra_param_*`
(`af_sub1..5`, `campaign`, `campaign_id`, `media_source`, `agency`,
`af_id`, `bundle_id`, `push_token`, `deep_link_value`, `deep_link_sub1`),
so a thin body still yields a URL, just one with empty sub-ids.

Two consequences worth internalising before debugging this flow again:

- **A 404 here is a normal verdict, not an outage.** `VoltGate`
  already treats any non-2xx as `verdict()` rather than `silence()`,
  which is what makes the native decision stick.
- **A directly installed APK can never reach the shell.** A direct
  install is genuinely organic, AppsFlyer says so truthfully, and the
  backend refuses. This is the flow working. Use
  `volt.forceStatus=Non-organic` (§QA switches) or a real attributed
  install.

### 9. Behavior contract on failure

**First install — never fetched a successful `url` before.**

The verdict is permanent, so it must be a real one. `VoltMode.native`
is written only when **both** of these hold:

- the endpoint genuinely answered (`VoltReply.answered == true`, i.e.
  an HTTP response was parsed — not a timeout, DNS failure or socket
  reset), and
- there was attribution behind the question
  (`tracker.hasAttributionData()`).

Anything else — endpoint unreachable, or an answer built on an empty
conversion map — opens `MainActivity` **for this launch only** and
leaves the mode `pending`, so the next launch asks again. Writing
`native` on a timeout permanently burns a paid install and cannot be
undone short of a reinstall; that asymmetry is why the check is
deliberately conservative in the other direction.

Once `native` really is committed, no further config request is ever
sent, on this launch or any future one.

**Returning launch — mode is `online` and a `url` is cached.**

1. A pending push URL in the vault wins over everything, including a
   perfectly fresh cache. See §Push routing.
2. Otherwise **ask the backend on every return**, not only after
   `expires` has passed. A campaign destination can move at any time,
   and a link the server sent without an expiry would otherwise be
   pinned for the life of the install. The attribution budget on this
   path is short precisely because the ask must not delay a returning
   user.
3. Success ⇒ overwrite `savedUrl` + `expires`, load the new `url`.
4. Failure ⇒ **still load `savedUrl`**. Never fall back to the game,
   never show a blank state. With no `savedUrl` at all, go to
   `VoltQuietActivity`.

The cache is the fallback for when the ask does not land — not a way to
skip it.

---

## AppsFlyer: Organic False-Positive Fix

**Problem:** a paid install can read as organic — the SDK reports
`af_status: "Organic"`, or fires an empty conversion map, and the config
POST goes out describing a paid user as an organic one. The backend then
correctly answers "no URL" and the install is burned.

**Do not use the GCD v4.0 recheck.** Older revisions of this guide told
you to re-query
`https://gcdsdk.appsflyer.com/install_data/v4.0/{bundleId}` after a 5s
delay. That endpoint is retired: it now answers `403` for new dev keys,
so the recheck silently returns `null`, the delay is spent for nothing
and the original organic reading is used anyway. The code path has been
removed along with `ORGANIC_RECHECK_DELAY` and `VoltSecrets.gcdUrl`.
Do not reintroduce it.

**What actually prevents the false positive** is getting the SDK's own
timing right, in three parts:

1. **`prime()` in `VoltBoot.onCreate`, before any Activity exists.**
   `AppsFlyerLib.init` registers `ActivityLifecycleCallbacks` and uses
   them to notice the app came to the foreground. Calling `init` when an
   Activity is already on screen means the SDK misses this launch and
   queues the install until the next one — attribution reads organic for
   the entire first session. This single ordering mistake causes most
   "organic" paid installs. `prime()` touches no network.

2. **`ignite(activity)` only once a connection is confirmed.** This is
   the call that actually speaks to AppsFlyer (`AppsFlyerLib.start`).
   Firing it while the radio is off produces an immediate empty callback
   that would otherwise become the answer the config POST is built from.

3. **`retrace(activity)` when the link comes back.** If the first
   reading settled empty and the device is now reachable, the deferred
   is reset and `start` is called again. Unlike the GCD recheck this
   re-asks the live SDK, not a dead REST endpoint.

Both `prime()` and `ignite()` are idempotent (`AtomicBoolean` guards),
so the router can call them defensively without double-initialising.

---

## Push Notifications: Complete Implementation

### Permission flow

1. The router starts `VoltPermitActivity` **before**
   `VoltPaneActivity`, as its own Activity — not a stage inside the
   Portal. Both exits from it (`Accept` and `Skip`) lead to the shell.
2. It is shown only if the permission is not granted and the OS dialog
   can still lead somewhere.
3. `Skip` arms a cooldown (`armInviteCooldown`, `INVITE_COOLDOWN_SECONDS`
   — 3 days) and proceeds. It must **not** be recorded as a refusal.
4. `Accept` launches the Android 13+ `POST_NOTIFICATIONS` dialog and
   calls `markOsAsked()` — the flag is set only when the *system* dialog
   actually opened.
5. A denial at the system dialog sets `markPushBlockedByOs()` and the
   screen is never shown again.

The `wasOsAsked` / `armInviteCooldown` split is the whole point:
conflating "the user skipped our promo" with "the user refused the OS
prompt" turns a 3-day snooze into a permanent one.

### `VoltVault.shouldOfferInvite(host)` logic

It takes an `Activity` because "asked and refused for good" is only
readable through `shouldShowRequestPermissionRationale`. Asking the OS
directly — not just the stored flags — also covers the user who turned
notifications off in system settings, entirely outside the app.

```kotlin
fun shouldOfferInvite(host: Activity): Boolean {
    if (isPushAllowed() || isPushBlockedByOs()) return false
    if (osGranted()) { markPushAllowed(true); return false }
    if (osRefusedForGood(host)) { markPushBlockedByOs(); return false }
    return nowSeconds() >= inviteCooldownUntil()
}
```

### Push URL routing (CRITICAL DISTINCTION)

| Scenario | Path | Action |
|---|---|---|
| Shell alive, message arrives | `onMessageReceived` → `VoltRelay.offer(url)` | Handed to the live WebView; **nothing persisted** |
| Shell stopped but not destroyed | `VoltRelay` parks the URL | Drained on the shell's next `onStart`; **nothing persisted** |
| App killed, message arrives | `onMessageReceived` → `vault.stashPushLink(url)` | Saved for **exactly one** cold start |
| App killed, tap opens launcher | `VoltBeacon.extractUrl(intent)` in `VoltHubActivity` | Stashed, then routing continues normally |
| Mode is `native` | Notification is shown with **no** tap URL | URL neither handed over nor saved |

A stashed link is consumed once and cleared. It must never survive into
a second launch — push URLs are one-time, and on the next start the
config answer is the source of truth.

**Pitfall §32 — read both extra shapes.** A data-only message reaches
`VoltTokenService`, which builds the tap intent itself and sets
`EXTRA_URL`. A message carrying a `notification` block is drawn by the
Firebase SDK directly whenever the app is not in the foreground; that
path never runs our service, and the tap opens the launcher with the raw
`data` payload as plain string extras (`url` / `link` / `target_url`).
Reading only `EXTRA_URL` is exactly how a pushed link gets silently
dropped and the shell reopens on the previously saved page. Hence:

```kotlin
val own = intent.getStringExtra(EXTRA_URL)
val raw = RAW_URL_KEYS.firstNotNullOfOrNull { intent.getStringExtra(it) }
return VoltUrlGuard.sanitize(own ?: raw)
```

**Every push URL goes through `VoltUrlGuard` first.** A payload that
is not `http`/`https` with a non-blank host is dropped and the
notification is rendered as plain text. Without this the tap lands on
`ERR_UNKNOWN_URL_SCHEME` in a blank WebView the user cannot leave.

**Never short-circuit routing on a push tap in `pending` mode.** Stash
the URL and let the state machine run — a first launch still has to
resolve attribution and ask the backend before it is allowed to decide
this user belongs in the shell at all.

**The token is persisted in `onNewToken`.** Otherwise a rotation that
happens while the next launch is offline is lost, and the config POST
goes out with no `push_token` — the backend then has no way to reach
that install again.

### Android 13+ permission (API 33+)

Add to `AndroidManifest.xml`:
```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
```

Request via `ActivityResultContracts.RequestPermission` on the "Accept"
button tap.

### Notification channel

Create in `VoltBeacon.ensureChannel()`:
```kotlin
val channel = NotificationChannel(
    CHANNEL_ID,          // must match manifest meta-data
    CHANNEL_NAME,
    NotificationManager.IMPORTANCE_HIGH,
)
```

Manifest meta-data:
```xml
<meta-data
    android:name="com.google.firebase.messaging.default_notification_channel_id"
    android:value="volt_surge_alerts" />
<meta-data
    android:name="com.google.firebase.messaging.default_notification_icon"
    android:resource="@drawable/ic_volt_flame" />
<meta-data
    android:name="com.google.firebase.messaging.default_notification_color"
    android:resource="@color/volt_beacon_flame" />
```

---

## Screen Layout: VoltLoadingScreen

The splash / loading screen is the **only** Compose screen shown by
`VoltHubActivity` while it resolves attribution and queries the
gate. It must feel like the app is doing real work — a static logo is
not enough. Two elements are mandatory:

1. An animated **"Loading"** caption with cycling trailing dots.
2. An animated **progress bar** that starts at `0` and reaches the far
   right edge at the exact moment routing decides the next screen.

Both must adapt to portrait and landscape via the background.

### Portrait layout (bottom-anchored stack)

- Background image `volt_loading_portrait.webp` /
  `splash_portrait.webp`, `Modifier.fillMaxSize()`, `ContentScale.Crop`.
- "Loading . . ." caption — centered horizontally, offset from bottom
  ≈ `maxHeight * 0.22`, 22 sp semi-bold white, letterSpacing 1.5, alpha 0.9.
- Progress bar — `Modifier.padding(horizontal = 32.dp).height(18.dp)`,
  bottom offset ≈ `maxHeight * 0.14`; track dark 33 %, fill sky
  gradient (`#63BEF8` → `#2E78C9`).

### Landscape layout

- Background image `volt_loading_landscape.webp` /
  `splash_landscape.webp`.
- "Loading . . ." caption — centered, bottom offset ≈ `maxHeight * 0.20`.
- Progress bar — width ≈ `maxWidth * 0.60`, centered horizontally,
  bottom offset ≈ `maxHeight * 0.10`.

### "Loading . . ." caption animation

- Base text: the literal word `Loading`.
- Trailing dots cycle through: `""`, `"."`, `". ."`, `". . ."`, then wrap.
- Each step lasts **300 ms** (full cycle 1200 ms). Implement via
  `LaunchedEffect(Unit) { while (true) { delay(300) ; dots = (dots + 1) % 4 } }`.

### Progress bar animation — timing contract

- Start `progress = 0.05f` at first Composition of `VoltLoadingScreen`.
- After `VoltAttribution.ignite()` returns → 0.4f.
- After attribution `await` returns → 0.7f.
- After `VoltGate.query()` returns → 1.0f (animated 250 ms `tween`),
  then start the next `Activity`.
- Never let the bar reach `1.0` early and freeze — that reads as a hang.
- Never jump backwards.

### Behavior invariants

- `VoltLoadingScreen` is non-interactive — do not attach any
  clickable modifiers.
- System back is intercepted with `BackHandler(true) { /* nothing */ }`.
- Landscape rotation mid-splash must not restart the progress —
  hoist `progress` into the caller's `remember`.

---

## Screen Layout: VoltInviteScreen (push permission)

### Portrait layout

Full-screen background image `volt_invite_portrait.webp`
(`ContentScale.Crop`); Accept + Skip buttons stacked at the bottom
inside a `Column` with `horizontalAlignment = Alignment.CenterHorizontally`.

**⚠️ NO `SafeArea` / `WindowInsets` PADDING on portrait either.** The
artwork already reserves space at the bottom; adding an inset shifts
the buttons out of the reserved area.

Key measurements (current implementation):
- Vertical offset comes from `VoltArtMetrics.InvitePortrait` via
  `band.offsetFor(...)`, which centres the pair in the space under the
  painted card. Never a percentage of `maxHeight` — see
  `volt_pitfalls.md` §12.
- Accept button width = `maxWidth * 0.7` (portrait), `maxWidth * 0.26`
  per pill (landscape).
- Skip is a gradient button — never a subdued text link (see
  `volt_pitfalls.md` §9).
- Corner radius = 16 dp; button heights 56 / 50 dp (portrait), 52 dp
  (landscape); font 18 sp.

### Landscape layout — critical

- **Do NOT apply `Modifier.systemBarsPadding()` or
  `WindowInsets.safeDrawing`.** Landscape safe-zone insets on
  cutout devices push the button column off the geometric center of
  the artwork's card, so the buttons look "tilted" relative to the
  card frame.
- The two pills go **side by side** in a `Row`, not stacked: stacked
  pills plus their gap eat a third of the landscape height and crowd
  the card above them.
- Centred horizontally against the container, which is exact because
  Crop keeps the artwork's centred card centred.

### Button animation details

- Accept: `animateFloatAsState` press-scale 1.0 → 0.95, 90 ms.
- Skip:   same scale animation, different gradient (muted variant).
- Both:   `pointerInput`-based gesture tracking, not `Modifier.clickable`
  alone, so press feedback matches the guide.

---

## Screen Layout: VoltOfflineScreen (no internet)

### Portrait layout

Full-screen background image `volt_offline_portrait.webp`,
`ContentScale.Crop`. A single **Retry** button overlaid near the
bottom.

**⚠️ NO `SafeArea` / `WindowInsets` PADDING** — same reason as
`VoltInviteScreen`.

- Retry button width ≈ `maxWidth * 0.55`, centered; vertical offset from
  `VoltArtMetrics.OfflinePortrait` via `band.offsetFor(...)`, not a
  percentage of `maxHeight` (`volt_pitfalls.md` §12).
- Height 54 dp, corner radius 16 dp, same sky gradient as the invite
  Accept button.
- During retry (200–600 ms) swap the label for a `CircularProgressIndicator`
  in the same box — never move / hide the button entirely, that
  causes layout jump.

### Landscape layout

- Background swap to `volt_offline_landscape.webp`, and the card
  metrics swap to `VoltArtMetrics.OfflineLandscape` with them — the
  two bitmaps put the card at different heights.
- Button width ≈ `maxWidth * 0.3`, still centered horizontally, no
  safe-area inset.

### Behavior

- The offline screen must be shown **immediately** on a connectivity
  loss — decide on `hasAnyAdapter()`, never wait for `isReachable()`
  here. The reachability probe can hang for its full timeout while
  offline.
- Tapping Retry must `finish()` the current `VoltHubActivity`
  and `start` a fresh one (see `VoltLink.kt` for the connectivity
  observer that also debounces VPN-flicker events by 700 ms outside
  the first-frame boot path).

---

## Android-Specific Bugs & Fixes

### 1. Keyboard covers inputs in WebView

**Fix:**

- `AndroidManifest.xml` on `VoltPaneActivity`:
  `android:windowSoftInputMode="adjustResize"`.
- Set `WebSettings.setSupportZoom(false)` and rely on JavaScript
  `visualViewport` scroll-into-view (single 350 ms delayed pass with
  `behavior:'auto'`, never `'smooth'`).

### 2. Status bar shows over WebView in portrait

**Fix in `VoltPaneActivity`:** apply `WindowInsets.systemBars`
padding **only on the top** in portrait, `0` in landscape (or better
— use `SafeDrawingContent` around the `AndroidView` that hosts the
WebView, but omit `bottom`).

### 3. Keyboard jitter (`scrollIntoView({behavior:'smooth'})` conflict)

Same JS fix as the Flutter template — see the
`_injectKeyboardScrollFix()` JS in `VoltPaneActivity.kt`. Use
`behavior:'auto'` and skip the CSS-injection loop while the keyboard
is open (`visualViewport.height < innerHeight * 0.75`).

### 4. Too many redirects loop

Track the last main-frame URL in `WebViewClient.shouldOverrideUrlLoading`
and, on `onReceivedError` with `ERR_TOO_MANY_REDIRECTS`, retry the
last URL up to 3 times before falling through to `VoltOfflineScreen`.

### 5. Safe-area white bars on notched Android

Inject the CSS override on `onPageFinished` — see
`volt_webview_safe_area.md` for the exact CSS payload.

### 6. Videos don't autoplay in WebView

```kotlin
webView.settings.mediaPlaybackRequiresUserGesture = false
```

If still not working, inject the JS from the pitfalls note.

### 7. Firebase App Check blocks requests in debug mode

Initialize App Check with the debug provider in debug builds:
```kotlin
FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
    if (BuildConfig.DEBUG) DebugAppCheckProviderFactory.getInstance()
    else PlayIntegrityAppCheckProviderFactory.getInstance(),
)
```

### 8. Gradle build fails with AccessDeniedException on Windows

Stop the Gradle daemon before cleaning:
```powershell
./gradlew.bat --stop; ./gradlew.bat clean
```

### 9. FCM token is `null` at first launch

Token is fetched via `FirebaseMessaging.getInstance().token` (a
`Task`). If still `null` when the body is being assembled, **omit
both** `push_token` and `firebase_project_id` keys entirely. The
`VoltTokenService.onNewToken` callback drives the re-POST when the
token eventually arrives.

### 10. `adjustResize` + `WindowCompat.setDecorFitsSystemWindows(false)` + IME insets

If `VoltPaneActivity` uses `enableEdgeToEdge()` you MUST also
consume `WindowInsets.ime` in the WebView container — otherwise
Compose will layout twice per keyboard frame.

---

## Obfuscation: What to Hide

### Safe to hide (do it)

| What | Where | How |
|------|-------|-----|
| Config endpoint domain | `VoltSecrets.kt` | XOR byte array via `VoltCodec.decode()` |
| AppsFlyer Dev Key | `VoltSecrets.kt` | XOR byte array |
| Firebase project number | `VoltSecrets.kt` | XOR byte array |
| Chrome/WebKit UA fragments | `VoltSecrets.kt` | XOR byte array |
| Log statements | All gateways | Wrap in `if (BuildConfig.DEBUG)` |
| Class names with intent | Rename per project | See Code Mixing |
| `webview`, `betting`, `casino` in route names | Use neutral names | `VoltGate*`, `Volt*` |

### Do NOT hide (breaks functionality)

| What | Why |
|------|-----|
| `INTERNET` permission | App can't make HTTP requests |
| `POST_NOTIFICATIONS` permission | System push dialog never appears on API 33+ |
| FCM channel meta-data | Push notifications silently dropped |
| `adjustResize` in Manifest | Keyboard covers inputs |
| `google-services.json` | Firebase fails to initialize |

### R8 / ProGuard keep rules

`app/proguard-rules.pro` must keep:
- `com.appsflyer.**`
- `com.google.firebase.**`
- The `VoltTokenService` class (referenced only from manifest)
- All Kotlin serialization data classes used for the config body

---

## Code Mixing: Mandatory Per-Project Changes

**Never ship two apps with the same folder names, class names, or
codec seed.** Stores scan for cross-submission structural patterns.
Because this developer ships 15+ apps, one match cascades to all of
them.

### Minimum changes per project fork

1. **Codec seed** — change `SEED_PHRASE` in `VoltCodec.kt`.
2. **Library versions** — vary at least two minors in
   `gradle/libs.versions.toml`.
3. **Class names** — rename at least `VoltVault`, `VoltGate`,
   `VoltAttribution`, `VoltBeacon`, `VoltPaneActivity`,
   `VoltHubActivity`. Do NOT keep the `Volt*` prefix on a fork
   — pick a fresh prefix (e.g. `Loom*`, `Praetor*`, `Vantage*`).
4. **Folder names** — rename `volt/` to the new prefix, and rename
   subfolders (`gateway/` → `core/`, `crypt/` → `guard/`, etc.).
5. **File names** — every `.kt` under the shell folder must be
   renamed.
6. **Kotlin package** — `com.thundercrest.thundercrestgame` →
   `com.newcompany.newapp`, applied atomically.

### R8 obfuscation

Release builds run with `isMinifyEnabled = true` +
`isShrinkResources = true`. Keep `mapping.txt` out of git.

---

## Library Versions Reference

Central versions live in `gradle/libs.versions.toml`. Current pins for
Thunder Crest (bump minors — never patch-level copy — for the next
fork):

```toml
[versions]
agp             = "8.13.0"
kotlin          = "2.4.10"
coreKtx         = "1.17.0"
lifecycle       = "2.9.4"
activityCompose = "1.11.0"
composeBom      = "2026.06.01"

appsflyer       = "6.15.3"
firebaseBom     = "34.4.0"
okhttp          = "5.1.0"
webkit          = "1.15.0"
security        = "1.1.0-alpha07"  # EncryptedSharedPreferences
datastore       = "1.1.7"
coroutines      = "1.10.2"
serialization   = "1.9.0"
```

Stagger these across projects — do not copy the exact triple.

---

## Testing Guide

### QA switches (`volt.properties`, debug builds only)

Copy `volt.properties.sample` to `volt.properties` (git-ignored) and
re-sync Gradle. Both keys are hardcoded to their safe values in the
release build, so nothing here can reach a store artifact.

| Key | BuildConfig field | Effect |
|---|---|---|
| `volt.forceStatus` | `FORCE_AF_STATUS` | Non-empty ⇒ overwrite `af_status` in the request body, stamped after the SDK's own value. Set to `Non-organic` to reach the shell from a directly installed APK. |
| `volt.stickyVerdict` | `STICKY_VERDICT` | `false` ⇒ a native verdict is not persisted, **and** a native verdict written earlier is cleared on launch. Every debug launch asks the backend again. |
| `volt.probeLink` | `PROBE_LINK` | Non-empty ⇒ skip attribution and the config POST entirely, open the shell on this URL. Checked before the persisted mode, so it works on an install already locked to the game. |

`forceStatus` is the one that actually unlocks the gray part on a dev
device, because §8a is the whole gate. `probeLink` is narrower — it
shows the shell but skips the routing that decides to show it.

`stickyVerdict=false` is what makes repeat testing practical: in
production the first organic answer locks the install into the game
permanently, and `route()` returns to the game before it asks anyone
anything, so without clearing it an uninstall is the only way back.

**Both must be combined on a device that already went organic once.**
Turning on `forceStatus` alone changes nothing there, because the
persisted native mode short-circuits the router before the request body
is ever built.

### Test the tracking link (non-organic install)

1. Add your device's GAID to the AppsFlyer Test Devices list
   (Configuration → Test devices). Without this the SDK ignores the
   click and every install reads organic.
2. Uninstall the app, or set `volt.stickyVerdict=false` — a native
   verdict from an earlier attempt would otherwise short-circuit the
   whole flow before AppsFlyer is even asked.
3. Click the OneLink **on the test device, in a browser, before
   installing**, with `&advertising_id=<GAID>` appended.
4. Install the app.
5. Expected in Logcat: `af_status: "Non-organic"` in the AppsFlyer
   response, the same value inside `[VoltGate] request (N fields)`,
   then `ok: true` with a URL and `VoltPaneActivity` opening.

Installing straight from Android Studio or an APK, with no click
beforehand, is by definition an organic install — AppsFlyer answers
`{"af_message":"organic install","af_status":"Organic"}` and the
backend correctly refuses a URL. That is the flow working, not a bug.
Use `volt.probeLink` when you only need to look at the shell.

### Test organic install (game)

Install WITHOUT clicking a tracking link first. Expected: game screen,
no push permission dialog, no WebView.

### Test push notifications

Firebase Console → Cloud Messaging → Test message. Push token must be
in the config request body (check Logcat: `[VoltGate] request`).

### Check attribution logs

In debug builds:

| Tag | Line | Meaning |
|---|---|---|
| `VoltAttribution` | `conversion arrived, af_status=…` | What the SDK actually decided |
| `VoltAttribution` | `attribution re-asked now that the link is up` | `retrace()` fired after an empty first reading |
| `VoltGate` | `request (N fields): {…}` | The exact JSON that went to `config.php` |
| `VoltGate` | `response <code>: {…}` | The exact body that came back |
| `VoltGate` | `verdict: allowed=… note=…` | How that body was interpreted |
| `VoltPortal` | `backend ruled native (…)` | The verdict was persisted |

The field count in front of the request body is the quick check for
"are all the parameters there". An organic install legitimately sends
only about eight fields, because AppsFlyer's own payload is just
`af_message` / `af_status` / `install_time` — the campaign keys only
exist for an install that came through a tracking link.

A healthy launch shows `prime()` running from `VoltBoot` *before*
any Activity log line. If the first AppsFlyer log appears after
`VoltHubActivity`, the SDK missed the foreground transition and
this session will read organic — fix the ordering, not the payload.

---

## App Requirements

| Requirement | Value |
|------------|-------|
| Target SDK | 36 |
| Min SDK | 24 |
| App size | < 100 MB (aim for < 40 MB) |
| Privacy policy | Must be accessible in-app (`VoltPaneActivity` mini-view for legal links) |
| Loading screen | Must adapt to portrait AND landscape |
| Push promo screen | Must adapt to portrait AND landscape, buttons centered without safe-area inset |
| Loading time | < 10 seconds on normal internet speed |
| Adaptive icon | Must fill the icon shape, no empty borders, no clipping |
| Notification icon | Separate vector icon (NOT the launcher icon) |
| Push images | Must be supported (`NotificationCompat.BigPictureStyle`) |

---

## Common Errors Quick Reference

| Error | Cause | Fix |
|-------|-------|-----|
| Config returns 403 | Firebase App Check not configured | Install debug provider in debug builds |
| Push token null | FCM init race condition | Token sent on next launch or refresh — normal on first run |
| WebView blank on organic | No URL returned — correct behavior | Game should show instead |
| Keyboard hides inputs | Missing one of the three keyboard layers | Apply all three: `adjustResize` + Compose IME inset + JS inject |
| White bar in WebView | Safe-area CSS not overridden | Check the injection fires on `onPageFinished` |
| Videos need tap to play | `mediaPlaybackRequiresUserGesture` not `false` | Set on `webView.settings` |
| App crashes on `./gradlew clean` (Windows) | Gradle daemon holds file locks | Run `./gradlew.bat --stop` first |
| Push shows no image | Missing `BigPictureStyle` | Use `NotificationCompat.BigPictureStyle` in `VoltBeacon.render()` |
| Attribution always Organic | `AppsFlyerLib.init` ran after an Activity existed | Move it to `VoltBoot.onCreate` via `prime()` — do **not** add a GCD recheck |
| Paid user stuck in the game forever | `native` committed on a network failure | Only commit when `VoltReply.answered` **and** attribution is present |
| Push tap reopens the old page | Only `EXTRA_URL` was read | Also read raw `url` / `link` / `target_url` extras (pitfalls §32) |
| Release build fails on `volt_*.png` | File is JPEG bytes with a `.png` name | Convert to WebP; `aapt2` reads magic bytes |
| `InvalidFragmentVersionForActivityResult` in `lintVital` | `play-services-basement` pins `androidx.fragment:1.1.0` | Pin a modern `androidx.fragment` explicitly |
| `OutOfMetaspaceError` during `lintVitalAnalyze` | Default daemon metaspace too small for a four-module Compose build | Raise `org.gradle.jvmargs` metaspace in `gradle.properties` |
| Android Studio reports `Unresolved reference 'java'` / `'let'` / `'apply'` while Gradle builds fine | IDE has no JDK attached — `gradleJvm` is `#GRADLE_LOCAL_JAVA_HOME` and `JAVA_HOME` is unset | Point `.idea/gradle.xml` `gradleJvm` at a real JDK (e.g. `jbr-21`) and re-sync; the code is not the problem |

---

## Packer script (paste into a scratch `.kts` file locally)

```kotlin
// Local packer — do NOT commit. Paste the plaintext values below,
// run once via `kotlin VoltPacker.main.kts`, copy the printed
// intArrayOf(...) blocks into VoltSecrets.kt, delete the file.
val SEED_PHRASE = "CHANGE_ME_PER_PROJECT"
val STREAM_LEN  = 24

fun stream(): IntArray {
    var hash = 0x811C9DC5.toInt()
    for (c in SEED_PHRASE.toCharArray()) {
        hash = (hash xor c.code) and 0xFFFFFFFF.toInt()
        hash = (hash * 0x01000193) and 0xFFFFFFFF.toInt()
    }
    var state = if (hash == 0) 0x9E3779B9.toInt() else hash
    val out = IntArray(STREAM_LEN)
    for (i in 0 until STREAM_LEN) {
        state = state xor (state shl 13)
        state = state xor (state ushr 17)
        state = state xor (state shl 5)
        out[i] = (state ushr 16) and 0xFF
    }
    return out
}
fun pack(plaintext: String): IntArray {
    val s = stream()
    return IntArray(plaintext.length) { i ->
        (plaintext[i].code xor s[i % STREAM_LEN] xor (i and 0xFF)) and 0xFF
    }
}
fun p(name: String, plain: String) {
    val bytes = pack(plain).joinToString(", ")
    println("val $name = intArrayOf($bytes)")
}

p("CONFIG_ENDPOINT_BYTES", "https://thunndercrest.com/config.php")
p("ATTRIBUTION_KEY_BYTES", "<APPSFLYER_DEV_KEY>")
p("MESSAGING_PROJECT_BYTES","<FIREBASE_PROJECT_NUMBER>")
p("CHROME_VERSION_BYTES",  "149.0.7827.163")
p("WEBKIT_VERSION_BYTES",  "537.36")
```
