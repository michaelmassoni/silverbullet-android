// Injected by the Android app at document start, only on the configured server's origin.
(function () {
  if (window.__sbAndroid) return;

  // --- Keyboard -------------------------------------------------------------------------------
  // SilverBullet lays itself out above the on-screen keyboard (and shows its formatting bar) using
  // the VirtualKeyboard API. Android's WebView exposes that API but always reports height 0, so
  // replace it with one fed the real keyboard height by the app.
  let kb = 0;
  let overlays = false;
  const vk = new EventTarget();
  Object.defineProperties(vk, {
    overlaysContent: { get: () => overlays, set: (v) => { overlays = !!v; } },
    boundingRect: { get: () => new DOMRect(0, window.innerHeight - kb, window.innerWidth, kb) },
  });
  vk.show = () => {};
  vk.hide = () => {};
  try {
    Object.defineProperty(navigator, "virtualKeyboard", { value: vk, configurable: true });
  } catch (e) { /* leave the native one */ }

  // --- Hide SilverBullet's top-right buttons (the native bar replaces them) -------------------
  // On by default so they never flash; the app turns it off if the user prefers them.
  const hideCss = document.createElement("style");
  hideCss.textContent = "html.sb-android-hide-actions #sb-top .sb-actions { display: none !important; }";
  (document.head || document.documentElement).appendChild(hideCss);
  document.documentElement.classList.add("sb-android-hide-actions");

  // --- Sidebar behaves like an Android drawer -------------------------------------------------
  // On phones SilverBullet overlays the docked sidebar without a backdrop, so a tap beside it lands
  // in the note (focusing the editor and opening the keyboard) while the sidebar stays open. Make
  // that first tap only close the sidebar, swallowing every event of the gesture.
  function openDrawer() {
    for (const side of ["left", "right"]) {
      const btn = document.querySelector(`#sb-top .sb-mobile-dock-button.sb-mobile-dock-${side}[aria-expanded="true"]`);
      if (!btn || getComputedStyle(btn).display === "none") continue; // wide screen: real sidebar
      const panel = document.querySelector(`.sb-nav-root-${side === "left" ? "lhs" : "rhs"}`);
      if (panel) return { btn, panel };
    }
    return null;
  }
  let swallowing = false;
  let swallowUntil = 0;
  function drawerGuard(e) {
    const starts = e.type === "pointerdown" || e.type === "touchstart" || e.type === "mousedown";
    if (!swallowing || (starts && Date.now() > swallowUntil)) {
      swallowing = false;
      if (!starts) return;
      const d = openDrawer();
      if (!d) return;
      const t = e.target;
      if (d.panel.contains(t) || d.btn.contains(t)) return;
      if (t.closest && t.closest(".sb-modal, .sb-modal-backdrop")) return; // e.g. a picker on top
      d.btn.click(); // close first: this synthetic click must not be swallowed
      swallowing = true;
      swallowUntil = Date.now() + 1500; // safety net if the end of the gesture never arrives
    }
    if (e.type === "touchend" || e.type === "pointerup" || e.type === "touchcancel") {
      swallowUntil = Date.now() + 400; // still catch the emulated mouse events and click
    }
    e.preventDefault();
    e.stopImmediatePropagation();
  }
  for (const type of ["pointerdown", "touchstart", "mousedown", "pointerup", "touchend",
                      "touchcancel", "mouseup", "click"]) {
    window.addEventListener(type, drawerGuard, { capture: true, passive: false });
  }

  // --- Actions for the native bottom bar ------------------------------------------------------
  function commandNames(c) {
    try { return [...c.ui.viewState.commands.keys()]; } catch (e) { return []; }
  }
  function tryRun(c, name) {
    return Promise.resolve(c.runCommandByName(name)).catch((e) => console.warn("[sbAndroid]", name, e));
  }
  const actions = {
    sidebar(c) {
      // Prefer SilverBullet's own dock toggle; it only exists once a view has been docked left.
      const btn = document.querySelector("#sb-top .sb-mobile-dock-button.sb-mobile-dock-left");
      if (btn) { btn.click(); return; }
      // Otherwise open the view the space configures for the left dock (or the built-in tree).
      let defaults = {};
      try { defaults = c.config.get("view.defaults", {}) || {}; } catch (e) {}
      const name = Object.keys(defaults).find((k) => defaults[k] && defaults[k].dock === "lhs" && defaults[k].open !== false);
      c.openNavigatorView(name || "std.spaceTree");
    },
    back() { history.back(); },
    home(c) { tryRun(c, "Navigate: Home"); },
    openpage(c) { tryRun(c, "Navigate: Page Picker"); },
    readonly(c) { tryRun(c, "Editor: Toggle Read Only Mode").then(reportTheme); },
    search(c) {
      // Use a full-text search plug if installed (e.g. "Silversearch: Search"), else the page picker.
      const s = commandNames(c).find((k) => /^[^:]+:\s*search( space)?$/i.test(k));
      if (s) tryRun(c, s); else c.startPageNavigate();
    },
    journal(c) { tryRun(c, "Journal: Today"); },
    commands(c) { c.startCommandPalette(); },
  };

  // --- Theme reporting ------------------------------------------------------------------------
  // Tell the app SilverBullet's top-bar colours so the system bars and bottom bar match.
  let lastTheme = "";
  function reportTheme() {
    const top = document.getElementById("sb-top");
    if (!top || !window.SBAndroidNative) return;
    const cs = getComputedStyle(top);
    const page = getComputedStyle(document.getElementById("sb-main") || document.body);
    let ro = false;
    try { ro = !!window.client.ui.viewState.uiOptions.forcedROMode; } catch (e) {}
    const msg = JSON.stringify({ type: "theme", bar: cs.backgroundColor, fg: cs.color, page: page.backgroundColor, readOnly: ro });
    if (msg !== lastTheme) { lastTheme = msg; window.SBAndroidNative.postMessage(msg); }
  }
  setInterval(reportTheme, 1000);

  window.__sbAndroid = {
    setKeyboard(px) {
      if (px === kb) return;
      kb = px;
      vk.dispatchEvent(new Event("geometrychange"));
    },
    run(name) {
      const c = window.client;
      if (!c || !actions[name]) return false;
      try { actions[name](c); return true; } catch (e) { console.warn("[sbAndroid]", name, e); return false; }
    },
    reportTheme,
    setHideTopActions(on) {
      document.documentElement.classList.toggle("sb-android-hide-actions", !!on);
    },
    // Android back: close whatever SilverBullet has open (panel, picker, prompt, sidebar) first.
    // Uses SilverBullet's own controls only. Never synthesise key events: SilverBullet treats a
    // keydown with no on-screen keyboard as a hardware keyboard and then docks its keyboard bar.
    back() {
      const c = window.client;
      if (!c || !c.ui) return false;
      const vs = c.ui.viewState || {};
      const dispatch = (action) => { c.ui.viewDispatch(action); return true; };
      // Plug panels (e.g. Silversearch) live in an iframe.
      if (vs.panels && vs.panels.modal && vs.panels.modal.mode !== undefined) {
        return dispatch({ type: "hide-panel", id: "modal" });
      }
      // Navigator pickers (page picker, command palette, …) have a close button.
      const navClose = document.querySelector(".sb-nav-root-modal .sb-nav-close");
      if (navClose) { navClose.click(); return true; }
      // Prompts and confirms: press Cancel so whoever asked gets an answer.
      if (vs.showPrompt || vs.showConfirm) {
        const cancel = [...document.querySelectorAll(".sb-modal button")]
          .find((b) => /^\s*(cancel|no)\s*$/i.test(b.textContent || ""));
        if (cancel) { cancel.click(); return true; }
        return dispatch({ type: vs.showPrompt ? "hide-prompt" : "hide-confirm" });
      }
      if (vs.showFilterBox) return dispatch({ type: "hide-filterbox" });
      // Docked sidebars.
      for (const side of ["left", "right"]) {
        const btn = document.querySelector("#sb-top .sb-mobile-dock-button.sb-mobile-dock-" + side + "[aria-expanded=true]");
        if (btn) { btn.click(); return true; }
      }
      return false;
    },
  };
})();
