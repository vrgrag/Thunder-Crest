# START HERE — Cursor / AI Agent Entry Point (Thunder Crest)

> **Read this file first. Every time you are asked to work on this
> project, read this file, then read every file in `.cursor/rules/`
> before writing any code.**

Thunder Crest is a **native Android (Kotlin + Jetpack Compose)**
gray-part flow app. The gray-flow architecture is documented in full in
`.cursor/rules/volt_gray_guide.md` — that document is the API
contract, the state machine, and the setup manual. This file is the
short "where to look" index that points into it.

Reference implementations (read for behaviour, never copy names):

- Flutter template: `D:\flutter_proj\gray_part_flow\`
- Kotlin Oracle port: `D:\flutter_proj\Olympus_Surge\`
- Kotlin alternate structure: `D:\flutter_proj\golden rittler\`

Thunder Crest uses its own **`volt`** package with unique class /
folder / preference / library fingerprints.

---

## 1. What "gray flow" means (30-second version)

A dual-mode Android app:

- **Gray mode** — full-screen `WebView` in `VoltPaneActivity`, URL from
  the remote config endpoint. Non-organic (paid) users see this.
- **White mode** — native Compose game in `MainActivity`. Organic users
  and store reviewers see this. Must launch **without internet**.

Routing is decided ONCE per install by the backend from AppsFlyer
attribution. It cannot be spoofed client-side.

Process entry: `volt/VoltBoot.kt` (`Application`) primes Firebase +
AppsFlyer. Launcher: `volt/VoltHubActivity.kt`.

### Component map

| File | Role |
|---|---|
| `volt/VoltBoot.kt` | `Application`. Firebase init + AppsFlyer `prime`. |
| `volt/VoltHubActivity.kt` | Launcher. Full routing state machine. |
| `volt/VoltPaneActivity.kt` | WebView shell. |
| `volt/VoltPermitActivity.kt` | Push opt-in (Accept / Skip). |
| `volt/VoltQuietActivity.kt` | No-connection + Retry / auto-retry. |
| `volt/pipe/VoltTrack.kt` | AppsFlyer: `prime` / `ignite` / `retrace`. |
| `volt/pipe/VoltAsk.kt` | Config endpoint POST. |
| `volt/pipe/VoltNet.kt` | Adapter state + TCP reachability probe. |
| `volt/pipe/VoltStore.kt` | Plain + encrypted persistence. |
| `volt/pipe/VoltPush.kt` | FCM channel, token, service, tap routing. |
| `volt/pipe/VoltHandoff.kt` | Warm push hand-off to a live shell. |
| `volt/pipe/VoltAgent.kt` | Forged UA + shared OkHttp client. |
| `volt/guard/VoltUrlGate.kt` | http(s) shape check on inbound URLs. |
| `volt/cipher/VoltCipher.kt` | XOR codec (`SEED_PHRASE` + `STREAM_LEN`). |
| `volt/cipher/VoltPacked.kt` | Encoded endpoint / AF key / FCM / UA. |
| `volt/mark/VoltId.kt` | packageId / timings. |
| `volt/mark/VoltLinks.kt` | Privacy / support / home URLs. |

---

## 2. Where to look for what

| You need to… | Read |
|---|---|
| Architecture end-to-end | `rules/volt_gray_guide.md` |
| Routing / attribution / push | `rules/volt_launch_flow.md` |
| Known Android bugs | `rules/volt_pitfalls.md` |
| WebView safe-area CSS | `rules/volt_webview_safe_area.md` |
| User-Agent contract | `rules/volt_user_agent.md` |
| Ship gate | `FINAL_CHECKLIST.md` |

---

## 3. Order of operations for this project

1. ★ Config URL — done (`https://thunndercrest.com/config.php`).
2. ★ Privacy / Support — done.
3. ★ Screen art + launcher icon — done.
4. ★ AppsFlyer Dev Key + OneLink host — done (`PG6N5qRcCdbtsBJs7vTBre`, `thundercrest.onelink.me`).
5. ★ `google-services.json` + Firebase project number — done (`500343001472`).
6. After AF/Firebase arrive:
   - paste into `tools/pack_secrets.py`
   - keep `VoltCipher` seed (`Tc7!rKm_q9Vx2` / `31`)
   - run packer → update `VoltPacked.kt`
   - drop `google-services.json` into `app/`
   - set real OneLink host in `AndroidManifest.xml`
7. Smoke-test debug, then QA against `FINAL_CHECKLIST.md`.

Debug QA without AF: put a probe URL in `ridge.properties`:

```
ridge.probeLink=https://example.com
ridge.stickyVerdict=false
```

---

## 4. Fingerprint — already diversified for Thunder Crest

- Package / theme: `volt` (not `oracle` / `yard` / Flutter names)
- Codec: `Tc7!rKm_q9Vx2` / stream 31
- Prefs: `crest_local_box` / `crest_vault_box`
- Channel: `volt_crest_alerts`
- Drawables: `volt_*` / `ic_volt_flame`
- Library minors differ from Olympus Surge (see `gradle/libs.versions.toml`)
- Button theme: gold/ember (not Olympus sky-blue)

Never copy identical class trees, seeds, channel ids, or preference
filenames into the next portfolio app.

---

## 5. Invariants that must NEVER break

1. Pending + offline → No-WiFi on **frame one**; mode stays pending.
2. `native` persisted only when endpoint **answered** AND attribution
   map was non-empty.
3. Once `native`, stay native — push never opens WebView.
4. Web install never drops to game because one config call failed.
5. Loading bar fills to 1.0 at the hand-over frame.
6. Permit / Quiet screens: **no** systemBars/safeDrawing padding;
   buttons horizontally centered, pinned under the red plaque.
7. WebView respects cutout insets; white game launches offline.
8. AppsFlyer `prime` in `VoltBoot`, `ignite` only after connectivity.
9. Push URL extract reads custom extras **and** raw `url`/`link`.
10. Warm push → `VoltHandoff` (never persist); cold → one-shot stash.

---

## 6. Screen assets

| Screen | Portrait | Landscape |
|---|---|---|
| Loading | `volt_splash_port.webp` | `volt_splash_land.webp` |
| Notifications | `volt_permit_port.webp` | `volt_permit_land.webp` |
| NoWifi | `volt_quiet_port.webp` | `volt_quiet_land.webp` |

Source copies also live under project `assets/` with the original
`Vertical_*` / `Horizontal_*` names.
