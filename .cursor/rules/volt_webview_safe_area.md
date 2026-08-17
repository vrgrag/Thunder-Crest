# WebView safe-area CSS injection — do not squash the partner site

The gray-flow shell injects a CSS override on `onPageFinished` to
neutralise `env(safe-area-inset-*)` gaps that would otherwise leave
blank strips on notched Android devices. This override is easy to
overreach with — if it does, the partner site renders visibly
narrower than in a normal mobile browser: buttons appear squeezed
together and touch the screen edges instead of keeping their gutters.

## Symptom

Comparing the same URL in the WebView vs. Chrome on the same device:

- WebView: content columns extend fully to the left/right edges, no gutters
- Browser: content has natural side padding (~12–24 px) as designed by the site

The site itself is fine — we broke it with our CSS.

## Root cause

Older template code shipped this rule:

```javascript
// ❌ BAD — kills the site's own gutters together with the safe-area gap.
'html,body,#__nuxt,#__layout,#app,#root,' +
'.gameview-mobile-header,.app-header{' +
  'padding-top:0!important;' +
  'padding-left:0!important;' +
  'padding-right:0!important;' +
  'margin-top:0!important;' +
'}';
```

Zeroing `padding-left/right` and `margin-top` on `html/body/#app/#root`
also erases the site's designed horizontal padding.

## Correct pattern (used in `VoltPaneActivity.injectSafeAreaCss()`)

Split the injection into two independent responsibilities:

1. **Overwrite custom safe-area CSS variables** the site defines
   itself (`--sat`, `--sab`, `--safe-top`, ...). Sites that never
   define such variables are unaffected.
2. **Only touch the visual top spacer**, and only on classes that
   are known to be sticky / decorative headers — never on
   `html/body/#app/#root`.

```javascript
// ✅ GOOD — inject inside VoltPaneActivity via webView.evaluateJavascript(...)
(function () {
  if (window.__voltSa) return; window.__voltSa = true;
  var ID = '__volt_sa';
  var CSS =
    ':root{' +
      '--safe-area-inset-top:0px!important;' +
      '--safe-area-inset-right:0px!important;' +
      '--safe-area-inset-bottom:0px!important;' +
      '--safe-area-inset-left:0px!important;' +
      '--sat:0px!important;--sar:0px!important;' +
      '--sab:0px!important;--sal:0px!important;' +
      '--safe-top:0px!important;--safe-bottom:0px!important;' +
      '--safe-left:0px!important;--safe-right:0px!important;' +
    '}' +
    // Only narrow decorative headers. Never html / body / #app / #root.
    '.gameview-mobile-header,.app-header,.js-safe-top{' +
      'padding-top:0!important;' +
      'margin-top:0!important;' +
    '}';
  function kbOpen() {
    if (!window.visualViewport) return false;
    return window.visualViewport.height < window.innerHeight * 0.75;
  }
  function apply() {
    if (kbOpen()) return;
    var head = document.head || document.documentElement; if (!head) return;
    var m = document.querySelector('meta[name="viewport"]');
    if (m && !/viewport-fit\s*=\s*contain/i.test(m.getAttribute('content')||'')) {
      var c = (m.getAttribute('content')||'').replace(/,?\s*viewport-fit\s*=\s*\w+/ig,'').trim();
      m.setAttribute('content', c + (c ? ', ' : '') + 'viewport-fit=contain');
    }
    var s = document.getElementById(ID);
    if (!s) { s = document.createElement('style'); s.id = ID; head.appendChild(s); }
    if (s.textContent !== CSS) s.textContent = CSS;
  }
  apply();
  ['pushState','replaceState'].forEach(function (fn) {
    var o = history[fn]; history[fn] = function () { var r = o.apply(this, arguments); setTimeout(apply, 80); setTimeout(apply, 400); return r; };
  });
  window.addEventListener('popstate', function () { setTimeout(apply, 80); });
  setInterval(apply, 2500);
})();
```

## The native half: the WebView must be inset, not full-bleed

The CSS above only removes the *page's* safe-area padding, on the
assumption that the *window* already reserved it. That assumption has to
be true, and it is `installInsetRule()` in `VoltPaneActivity` that
makes it so.

The window is edge-to-edge (`setDecorFitsSystemWindows(false)` plus
`LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`), so an unpadded WebView
reports a viewport taller than the screen. The page then sizes `100vh`
against a height whose top strip is behind the clock and whose bottom
strip is behind the gesture bar. Every full-height layout is wrong by
exactly those two bands, and anything anchored to the bottom — usually
the primary button — ends up unreachable.

So the container is padded by the **union of the system bars and the
display cutout, on all four sides**:

```kotlin
val bars = insets.getInsets(WindowInsets.Type.systemBars())
intArrayOf(
    maxOf(cutout?.safeInsetLeft ?: 0, bars.left),
    maxOf(cutout?.safeInsetTop ?: 0, bars.top),
    maxOf(cutout?.safeInsetRight ?: 0, bars.right),
    maxOf(cutout?.safeInsetBottom ?: 0, bars.bottom),
)
```

Three details that matter:

- **`systemBars()`, never `ime()`.** Including the keyboard inset
  re-lays-out the view every time the IME opens and fights the
  scroll-into-view fix.
- **No portrait/landscape branching.** An earlier revision padded top
  in portrait and left/right in landscape and skipped bottom entirely.
  Insets already describe the orientation; branching on it by hand is
  how one of the four sides gets forgotten.
- **Zero padding is a valid answer.** On many devices the window
  already stops above the navigation bar, so `bars.bottom` is 0 and
  nothing is wasted. Verified on a Galaxy S23: window `1080×2042`,
  WebView `[0,112][1080,2042]` — cutout honoured at the top, no
  double-inset at the bottom.

Measure it rather than eyeballing it:

```
adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml
```

The `android.webkit.WebView` node's `bounds` must sit inside the safe
area and must not be shorter than it.

## Hard rules

- ❌ **Never** apply `padding-left:0!important` or
     `padding-right:0!important` to `html`, `body`, `#__nuxt`,
     `#__layout`, `#app`, `#root`.
- ❌ **Never** apply `margin-*:0!important` to those elements either.
- ✅ Only touch `padding-top` / `margin-top`, and only on the site's
     known top-spacer class list.
- ✅ Keep the CSS-variable overrides — they only take effect if the
     site already declares those variables.
- ✅ Also skip the whole patch while the keyboard is open
     (compositor race — see `volt_pitfalls.md` and the
     `kbOpen()` guard above).
- ✅ Create the viewport meta when the page has none. Without it the
     WebView lays the page out on a 980 px canvas and scales it down to
     fit, which is how a mobile site ends up unreadably small in a
     wrapper.
- ✅ Pin `WebSettings.textZoom = 100`. Left alone the WebView multiplies
     every font by the system font scale, and plenty of devices ship
     above 1.0 — the page then lays out against text 15–30 % larger than
     its CSS says, which reads as "all the sizes are wrong" at once.

## Verification checklist

Before shipping a new fork, compare side-by-side on a real device:

- [ ] Open the same partner URL in Chrome and in the WebView shell.
- [ ] Buttons / cards / grids have identical horizontal gutters.
- [ ] No blank strip above the fixed header from safe-area insets.
- [ ] On landscape with a camera notch, no white bars on the sides
      (also see `volt_pitfalls.md` §11 for the native cutout
      inset).

If the gutters differ, the injection is over-reaching. Adjust the
JavaScript payload — the fix is always in the JS, not in the native
insets.
