# Volt launch flow — routing, attribution, push

Read this before changing anything in `VoltHubActivity`,
`VoltAttribution`, `VoltBeacon` or `VoltRelay`. Every rule below
exists because of a specific failure, and most of them are invisible
until an install is already in the field and cannot be fixed remotely.

---

## 1. Persisted state

`VoltVault` (`olym_local_prefs` plain, `olym_sealed_prefs` encrypted).

| Key | Store | Meaning |
|---|---|---|
| `shell_pref_v1` | plain | `VoltMode`: `pending` / `web` / `native` |
| `cache_blob` | sealed | destination URL |
| `cache_ttl` | plain | unix seconds; `0` or absent means no expiry |
| `pending_blob` | sealed | one-shot cold push URL |
| `beam_id` | sealed | cached FCM token |
| `beam_ok` / `beam_blocked` / `beam_asked` | plain | push permission state |
| `invite_after` | plain | snooze deadline after a Skip |

If the encrypted store cannot be opened, URLs live in memory for that
process only. They are never written to the plain file as a fallback.

---

## 2. The state machine

### `pending` — first launch

```
no adapter          -> VoltQuietActivity on frame one.
                       Nothing started, nothing persisted.
adapter (or grace)  -> ignite AppsFlyer -> retrace -> collect body
                       -> POST config
     ok + url       -> persist web + url + ttl -> shell
     otherwise      -> game, and persist native ONLY IF
                       reply.answered && tracker.hasAttributionData()
```

**The native verdict is permanent, so it has to be real.** A first
launch on a flaky connection must not lock the user into the game for
the life of the install because one socket timed out. `VoltReply`
carries `answered` precisely to separate "the server ruled native" from
"we never reached the server" — both arrive as `allowed = false`.
`VoltGate.silence()` is the second case; `VoltGate.verdict()` is the
first, and only the first is written down.

### `web` — was the shell last time

```
push URL stashed    -> shell with it (outranks everything)
otherwise           -> ignite + retrace -> POST config (short budget)
     ok + url       -> update cache -> shell
     any cached url -> shell with the cached one (even if expired)
     nothing        -> VoltQuietActivity
```

The backend is asked on **every** return, not only when the cache
expired: a campaign destination can move at any time, and a URL sent
without an expiry would otherwise be pinned forever. The cache is the
fallback for when the ask does not land, never a way to skip it.

A `web` install never drops into the game. Once the backend has said
"WebView" for this install, a failed request is a connectivity problem,
not a re-decision.

### `native` — was the game last time

The game, always, with no network work at all. This is also what makes
the white part launch with the radio off, which Play review requires.
Once native, stay native — including when a push carries a URL.

---

## 3. AppsFlyer: prime vs ignite

`prime()` belongs in `VoltBoot` (the `Application`) and puts
nothing on the wire. What it does is let the SDK register its
`ActivityLifecycleCallbacks` before the first Activity exists. Calling
`init` from an Activity that is already on screen means the SDK never
observes the foreground transition for this launch: the install stays
queued until the next one, the conversion map comes back empty, and the
user is filed as organic. This is the single most expensive failure in
the flow and it leaves no trace in logs.

`ignite(activity)` is the half that talks to AppsFlyer, and the router
calls it only after confirming a connection. Starting the SDK with no
route out produces an immediate failure callback that is
indistinguishable from a genuine organic install. It needs a real
Activity — the application context satisfies `init` but leaves `start`
without the foreground signal it exists to report.

`retrace(activity)` re-asks when the first reading came back empty and
the link has since come up. A reading that carried data is left alone.

### There is no organic re-check, and there must not be one

In AppsFlyer 6.x the map handed to `onConversionDataSuccess` already
*is* the GCD result: the SDK fetches `install_data/v5.0` on a signed
sharded host and calls straight through. An earlier revision of this
project re-queried the public `install_data/v4.0` endpoint with the dev
key whenever the verdict was `Organic`. That endpoint answers
`400 {"error_reason":"App ID is incorrect"}` — the modern API wants an
account token, not a dev key — so the re-check could never succeed, and
it cost every organic launch a five-second stall plus a round trip on
the loading screen. It is gone. Do not bring it back.

---

## 4. Push routing

Order of preference, and the reason for each:

1. **URL fails `VoltUrlGuard`** → dropped, notification shown as text.
   A tap must never open something the shell cannot load.
2. **Mode is `native`** → notification shown, URL neither delivered nor
   saved. Flipping a native user into a WebView after the fact is a
   store-review problem, not a feature.
3. **Shell alive** → `VoltRelay.offer()` hands it over in-process and
   nothing is persisted. A warm URL is a fact about this moment; saving
   it would make it replay on the next cold start.
4. **Otherwise** → stashed in the vault for exactly one cold start.

### Reading the tap intent (this one bites)

A data-only message reaches `VoltTokenService`, which builds the tap
intent with our own `portal_url` extra. A message carrying a
`notification` block is drawn by the Firebase SDK itself whenever the
app is not in the foreground — **that path never runs our service**, and
the tap opens the launcher with the raw `data` payload as plain string
extras instead. `VoltBeacon.extractUrl` therefore reads both our own
extra and the raw `url` / `link` / `target_url` keys. Reading only our
own is how a pushed link gets silently dropped and the shell reopens on
the previously saved page.

`onNewToken` writes the token to the vault. The config body prefers the
cached one so a launch does not wait on Firebase when it does not have
to, and omits `push_token` and `firebase_project_id` **together** when
there is no token — never as empty strings.

---

## 5. Connectivity

`hasAnyAdapter()` and `isReachable()` answer different questions and are
not interchangeable.

The capability check cannot tell you the internet is gone, and a VPN is
the case that makes the difference impossible to ignore: with a tunnel
up, the active network for this uid *is* the tunnel, and switching Wi-Fi
off underneath leaves it connected, internet-capable and validated with
nothing behind it. No `onLost` is delivered for a network that never
went away. Captive portals behave the same.

`isReachable()` therefore ends in a TCP handshake against two raw IPs on
two different ports (`1.1.1.1:443`, `8.8.8.8:53`). Raw IPs on purpose —
a hostname needs DNS, and DNS down a dead tunnel is exactly the lookup
that hangs. Two targets because a network that blocks outbound 53 to
public resolvers is restrictive, not offline.

Offline transitions in `statusStream()` are debounced; coming back
online never is.

---

## 6. Log evidence for a healthy first launch

Debug builds only (`Trace` equivalents are stripped by `BuildConfig.DEBUG`).

```
VoltAttribution  conversion arrived, af_status=Non-organic
VoltGate         response: 200 (N chars)
VoltPortal       -> shell
```

An organic install that is genuinely organic:

```
VoltAttribution  conversion arrived, af_status=Organic
VoltGate         response: 404
VoltPortal       backend ruled native
```

A launch that must **not** persist native:

```
VoltGate         request never landed / timeout
VoltPortal       endpoint unreachable, game for now, decision left open
```

If you see `backend ruled native` without a preceding HTTP status, the
`answered` plumbing has been broken.
