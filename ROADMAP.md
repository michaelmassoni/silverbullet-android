# Roadmap

Ideas for making the app better than the SilverBullet PWA. Brainstormed 2026-11-05.

## Done (v0.2.0, 2026-11-05)

- **Bottom bar** (ideas 1 and 3) with per-entry toggles in settings, plus the **settings shortcut**
  (idea 2, as an optional bar entry). Theme-matched bars (idea 11) came along with it.
- **Formatting row** (idea 9): no custom bar needed. SilverBullet already ships a context-aware
  keyboard bar; it was hidden because WebView's `navigator.virtualKeyboard` always reports height 0.
  `assets/bridge.js` replaces it with a stand-in fed the real IME height from Android.
- **Command bridge answered:** `window.client` exposes `runCommandByName`, `startCommandPalette`,
  `startPageNavigate`, `openNavigatorView`, `navigate`; panels close with
  `client.ui.viewDispatch({type: "hide-panel", id: "modal"})`. Command names come from
  `client.ui.viewState.commands` (a Map). Internal API, so re-test after SilverBullet upgrades.
- **First-run sidebar quirk** worked around: the bar's sidebar button opens the view the space
  docks on the left (`view.defaults` with `dock = "lhs"`) when SilverBullet's own toggle is missing.

## Done (v0.3.0, 2026-11-05)

- New bar defaults: sidebar, back, home, search, journal, settings. New entries: open page,
  run command (renamed from command palette), read-only toggle with lit state.
- Setting to hide SilverBullet's top-right `actionButtons` (on by default, CSS class on `<html>`).
- Bugs fixed: Back used a synthetic Escape keydown to close pickers, which made SilverBullet think
  a hardware keyboard was attached and dock its keyboard bar permanently. Never synthesise key
  events; close via `.sb-nav-close`, `viewDispatch` hide-* actions, or Cancel buttons. Back could
  also return to the `/.auth` login page; history is now cleared after sign-in. Bar hidden on the
  login page.

## Follow-ups noticed

- Search opens without the keyboard; you have to tap the field first.
- Bar entries can be toggled but not reordered.
- Test on newer WebViews that the keyboard height isn't applied twice (emulator had WebView 124).


## How each idea would be built

- **Native:** Android code. Robust, works with any server, unaffected by SilverBullet updates.
- **Injected:** CSS/JS the app adds to the SilverBullet page. Quick, but it relies on SilverBullet's
  internal element names (`#sb-top`, `.sb-mobile-dock-button`, …), so an update can break it.
- **Bridge:** native buttons that run SilverBullet commands via `window.client` (see "Done" above;
  implemented in `assets/bridge.js`).

## Known quirk: sidebar button missing on first run

The top-left sidebar button (`sb-mobile-dock-button`) only renders once a view is docked on the left
(`Mo("lhs")` in `client.js`) and the screen is under 600px wide. On a fresh install it didn't
appear until **Navigate: Folder Tree** was run once from the command palette. It isn't caused by
the app: the client code has no standalone/mobile condition for it. Fix idea: have the app open the
tree view once on first launch.

Related finding: SilverBullet decides "standalone" with `matchMedia("(display-mode: browser)")`. A
WebView always reports `browser`, so any `actionButtons` entry marked `standalone = true` is hidden
in the app. None are used today, but keep it in mind.

## Ideas

### User's three
1. **Bottom action bar** (native + bridge). A thumb-reach bar, suggested order:
   sidebar · search · journal · capture · ☰. Native beats moving SilverBullet's top bar with CSS.
   Hide it while the keyboard is open.
2. **Settings inside the app** (native). Reachable from the bar or a long-press, not just the
   launcher menu. Server, capture page, token, appearance, bar layout, pending-captures view.
3. **Search button** (bridge). Opens Silversearch if installed, otherwise SilverBullet's page
   picker. Plus a "Search notes" launcher shortcut.

### Things a PWA can't do
4. **Richer quick capture** (native)
   - Voice dictation into a bullet.
   - Photo/file: share an image from the gallery or take a photo; upload it into the space via
     `PUT /.fs/…` and embed it on today's page.
   - Task mode toggle: save the line as `* [ ]`.
   - Target picker in the dialog (Journal, Inbox, a recent page).
5. **Home-screen widgets** (native). Today's journal, a pinned page, or open tasks, read via
   `GET /.fs/…`, cached offline. Tapping opens that page.
6. **Pin a page to the home screen** (native + bridge). Pinned shortcuts plus recent pages in
   the launcher's long-press menu.
7. **Task reminders** (native). Notifications for tasks with due dates. Most valuable, hardest
   (needs to read and parse tasks from the space). A separate project.
8. **Fingerprint/face unlock** (native). Optional app lock.

### Polish
9. **Formatting row above the keyboard** (injected or native). Bullets, checkboxes, `[[link]]`,
   indent and outdent. SilverBullet has a basic `keyboardBar` option; this would be phone-tuned.
10. **Gestures** (injected). Edge-swipe opens the tree; swipe back through page history.
11. **Theme matching** (injected). Status and navigation bars take SilverBullet's colours and
    dark mode.
12. **Native sync indicator.** "Offline · 3 changes waiting" instead of just the yellow bar.

## Suggested order
1. Bottom bar + search + settings (1–3), plus the first-run tree fix. Builds the command bridge
   that most other ideas reuse.
2. Formatting row (9).
3. Capture upgrades (4), voice and photo first.
4. Widgets and pinned pages (5, 6).
5. Later: reminders (7).

Waiting on the user: which to build first, and the bottom-bar button set and order.
