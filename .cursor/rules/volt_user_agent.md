# Volt User-Agent — device authenticity + slot-vs-crash suffix

## 1. Baseline rules (apply to every fork)

The forged User-Agent is used by BOTH the OkHttp client that hits the
config endpoint AND the WebView (`WebSettings.userAgentString`). It
must:

- **Look like a real Chrome on a real Android device.** Include a
  current Chrome major version, WebKit fragment, real Android release
  and a plausible device model / build id.
- **NEVER contain** any of these substrings (case-insensitive):
  - `wv/` (WebView-specific token — must be stripped)
  - the package name, class names or SDK identifiers
- **Reflect the actual device on which the app runs.** Read
  `Build.MODEL`, `Build.DEVICE`, `Build.VERSION.RELEASE`,
  `Build.MANUFACTURER` — do not hardcode a fixed device string. Two
  identical UAs across ten installs are a cluster signal for
  scanners.
- **Be identical between HTTP + WebView.** The partner backend
  cross-checks the two; a mismatch breaks session continuity.
- **Bump the Chrome major version** on every project. A stale major
  (e.g. Chrome/112 on an app shipping in 2026) is trivially
  detectable.
- **Use a unique Chrome version per project.** Every fork must have a
  different Chrome build / patch number so the UA cannot be used as a
  cross-app fingerprint.

### Chrome version rule for AI agents

**Major version is always 149.** Build and patch must be freshly
generated for each new project — never copy them from another project
or from the template default.

Generation formula:
- Major: `149`
- Minor: `0` (always)
- Build: random integer in **7500–7900**
- Patch: random integer in **10–250**

Example: `149.0.7827.163`

Write the generated version into `CHROME_VERSION_BYTES` in
`VoltSecrets.kt` (via the packer script — see
`volt_gray_guide.md` §"Packer script"). **Never ship two projects
with identical build / patch numbers.**

Canonical shape (no identity suffix):

```
Mozilla/5.0 (Linux; Android 15; SM-S931U Build/AP3A.240905.015.A2) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.7827.163 Mobile Safari/537.36
```

The `VoltUaForge` class assembles this string from
`Build.VERSION.RELEASE`, `Build.MANUFACTURER`, `Build.MODEL`,
`Build.ID` and the two decoded byte arrays.

---

## 2. Identity suffix — REMOVED for this project

Earlier revisions of this shell appended an identity suffix to the UA:

`
<browser UA> appid/<packageId> appname/<AppNameToken>
`

**Do not do this.** Those tokens appear in no real browser, so every
request carrying them announces itself as a wrapped app — the exact
opposite of what forging the UA is for. VoltUaForge.build() now
returns a plain Chrome-on-Android string and nothing else, and
VoltFacade.UA_APP_NAME has been deleted along with it.

The suffix was never a partner requirement here; the backend routes on
the request body (f_status), not on the UA. If a specific partner
ever does ask for it, add it for that partner only and write down who
asked — do not reintroduce it as a default.

Correct UA for this project:

`
Mozilla/5.0 (Linux; Android 15; Samsung SM-S931U Build/AP3A.240905.015.A2) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.7823.147 Mobile Safari/537.36
`

---

## 4. Verification

Before shipping any build:

- [ ] Log `VoltUaForge.value` in a debug run and confirm it matches
      the canonical shape from §1.
- [ ] Confirm the suffix presence matches the game theme per §2.
- [ ] Load `https://www.whatismybrowser.com/detect/what-is-my-user-agent`
      in the WebView and screenshot the parsed result — it must
      identify as Chrome on Android with the current major.

If any check fails, do not ship — fix `VoltUaForge.kt` first.
