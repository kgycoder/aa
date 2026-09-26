/*
 * LM Arena Android - 페이지 내부 자동화 라이브러리
 *
 * 원본(Windows) 프로그램은 Playwright 로케이터로 arena.ai DOM을 조작했다.
 * Android에는 Playwright가 없으므로 같은 판정/파싱 로직을 이 파일 안의 JS로 옮겨
 * WebView 안에서 직접 실행한다. (browser.py / model_selector.py / agent_mode.py /
 * captcha.py / dom_selectors.py 의 동작을 그대로 따른다.)
 *
 * 모든 공개 함수는 window.__lma 아래에 있으며, Java(ArenaBrowser)는
 *   JSON.stringify(window.__lma.<fn>(args))
 * 형태로 호출한다. 페이지가 새로 로드되면 Java가 이 파일을 다시 주입한다.
 */
(function () {
  if (window.__lma) return;
  var L = {};
  window.__lma = L;

  // ══════════════════════════════════════════════════════════════
  // 셀렉터 정의 (dom_selectors.py 이식)
  //   문자열 -> 순수 CSS
  //   {css, text} -> CSS로 찾은 뒤 텍스트(대소문자 무시 부분일치)로 거름 (Playwright :has-text)
  //   {smallest: text} -> 텍스트를 포함하는 가장 안쪽 요소 (Playwright text=)
  // ══════════════════════════════════════════════════════════════
  var SEL = {
    SIGN_IN: [
      { css: "button", text: "Log in" }, { css: "a", text: "Log in" },
      { css: "button", text: "Sign in" }, { css: "a", text: "Sign in" },
      { css: "button", text: "Sign up" }, { css: "a", text: "Sign up" },
      { smallest: "Log in" }, { smallest: "Sign in" },
      "[data-testid='login-button']", "[data-testid='sign-in-button']"
    ],
    ACCOUNT_MENU: [
      "[data-testid='user-avatar']", "[data-testid='account-menu']",
      "button[aria-label='Account']", "button[aria-label='User menu']"
    ],
    LOGGED_IN: [
      "div.ProseMirror[contenteditable='true']", "[contenteditable='true'][translate='no']",
      "div[contenteditable='true']", "textarea[name='message']", "textarea[placeholder]",
      "[data-testid='chat-input']"
    ],
    PROMPT: [
      "div.ProseMirror[contenteditable='true']", "div[contenteditable='true'][translate='no']",
      "div[contenteditable='true']:has(p[data-placeholder])", "[role='textbox'][contenteditable='true']",
      "div[contenteditable='true']", "textarea[name='message']", "textarea[placeholder='Ask anything…']",
      "textarea[placeholder]", "textarea.body-base", "[data-testid='chat-input']"
    ],
    SEND: [
      "button[type='submit']", "button[aria-label='Send message']",
      "button[data-testid='send-button']", "form button:has(svg)"
    ],
    GENERATING: [
      "button:has(svg rect[x='6'][y='6'][width='12'][height='12'])",
      "svg:has(rect[x='6'][y='6'][width='12'][height='12'])",
      "button:has(svg rect[width='12'][height='12'])",
      "button[aria-label='Stop generating']", "button[data-testid='stop-button']",
      { css: "button", text: "Stop" }
    ],
    STOP: [
      "button[aria-label='Stop generation']",
      "button:has(svg rect[x='6'][y='6'][width='12'][height='12'])",
      "svg:has(rect[x='6'][y='6'][width='12'][height='12'])",
      "button:has(svg rect[width='12'][height='12'])",
      "button[aria-label='Stop generating']", "button[data-testid='stop-button']"
    ],
    RESPONSE: ["div.prose[class*='prose-base']", "div.prose", "[data-testid='message-content']"],
    CODE_CONTAINER: ["div[data-code-block='true']", "div.not-prose:has(pre)", "pre"],
    CODE_LANG: ["span.text-text-secondary.text-sm.font-medium", "span[class*='text-secondary']", "div > span:first-child"],
    CODE_CODE: ["code", "pre code", "pre"],
    REASONING: [
      "div.font-mono.text-xs.leading-relaxed",
      "div[class*='font-mono'][class*='text-xs'][class*='leading-relaxed']",
      "div.space-y-4.whitespace-normal.size-full"
    ],
    NEW_CHAT: [
      { css: "a[data-sidebar='menu-button'][href='/text']", text: "New Chat" },
      { css: "a[href='/text']", text: "New Chat" },
      { css: "[data-sidebar='menu-button']", text: "New Chat" },
      { css: "a", text: "New Chat" }
    ],
    AGENT_NEW_CHAT: [
      { css: "a[data-sidebar='menu-button'][href^='/agent']", text: "New Chat" },
      { css: "a[href^='/agent']", text: "New Chat" },
      { css: "[data-sidebar='menu-button']", text: "New Chat" },
      { css: "a", text: "New Chat" }
    ],
    MODEL_BUTTON: [
      "button[aria-haspopup='dialog']:has(span.flex-1.truncate.text-left)",
      "button[aria-haspopup='dialog']:has(svg.size-4.flex-none):has(span.truncate)",
      "button[aria-haspopup='dialog'][aria-controls]:has(span.truncate)"
    ],
    MODEL_SEARCH: ["input[cmdk-input]", "input[placeholder='Search models']", "input[role='combobox'][placeholder*='Search']"],
    MODEL_ITEM: ["[cmdk-item]", "[role='option'][data-value]", "[role='option']"],
    AGENT_CODE_CONTAINER: [
      "div[role='button'][aria-pressed][class*='artifact']",
      "div[role='button'][aria-pressed]:has(div[class*='whitespace-pre-wrap'][class*='font-mono'])",
      "div[class*='artifact']:has(div[class*='whitespace-pre-wrap'])",
      "div[data-code-block='true']", "div.not-prose:has(pre)"
    ],
    AGENT_CODE_NAME: ["span[class*='flex-1'][class*='truncate']", "span[class*='min-w-0'][class*='truncate']", "span.truncate"],
    AGENT_CODE_LANG: ["span[class*='uppercase']", "span[class*='text-xs'][class*='border']"],
    AGENT_CODE_CODE: [
      "div[class*='whitespace-pre-wrap'][class*='font-mono']", "div[class*='font-mono'][class*='text-xs']",
      "pre code", "pre"
    ],
    AGENT_FOLDER: ["button[aria-label$=' folder']", "button[aria-label*='folder']"],
    AGENT_FILE: ["[role='button'][aria-label$=' file']", "[role='button'][aria-label*=' file']"],
    AGENT_DOWNLOAD: [
      "a[download$='.zip']", "button[aria-label*='Download workspace' i]", "a[aria-label*='Download workspace' i]",
      "button[aria-label*='Download all' i]",
      "button:has(svg path[d='M12 4V16M12 16L15.5 12.5M12 16L8.5 12.5'])",
      "a:has(svg path[d='M12 4V16M12 16L15.5 12.5M12 16L8.5 12.5'])",
      "button[aria-label*='Download' i]", "a[aria-label*='Download' i]", "a[download]"
    ],
    AGENT_REVIEW_CLOSE: ["button[aria-label='Close review panel']", "button[aria-label*='Close review' i]"],
    LOGIN_MODAL: [
      "[role='dialog']", "[data-radix-portal]", "dialog", ".cl-modalContent", ".cl-rootBox",
      "#auth0-lock-container", "[data-testid='login-modal']", "[data-testid='auth-modal']",
      "iframe[src*='accounts.google.com']"
    ],
    GOOGLE: [
      { css: "button", text: "Continue with Google" }, { css: "button", text: "Sign in with Google" },
      { css: "a", text: "Continue with Google" }, { smallest: "Continue with Google" },
      "button:has(svg path[fill='#4285F4'])"
    ],
    PRIVACY: [
      "button[aria-label='Privacy and legal']", "button[aria-label*='Privacy and legal' i]",
      "li[data-sidebar='menu-item'] button[aria-label*='Privacy' i]"
    ],
    CAPTCHA_DIALOG: "[role='dialog']",
    CAPTCHA_MARKERS: [
      ".recaptcha-v2-container", "#recaptcha-v2-container", "iframe[src*='recaptcha']",
      "iframe[title='reCAPTCHA']", "textarea.g-recaptcha-response"
    ],
    CAPTCHA_TEXT: "Security Verification|Protected by reCAPTCHA"
  };
  var REVIEW_CHOICES = {
    yes: ["예", "Yes"],
    no: ["아니요", "아니오", "No"],
    "continue": ["계속 작업하기", "계속 작업", "Keep working", "Continue working", "Continue"]
  };

  // ══════════════════════════════════════════════════════════════
  // 공통 헬퍼
  // ══════════════════════════════════════════════════════════════
  function norm(s) { return String(s == null ? "" : s).replace(/\s+/g, " ").trim(); }
  function lower(s) { return norm(s).toLowerCase(); }

  function isVisible(el) {
    try {
      if (!el || !el.isConnected) return false;
      var r = el.getBoundingClientRect();
      if (r.width <= 0 || r.height <= 0) return false;
      var cs = getComputedStyle(el);
      if (cs.visibility === "hidden" || cs.display === "none") return false;
      return true;
    } catch (e) { return false; }
  }

  function qAll(css, root) {
    try { return Array.prototype.slice.call((root || document).querySelectorAll(css)); }
    catch (e) { return []; }
  }

  function smallestByText(text, root) {
    var t = lower(text), out = [], seen = new Set();
    var base = root || document.body;
    if (!base) return out;
    var w = document.createTreeWalker(base, NodeFilter.SHOW_TEXT, null);
    var n;
    while ((n = w.nextNode())) {
      var s = lower(n.nodeValue);
      if (s.indexOf(t) < 0) continue;
      var p = n.parentElement;
      if (!p || seen.has(p)) continue;
      if (/^(script|style|noscript|template)$/i.test(p.tagName)) continue;
      seen.add(p);
      out.push(p);
    }
    return out;
  }

  function resolveSpec(spec, root) {
    if (typeof spec === "string") return qAll(spec, root);
    if (spec.smallest) return smallestByText(spec.smallest, root);
    if (spec.css) {
      var t = lower(spec.text || "");
      var els = qAll(spec.css, root);
      if (!t) return els;
      return els.filter(function (e) { return lower(e.textContent).indexOf(t) >= 0; });
    }
    return [];
  }

  function firstMatch(specs, root) {
    for (var i = 0; i < specs.length; i++) {
      var els = resolveSpec(specs[i], root);
      if (els.length > 0) return els;
    }
    return [];
  }

  function firstVisibleOfFirst(specs) {
    // Python: loc.count() > 0 and loc.first.is_visible()  (선택자마다 first만 검사)
    for (var i = 0; i < specs.length; i++) {
      var els = resolveSpec(specs[i]);
      if (els.length > 0 && isVisible(els[0])) return els[0];
    }
    return null;
  }

  function fireMouse(el, type, extra) {
    var r = el.getBoundingClientRect();
    var init = {
      bubbles: true, cancelable: true, view: window, button: 0,
      clientX: r.left + r.width / 2, clientY: r.top + r.height / 2
    };
    for (var k in (extra || {})) init[k] = extra[k];
    try { el.dispatchEvent(new MouseEvent(type, init)); } catch (e) {}
  }
  function firePointer(el, type, extra) {
    var r = el.getBoundingClientRect();
    var init = {
      bubbles: true, cancelable: true, view: window, button: 0, pointerId: 1,
      pointerType: "mouse", isPrimary: true,
      clientX: r.left + r.width / 2, clientY: r.top + r.height / 2
    };
    for (var k in (extra || {})) init[k] = extra[k];
    try { el.dispatchEvent(new PointerEvent(type, init)); } catch (e) {}
  }

  // 실제 마우스 클릭과 같은 이벤트 순서를 재현한다(Radix/React 컴포넌트는 pointerdown에 반응).
  function realClick(el) {
    try { el.scrollIntoView({ block: "center", inline: "center" }); } catch (e) {}
    firePointer(el, "pointerover"); firePointer(el, "pointerenter", { bubbles: false });
    fireMouse(el, "mouseover"); fireMouse(el, "mouseenter", { bubbles: false });
    firePointer(el, "pointermove"); fireMouse(el, "mousemove");
    firePointer(el, "pointerdown", { buttons: 1 }); fireMouse(el, "mousedown", { buttons: 1 });
    try { if (el.focus) el.focus({ preventScroll: true }); } catch (e) {}
    firePointer(el, "pointerup", { buttons: 0 }); fireMouse(el, "mouseup", { buttons: 0 });
    fireMouse(el, "click", { buttons: 0, detail: 1 });
  }

  function pressKeyOn(target, key, code, keyCode) {
    ["keydown", "keypress", "keyup"].forEach(function (type) {
      var ev = new KeyboardEvent(type, {
        key: key, code: code, bubbles: true, cancelable: true, composed: true
      });
      try {
        Object.defineProperty(ev, "keyCode", { get: function () { return keyCode; } });
        Object.defineProperty(ev, "which", { get: function () { return keyCode; } });
        Object.defineProperty(ev, "charCode", { get: function () { return type === "keypress" ? keyCode : 0; } });
      } catch (e) {}
      try { target.dispatchEvent(ev); } catch (e) {}
    });
  }

  // ══════════════════════════════════════════════════════════════
  // 페이지 안정화 훅 (백그라운드에서도 사이트가 멈추지 않도록)
  // ══════════════════════════════════════════════════════════════
  L.installHooks = function () {
    if (window.__lmaHooks) return true;
    window.__lmaHooks = true;
    try {
      Object.defineProperty(document, "hidden", { configurable: true, get: function () { return false; } });
      Object.defineProperty(document, "visibilityState", { configurable: true, get: function () { return "visible"; } });
      document.addEventListener("visibilitychange", function (e) { e.stopImmediatePropagation(); }, true);
      window.addEventListener("pagehide", function (e) { e.stopImmediatePropagation(); }, true);
    } catch (e) {}
    try {
      // 보이지 않는 WebView에서는 rAF가 멈추므로 타이머 기반으로 대체한다.
      window.requestAnimationFrame = function (cb) {
        return setTimeout(function () { try { cb(performance.now()); } catch (e) {} }, 16);
      };
      window.cancelAnimationFrame = function (id) { clearTimeout(id); };
    } catch (e) {}
    try { installDownloadHook(); } catch (e) {}
    return true;
  };

  // ── 다운로드 가로채기 (blob: 링크/앵커 download → 네이티브로 전달) ──
  function bytesToB64(buf) {
    var bin = "", bytes = new Uint8Array(buf), CH = 0x8000;
    for (var i = 0; i < bytes.length; i += CH) {
      bin += String.fromCharCode.apply(null, bytes.subarray(i, i + CH));
    }
    return btoa(bin);
  }
  function sendToNative(name, buf, id) {
    var bridge = window.LmaArena;
    if (!bridge) return;
    var CHUNK = 3 * 256 * 1024; // 3의 배수(base64 경계)
    var bytes = new Uint8Array(buf);
    var total = bytes.length;
    if (total === 0) { bridge.onDownloadChunk(id, name || "download.bin", "", true); return; }
    for (var off = 0; off < total; off += CHUNK) {
      var part = bytes.subarray(off, Math.min(total, off + CHUNK));
      bridge.onDownloadChunk(id, name || "download.bin", bytesToB64(part), off + CHUNK >= total);
    }
  }
  function grabUrl(url, name) {
    var id = "d" + Date.now() + "_" + Math.floor(Math.random() * 1e6);
    fetch(url, { credentials: "include" }).then(function (r) {
      if (!r.ok) throw new Error("HTTP " + r.status);
      var cd = r.headers.get("content-disposition") || "";
      var m = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(cd);
      if (m) { try { name = decodeURIComponent(m[1]); } catch (e) { name = m[1]; } }
      return r.arrayBuffer();
    }).then(function (buf) {
      sendToNative(name || "workspace.zip", buf, id);
    }).catch(function (err) {
      if (window.LmaArena) window.LmaArena.onDownloadError(id, String(err));
    });
  }
  function installDownloadHook() {
    var origClick = HTMLAnchorElement.prototype.click;
    function shouldCapture(a) {
      if (!window.__lmaCapture) return false;
      var href = a.href || "";
      return !!(a.hasAttribute("download") || /^blob:/i.test(href) || /\.zip(\?|$)/i.test(href));
    }
    HTMLAnchorElement.prototype.click = function () {
      if (shouldCapture(this)) { grabUrl(this.href, this.getAttribute("download") || ""); return; }
      return origClick.apply(this, arguments);
    };
    document.addEventListener("click", function (e) {
      var a = e.target && e.target.closest ? e.target.closest("a") : null;
      if (a && shouldCapture(a)) {
        e.preventDefault(); e.stopPropagation();
        grabUrl(a.href, a.getAttribute("download") || "");
      }
    }, true);
  }
  L.setCapture = function (on) { window.__lmaCapture = !!on; return !!on; };

  // ══════════════════════════════════════════════════════════════
  // 상태 / 로그인 판정 (browser.is_logged_in)
  // ══════════════════════════════════════════════════════════════
  var LANDING = /experience\s+the\s+frontier/i;

  function landingHeadlineVisible() {
    var hs = document.querySelectorAll("h1");
    for (var i = 0; i < hs.length; i++) {
      if (!isVisible(hs[i])) continue;
      if (LANDING.test(norm(hs[i].innerText))) return true;
    }
    return false;
  }
  function signInVisible() { return !!firstVisibleOfFirst(SEL.SIGN_IN); }

  L.pageInfo = function () {
    return { url: location.href, title: document.title, ready: document.readyState, textLen: document.body ? document.body.innerText.length : 0, nodes: document.getElementsByTagName("*").length };
  };

  L.isLoggedIn = function () {
    try {
      if (landingHeadlineVisible()) return { ok: false, why: "landing" };
      if (signInVisible()) return { ok: false, why: "signin" };
      var acc = firstVisibleOfFirst(SEL.ACCOUNT_MENU);
      if (acc) return { ok: true, why: "account" };
      for (var i = 0; i < SEL.LOGGED_IN.length; i++) {
        if (qAll(SEL.LOGGED_IN[i]).length > 0) return { ok: true, why: "input" };
      }
      return { ok: false, why: "unknown" };
    } catch (e) { return { ok: false, why: "error:" + e }; }
  };

  // ── 로그인 유도 ───────────────────────────────────────────────
  L.signInPresent = function () { return firstMatch(SEL.SIGN_IN).length > 0; };
  L.clickSignIn = function () {
    var els = firstMatch(SEL.SIGN_IN);
    if (!els.length) return false;
    realClick(els[0]);
    return true;
  };
  L.nudgePrivacy = function () {
    var els = firstMatch(SEL.PRIVACY);
    if (!els.length || !isVisible(els[0])) return false;
    realClick(els[0]);
    return true;
  };
  L.googleVisible = function () {
    var els = firstMatch(SEL.GOOGLE);
    return els.length > 0 && isVisible(els[0]);
  };
  L.clickGoogle = function () {
    var els = firstMatch(SEL.GOOGLE);
    if (!els.length || !isVisible(els[0])) return false;
    realClick(els[0]);
    return true;
  };
  L.loginModalVisible = function () {
    for (var i = 0; i < SEL.LOGIN_MODAL.length; i++) {
      var els = qAll(SEL.LOGIN_MODAL[i]);
      if (els.length > 0 && isVisible(els[0])) return true;
    }
    return false;
  };
  var LOGIN_STYLE_ID = "lmarena-cli-login-hide-style";
  L.hideBackground = function () {
    if (document.getElementById(LOGIN_STYLE_ID)) return true;
    var modal = SEL.LOGIN_MODAL.join(", ");
    var style = document.createElement("style");
    style.id = LOGIN_STYLE_ID;
    style.textContent =
      "body > *:not(script):not(style) { visibility: hidden !important; }" +
      modal + " { visibility: visible !important; }" +
      modal.split(", ").map(function (s) { return s + " *"; }).join(", ") + " { visibility: visible !important; }" +
      "body { background: #0b0b0f !important; }";
    document.head.appendChild(style);
    return true;
  };
  L.showBackground = function () {
    var el = document.getElementById(LOGIN_STYLE_ID);
    if (el) el.remove();
    return true;
  };

  // ══════════════════════════════════════════════════════════════
  // 새 채팅 / 모드 관련
  // ══════════════════════════════════════════════════════════════
  L.clickNewChat = function (agent) {
    var els = firstMatch(agent ? SEL.AGENT_NEW_CHAT : SEL.NEW_CHAT);
    if (!els.length) return false;
    realClick(els[0]);
    return true;
  };

  // ══════════════════════════════════════════════════════════════
  // 텍스트 정규화 / 에코 판정 (browser.py 이식)
  // ══════════════════════════════════════════════════════════════
  function normEcho(text) {
    var t = String(text == null ? "" : text);
    try { t = t.normalize("NFKC"); } catch (e) {}
    t = t.replace(/[\u200b\u200c\u200d\u2060\ufeff]/g, "");
    t = t.replace(/[\u2018\u2019\u201a\u2032]/g, "'")
         .replace(/[\u201c\u201d\u201e\u2033]/g, '"')
         .replace(/[\u2013\u2014\u2212]/g, "-")
         .replace(/\u2026/g, "...")
         .replace(/\u00a0/g, " ");
    return t.split(/\s+/).filter(Boolean).join(" ").trim();
  }
  var MD_FENCE = /```[^\n`]*\n?|```/g;
  var MD_INLINE = /`([^`]*)`/g;
  var MD_IMG = /!?\[([^\]]*)\]\([^)]*\)/g;
  var MD_HEADER = /^[ \t]{0,3}#{1,6}[ \t]*/gm;
  var MD_QUOTE = /^[ \t]{0,3}>[ \t]?/gm;
  var MD_LIST = /^[ \t]*(?:[-*+]|\d+[.)])[ \t]+/gm;
  var MD_HR = /^[ \t]{0,3}([-*_])(?:[ \t]*\1){2,}[ \t]*$/gm;
  var MD_EMPH = /(\*\*\*|\*\*|\*|___|__|_)/g;
  function stripMd(t) {
    t = t.replace(MD_FENCE, "");
    t = t.replace(MD_IMG, "$1");
    t = t.replace(MD_INLINE, "$1");
    t = t.replace(MD_HEADER, "");
    t = t.replace(MD_QUOTE, "");
    t = t.replace(MD_LIST, "");
    t = t.replace(MD_HR, "");
    t = t.replace(MD_EMPH, "");
    return t;
  }
  function promptForEcho(p) { return normEcho(stripMd(p)); }

  function quickRatio(a, b) {
    var total = a.length + b.length;
    if (!total) return 1.0;
    var counts = new Map(), i;
    for (i = 0; i < b.length; i++) { var c = b.charCodeAt(i); counts.set(c, (counts.get(c) || 0) + 1); }
    var matches = 0;
    for (i = 0; i < a.length; i++) {
      var k = a.charCodeAt(i), n = counts.get(k) || 0;
      if (n > 0) { matches++; counts.set(k, n - 1); }
    }
    return 2.0 * matches / total;
  }
  function looksLikeEcho(cand, prompt) {
    if (!cand || !prompt) return false;
    if (cand.length >= 40 && prompt.indexOf(cand) === 0) return true;
    if (cand.length >= 40 || prompt.length >= 40) {
      if (quickRatio(cand, prompt) >= 0.95) return true;
    }
    return false;
  }

  // ── 턴 시작: 전송 직전 스냅샷(baseline) ────────────────────────
  var T = { baseline: new Set(), reasoningBaseline: new Set(), np: "", agent: false, ready: false };
  L.T = T;

  function responseContainers() { return firstMatch(SEL.RESPONSE); }
  function reasoningNodes() { return firstMatch(SEL.REASONING); }

  // prompt 자체는 인자로 받지 않는다: 워크스페이스 공유 시 수십만 자에 이를 수 있어
  // evaluateJavascript 인자로 그대로 넣으면 Android WebView의 Binder IPC 크기 제한을
  // 넘을 수 있다. 대신 fillPrompt()와 동일하게 네이티브 브리지에서 "미리 꺼내보기"
  // (소비하지 않는) 방식으로 가져온다. 실제 소비(takePrompt)는 fillPrompt가 한다.
  L.beginTurn = function (agent) {
    T.baseline = new Set();
    T.reasoningBaseline = new Set();
    responseContainers().forEach(function (el) {
      var n = normEcho(el.innerText || "");
      if (n) T.baseline.add(n);
    });
    reasoningNodes().forEach(function (el) {
      var n = norm(el.innerText || "");
      if (n) T.reasoningBaseline.add(n);
    });
    var prompt = window.LmaArena ? window.LmaArena.peekPrompt() : "";
    T.np = promptForEcho(prompt || "");
    T.agent = !!agent;
    T.ready = true;
    return { baseline: T.baseline.size, reasoning: T.reasoningBaseline.size };
  };

  function findNewAnswer() {
    var els = responseContainers();
    var cands = [], echoSkipped = false;
    for (var i = 0; i < els.length; i++) {
      var text;
      try { text = els[i].innerText || ""; } catch (e) { continue; }
      var nt = normEcho(text);
      if (!nt) continue;
      if (T.baseline.has(nt)) continue;
      var isEcho = nt === T.np;
      if (!isEcho) isEcho = looksLikeEcho(nt, T.np);
      if (isEcho) {
        if (!echoSkipped) { echoSkipped = true; continue; }
      }
      cands.push({ len: nt.length, el: els[i] });
    }
    if (!cands.length) return null;
    cands.sort(function (a, b) { return b.len - a.len; });
    return cands[0].el;
  }

  function findNewReasoning() {
    var els = reasoningNodes();
    var cands = [];
    for (var i = 0; i < els.length; i++) {
      var t;
      try { t = els[i].innerText || ""; } catch (e) { continue; }
      var n = norm(t);
      if (!n) continue;
      if (T.reasoningBaseline.has(n)) continue;
      cands.push({ len: n.length, el: els[i], text: t.trim() });
    }
    if (!cands.length) return null;
    cands.sort(function (a, b) { return b.len - a.len; });
    return cands[0];
  }

  // ── 답변 컨테이너에서 (전체/일반/코드블록) 원자적 추출 ──────────
  function firstMatchAllIn(root, selectors) {
    for (var i = 0; i < selectors.length; i++) {
      var els = qAll(selectors[i], root);
      if (els.length > 0) return els;
    }
    return [];
  }
  function extract(node, agent) {
    var cSel = agent ? SEL.AGENT_CODE_CONTAINER : SEL.CODE_CONTAINER;
    var lSel = agent ? SEL.AGENT_CODE_LANG : SEL.CODE_LANG;
    var kSel = agent ? SEL.AGENT_CODE_CODE : SEL.CODE_CODE;
    var nSel = agent ? SEL.AGENT_CODE_NAME : [];
    var full = node.innerText || "";
    var containers = firstMatchAllIn(node, cSel);
    var blocks = containers.map(function (block) {
      var language = "text", i, el;
      for (i = 0; i < lSel.length; i++) {
        try { el = block.querySelector(lSel[i]); } catch (e) { el = null; }
        if (el && el.innerText && el.innerText.trim()) { language = el.innerText.trim(); break; }
      }
      var code = "";
      for (i = 0; i < kSel.length; i++) {
        try { el = block.querySelector(kSel[i]); } catch (e) { el = null; }
        if (el) { code = agent ? el.textContent : el.innerText; break; }
      }
      var filename = null;
      for (i = 0; i < nSel.length; i++) {
        try { el = block.querySelector(nSel[i]); } catch (e) { el = null; }
        if (el && el.innerText && el.innerText.trim()) { filename = el.innerText.trim(); break; }
      }
      return { language: language, code: code || "", filename: filename };
    });
    // 원본과 동일: 코드 블록을 복제본에서만 제거하고 남은 텍스트를 읽는다(원본 DOM은 건드리지 않음).
    var clone = node.cloneNode(true);
    for (var s = 0; s < cSel.length; s++) {
      try { clone.querySelectorAll(cSel[s]).forEach(function (el) { el.remove(); }); } catch (e) {}
    }
    var plain = clone.innerText;
    if (plain == null) plain = clone.textContent || "";
    return { full: full, plain: plain, codeBlocks: blocks };
  }

  function isGenerating() {
    return !!firstVisibleOfFirst(SEL.GENERATING);
  }
  L.isGenerating = function () { return isGenerating(); };

  // 한 번의 호출로 응답 상태 전체를 읽는다.
  //   opts: {reasoning: bool}
  L.poll = function (opts) {
    opts = opts || {};
    var r = { captcha: L.captchaSync(), generating: false, container: false, full_len: 0, plain: "", code_blocks: [], reasoning: null };
    if (!T.ready) { r.no_turn = true; return r; }
    var container = findNewAnswer();
    r.generating = isGenerating();
    if (container) {
      r.container = true;
      var ex = extract(container, T.agent);
      r.full_len = ex.full.length;
      r.plain = ex.plain.trim();
      r.code_blocks = ex.codeBlocks;
    } else if (opts.reasoning !== false) {
      var rc = findNewReasoning();
      if (rc) r.reasoning = rc.text;
    }
    return r;
  };

  // 최종 파싱(parse_last_response): 코드 블록은 비어 있지 않은 것만, 일반 텍스트는 모드별 방식.
  L.parseLast = function () {
    var out = { text_parts: [], code_blocks: [] };
    if (!T.ready) return out;
    var container = findNewAnswer();
    if (!container) return out;
    var ex = extract(container, T.agent);
    ex.codeBlocks.forEach(function (cb) {
      if ((cb.code || "").trim()) out.code_blocks.push(cb);
    });
    if (T.agent) {
      if (ex.plain.trim()) out.text_parts.push(ex.plain.trim());
      return out;
    }
    var remaining = ex.full;
    out.code_blocks.forEach(function (cb) { remaining = remaining.split(cb.code).join(""); });
    if (remaining.trim()) out.text_parts.push(remaining.trim());
    return out;
  };

  // ══════════════════════════════════════════════════════════════
  // 프롬프트 입력 / 전송
  // ══════════════════════════════════════════════════════════════
  function promptEl() {
    var els = firstMatch(SEL.PROMPT);
    return els.length ? els[0] : null;
  }
  function setNativeValue(el, value) {
    var proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    var d = Object.getOwnPropertyDescriptor(proto, "value");
    if (d && d.set) d.set.call(el, value); else el.value = value;
    el.dispatchEvent(new Event("input", { bubbles: true }));
    el.dispatchEvent(new Event("change", { bubbles: true }));
  }

  L.hasPrompt = function () { return !!promptEl(); };

  // 입력창 채우기. 긴 텍스트는 Java 브리지(LmaArena.takePrompt)로 받는다.
  L.fillPrompt = function () {
    var el = promptEl();
    if (!el) return { ok: false, error: "no_input" };
    var text = window.LmaArena ? window.LmaArena.takePrompt() : "";
    if (text == null) text = "";
    try { el.scrollIntoView({ block: "center" }); } catch (e) {}
    if (el.isContentEditable === true) {
      try { el.focus(); } catch (e) {}
      try {
        var range = document.createRange();
        range.selectNodeContents(el);
        var sel = window.getSelection();
        sel.removeAllRanges();
        sel.addRange(range);
      } catch (e) {}
      try {
        var dt = new DataTransfer();
        dt.setData("text/plain", text);
        el.dispatchEvent(new ClipboardEvent("paste", { clipboardData: dt, bubbles: true, cancelable: true }));
      } catch (e) {}
      var landed = (el.innerText || "").trim();
      if (!landed && text.trim()) {
        try {
          el.focus();
          document.execCommand("selectAll", false, null);
          document.execCommand("insertText", false, text);
        } catch (e) {}
        landed = (el.innerText || "").trim();
      }
      return { ok: !!landed || !text.trim(), landed: landed.length, kind: "editable" };
    }
    try { el.focus(); } catch (e) {}
    setNativeValue(el, text);
    return { ok: (el.value || "").length > 0 || !text.trim(), landed: (el.value || "").length, kind: "textarea" };
  };

  L.focusPrompt = function () {
    var el = promptEl();
    if (!el) return false;
    try { el.focus(); } catch (e) {}
    return true;
  };

  L.pressEnter = function () {
    var el = promptEl();
    if (!el) return false;
    try { el.focus(); } catch (e) {}
    pressKeyOn(el, "Enter", "Enter", 13);
    return true;
  };

  L.clickSend = function () {
    var els = firstMatch(SEL.SEND);
    if (!els.length) return false;
    for (var i = 0; i < els.length; i++) {
      if (isVisible(els[i]) && !els[i].disabled) { realClick(els[i]); return true; }
    }
    return false;
  };

  // 전송이 실제로 일어났는지 판정하는 신호들
  L.sendSignals = function () {
    var el = promptEl();
    var empty = true;
    if (el) {
      var v = el.isContentEditable ? (el.innerText || "") : (el.value || "");
      empty = v.trim().length === 0;
    }
    return { empty: empty, generating: isGenerating(), newAnswer: T.ready ? !!findNewAnswer() : false };
  };

  L.stopGeneration = function () {
    var els = firstMatch(SEL.STOP);
    if (!els.length) return false;
    var el = els[0];
    if (!isVisible(el)) return false;
    // svg 자체가 매칭된 경우 가장 가까운 button을 누른다.
    var btn = el.closest ? (el.closest("button") || el) : el;
    realClick(btn);
    return true;
  };

  // ══════════════════════════════════════════════════════════════
  // reCAPTCHA (captcha.py 이식)
  // ══════════════════════════════════════════════════════════════
  var CAPTCHA_STYLE_ID = "lmarena-cli-captcha-style";
  var CAPTCHA_COVER_ID = "lmarena-cli-captcha-cover";
  L.captchaSync = function () {
    try {
      var re = new RegExp(SEL.CAPTCHA_TEXT, "i");
      var found = null;
      var dialogs = document.querySelectorAll(SEL.CAPTCHA_DIALOG);
      for (var i = 0; i < dialogs.length; i++) {
        var el = dialogs[i];
        if (el.getAttribute("data-state") === "closed") continue;
        var isCaptcha = false;
        for (var m = 0; m < SEL.CAPTCHA_MARKERS.length; m++) {
          try { if (el.querySelector(SEL.CAPTCHA_MARKERS[m])) { isCaptcha = true; break; } } catch (e) {}
        }
        if (!isCaptcha && !re.test(el.textContent || "")) continue;
        var r = el.getBoundingClientRect();
        if (r.width < 2 || r.height < 2) continue;
        found = el;
        break;
      }
      if (!found) return false;
      var dz = parseInt(getComputedStyle(found).zIndex, 10);
      var z = isFinite(dz) ? dz - 1 : 100;
      var cover = document.getElementById(CAPTCHA_COVER_ID);
      if (!cover) {
        cover = document.createElement("div");
        cover.id = CAPTCHA_COVER_ID;
        document.body.appendChild(cover);
      }
      cover.setAttribute("style", "position:fixed;inset:0;background:#0b0b0f;pointer-events:none;z-index:" + z);
      return true;
    } catch (e) { return false; }
  };
  L.captchaClear = function () {
    var st = document.getElementById(CAPTCHA_STYLE_ID); if (st) st.remove();
    var cv = document.getElementById(CAPTCHA_COVER_ID); if (cv) cv.remove();
    return true;
  };

  // ══════════════════════════════════════════════════════════════
  // Agent 모드 (agent_mode.py 이식)
  // ══════════════════════════════════════════════════════════════
  L.readTree = function () {
    var rows = [], seen = new Set(), i, j, els, label, m;
    function pad(el) {
      var raw = (el.style && el.style.paddingLeft) || "";
      var n = parseFloat(raw);
      return isFinite(n) ? n : 0;
    }
    function push(el, kind, name) {
      if (!name || seen.has(el)) return;
      seen.add(el);
      rows.push({ kind: kind, name: name, pad: pad(el), top: el.getBoundingClientRect().top });
    }
    for (i = 0; i < SEL.AGENT_FOLDER.length; i++) {
      els = qAll(SEL.AGENT_FOLDER[i]);
      if (!els.length) continue;
      for (j = 0; j < els.length; j++) {
        label = els[j].getAttribute("aria-label") || "";
        m = label.match(/^(?:Expand|Collapse)\s+(.*?)\s+folder$/i) || label.match(/^(.*?)\s+folder$/i);
        push(els[j], "dir", (m ? m[1] : label).trim());
      }
      break;
    }
    for (i = 0; i < SEL.AGENT_FILE.length; i++) {
      els = qAll(SEL.AGENT_FILE[i]);
      if (!els.length) continue;
      for (j = 0; j < els.length; j++) {
        label = els[j].getAttribute("aria-label") || "";
        m = label.match(/^(.*?)\s+file$/i);
        push(els[j], "file", (m ? m[1] : label).trim());
      }
      break;
    }
    rows.sort(function (a, b) { return a.top - b.top; });
    var padSet = {};
    rows.forEach(function (r) { padSet[r.pad] = true; });
    var pads = Object.keys(padSet).map(Number).sort(function (a, b) { return a - b; });
    return rows.map(function (r) {
      return { kind: r.kind, name: r.name, depth: Math.max(0, pads.indexOf(r.pad)) };
    });
  };

  // workspace 전체 다운로드 버튼(개별 파일 행 안의 링크는 제외)에 표식을 달고 정보를 돌려준다.
  L.markWorkspaceDownload = function () {
    var old = document.querySelectorAll("[data-lma-dl]");
    for (var k = 0; k < old.length; k++) old[k].removeAttribute("data-lma-dl");
    for (var i = 0; i < SEL.AGENT_DOWNLOAD.length; i++) {
      var els = qAll(SEL.AGENT_DOWNLOAD[i]).slice(0, 30);
      for (var j = 0; j < els.length; j++) {
        var el = els[j];
        if (!isVisible(el)) continue;
        var inRow = false;
        try { inRow = !!el.closest("[role='button'][aria-label$=' file']"); } catch (e) {}
        if (inRow) continue;
        el.setAttribute("data-lma-dl", "1");
        return {
          found: true, tag: el.tagName.toLowerCase(),
          href: el.href || el.getAttribute("href") || "",
          download: el.getAttribute("download") || ""
        };
      }
    }
    return { found: false };
  };
  L.clickMarkedDownload = function () {
    var el = document.querySelector("[data-lma-dl]");
    if (!el) return false;
    realClick(el);
    return true;
  };
  L.fetchMarkedDownload = function () {
    var el = document.querySelector("[data-lma-dl]");
    if (!el) return false;
    var href = el.href || el.getAttribute("href");
    if (!href) return false;
    grabUrl(href, el.getAttribute("download") || "workspace.zip");
    return true;
  };

  function reviewPanel() {
    var i, els;
    var css = [
      "div:has(> div > div > button[aria-label='Close review panel'])",
      "div:has(button[aria-label='Close review panel'])"
    ];
    for (i = 0; i < css.length; i++) {
      els = qAll(css[i]);
      if (els.length > 0 && isVisible(els[0])) return els[0];
    }
    // div:has(> div > span:text-matches('이 작업이 성공|Did this task succeed', 'i'))
    var re = /이 작업이 성공|Did this task succeed/i;
    var spans = qAll("span");
    for (i = 0; i < spans.length; i++) {
      if (!re.test(spans[i].textContent || "")) continue;
      var p = spans[i].parentElement && spans[i].parentElement.parentElement;
      if (p && isVisible(p)) return p;
    }
    return null;
  }
  L.reviewPending = function () { return !!reviewPanel(); };

  function reviewButtons(panel) {
    var btns = qAll("button:has(span)", panel);
    if (!btns.length) btns = qAll("button", panel);
    return btns;
  }
  function labelOfBtn(b) {
    return b.getAttribute("aria-label") ? "" : norm(b.innerText || "");
  }
  L.readReviewOptions = function () {
    var panel = reviewPanel();
    if (!panel) return [];
    var labels = [];
    reviewButtons(panel).forEach(function (b) {
      var t = labelOfBtn(b);
      if (t && labels.indexOf(t) < 0) labels.push(t);
    });
    var out = [];
    Object.keys(REVIEW_CHOICES).forEach(function (choice) {
      var cands = REVIEW_CHOICES[choice];
      var match = null, i, j;
      for (i = 0; i < labels.length && !match; i++) {
        for (j = 0; j < cands.length; j++) {
          if (lower(labels[i]) === lower(cands[j])) { match = labels[i]; break; }
        }
      }
      for (i = 0; i < labels.length && !match; i++) {
        for (j = 0; j < cands.length; j++) {
          if (lower(labels[i]).indexOf(lower(cands[j])) >= 0) { match = labels[i]; break; }
        }
      }
      if (match) out.push({ choice: choice, label: match });
    });
    return out;
  };
  L.submitReview = function (choice) {
    var cands = REVIEW_CHOICES[String(choice || "").toLowerCase()];
    if (!cands) return { ok: false, error: "알 수 없는 평가 선택지입니다: " + choice };
    var panel = reviewPanel();
    if (!panel) return { ok: false, error: "평가 패널이 화면에 없습니다 (이미 처리되었을 수 있습니다)." };
    var btns = reviewButtons(panel);
    for (var pass = 0; pass < 2; pass++) {
      for (var i = 0; i < btns.length; i++) {
        var t = lower(labelOfBtn(btns[i]));
        if (!t) continue;
        var hit = false;
        for (var j = 0; j < cands.length; j++) {
          if (pass === 0 ? lower(cands[j]) === t : t.indexOf(lower(cands[j])) >= 0) { hit = true; break; }
        }
        if (hit) { realClick(btns[i]); return { ok: true }; }
      }
    }
    return { ok: false, error: "평가 버튼을 찾지 못했습니다 (DOM 변경 가능성)." };
  };
  L.closeReview = function () {
    var panel = reviewPanel();
    if (!panel) return false;
    for (var i = 0; i < SEL.AGENT_REVIEW_CLOSE.length; i++) {
      var els = qAll(SEL.AGENT_REVIEW_CLOSE[i], panel);
      if (els.length > 0) { realClick(els[0]); return true; }
    }
    return false;
  };

  // ══════════════════════════════════════════════════════════════
  // 모델 선택 (model_selector.py 이식)
  // ══════════════════════════════════════════════════════════════
  var SVG_TAGS = new Set(["svg", "g", "path", "rect", "circle", "ellipse", "line", "polyline", "polygon",
    "defs", "clippath", "lineargradient", "radialgradient", "stop", "mask"]);
  var SVG_ATTRS = new Set(["viewbox", "width", "height", "x", "y", "cx", "cy", "r", "rx", "ry", "d", "points",
    "x1", "y1", "x2", "y2", "fill", "stroke", "stroke-width", "stroke-linecap", "stroke-linejoin",
    "stroke-miterlimit", "stroke-dasharray", "stroke-dashoffset", "opacity", "fill-opacity",
    "stroke-opacity", "fill-rule", "clip-rule", "transform", "id", "clip-path", "mask", "offset",
    "stop-color", "stop-opacity", "gradientunits", "gradienttransform", "fx", "fy", "clippathunits",
    "maskunits", "maskcontentunits", "preserveaspectratio"]);
  var COLOR_ATTRS = ["fill", "stroke", "stop-color"];
  var NEEDS_RESOLVE = /var\(|currentcolor/i;
  function esc(v) { return String(v).replace(/&/g, "&amp;").replace(/"/g, "&quot;").replace(/</g, "&lt;"); }
  function svgToString(el, isRoot) {
    var tag = el.localName;
    if (!SVG_TAGS.has(tag.toLowerCase())) return "";
    var cs = getComputedStyle(el);
    var attrs = {};
    Array.prototype.slice.call(el.attributes).forEach(function (a) {
      if (!SVG_ATTRS.has(a.name.toLowerCase())) return;
      if (/url\(/i.test(a.value) && !/url\(\s*['"]?#/.test(a.value)) return;
      attrs[a.name] = a.value;
    });
    var styled = el.hasAttribute("style") || (el.getAttribute("class") || "") !== "";
    COLOR_ATTRS.forEach(function (prop) {
      if (prop === "stop-color" && tag.toLowerCase() !== "stop") return;
      var raw = attrs[prop];
      if ((raw !== undefined && NEEDS_RESOLVE.test(raw)) || (raw === undefined && styled)) {
        var v = cs.getPropertyValue(prop);
        if (v) attrs[prop] = v;
      }
    });
    if (isRoot) {
      attrs["xmlns"] = "http://www.w3.org/2000/svg";
      if (!attrs["viewBox"]) {
        var w = parseFloat(attrs["width"]) || 24, h = parseFloat(attrs["height"]) || 24;
        attrs["viewBox"] = "0 0 " + w + " " + h;
      }
      attrs["width"] = "24";
      attrs["height"] = "24";
    }
    var inner = "";
    Array.prototype.slice.call(el.children).forEach(function (c) { inner += svgToString(c, false); });
    var attrStr = Object.keys(attrs).map(function (k) { return k + '="' + esc(attrs[k]) + '"'; }).join(" ");
    return "<" + tag + " " + attrStr + ">" + inner + "</" + tag + ">";
  }
  function extractIcon(container) {
    var svg = container.querySelector("svg");
    if (!svg) return null;
    return svgToString(svg, true) || null;
  }
  function labelOf(item) {
    var t = item.querySelector("span.truncate, [class*='truncate']");
    var text = ((t && t.textContent) || "").trim();
    if (!text) {
      var lines = (item.innerText || item.textContent || "").split("\n").map(function (s) { return s.trim(); }).filter(Boolean);
      text = lines[0] || "";
    }
    return text;
  }

  function modelButton() {
    for (var i = 0; i < SEL.MODEL_BUTTON.length; i++) {
      var els = qAll(SEL.MODEL_BUTTON[i]);
      for (var j = 0; j < Math.min(els.length, 4); j++) if (isVisible(els[j])) return els[j];
    }
    return null;
  }
  function searchInput() {
    for (var i = 0; i < SEL.MODEL_SEARCH.length; i++) {
      var els = qAll(SEL.MODEL_SEARCH[i]);
      if (els.length > 0 && isVisible(els[0])) return els[0];
    }
    return null;
  }
  L.readCurrentModel = function () {
    var btn = modelButton();
    if (!btn) return { found: false };
    var t = btn.querySelector("span.truncate");
    var name = ((t && t.textContent) || btn.innerText || "").trim();
    var slug = null;
    try { slug = new URLSearchParams(location.search).get("model_a"); } catch (e) {}
    return { found: true, name: name, icon: extractIcon(btn), slug: slug };
  };
  L.modelButtonFound = function () { return !!modelButton(); };
  L.searchVisible = function () { return !!searchInput(); };
  L.openModelDialog = function () {
    if (searchInput()) return { open: true };
    var btn = modelButton();
    if (!btn) return { open: false, error: "no_button" };
    if (btn.getAttribute("aria-expanded") !== "true") realClick(btn);
    return { open: !!searchInput() };
  };
  L.itemSelector = function () {
    for (var i = 0; i < SEL.MODEL_ITEM.length; i++) {
      if (qAll(SEL.MODEL_ITEM[i]).length > 0) return SEL.MODEL_ITEM[i];
    }
    return null;
  };
  L.collectModels = function (itemSel) {
    var out = [];
    qAll(itemSel).forEach(function (it) {
      if (it.getAttribute("aria-disabled") === "true" || it.getAttribute("data-disabled") === "true") return;
      var label = labelOf(it);
      if (!label) return;
      out.push({
        label: label,
        search: (it.innerText || it.textContent || "").replace(/\s+/g, " ").trim(),
        icon: extractIcon(it)
      });
    });
    return out;
  };
  L.findItem = function (itemSel, label) {
    var want = lower(label);
    var items = qAll(itemSel);
    for (var i = 0; i < items.length; i++) {
      if (lower(labelOf(items[i])) === want) return i;
    }
    return -1;
  };
  L.scrollList = function (itemSel, mode) {
    var first = document.querySelector(itemSel);
    if (!first) return { found: false, moved: false };
    var el = first.parentElement, scroller = null;
    while (el && el !== document.body) {
      var oy = getComputedStyle(el).overflowY;
      if ((oy === "auto" || oy === "scroll") && el.scrollHeight > el.clientHeight + 2) { scroller = el; break; }
      el = el.parentElement;
    }
    if (!scroller) return { found: false, moved: false };
    var before = scroller.scrollTop;
    if (mode === "top") scroller.scrollTop = 0;
    else if (mode === "bottom") scroller.scrollTop = scroller.scrollHeight;
    else scroller.scrollTop = before + Math.max(120, scroller.clientHeight * 0.85);
    return { found: true, moved: scroller.scrollTop !== before };
  };
  L.fillSearch = function (text) {
    var inp = searchInput();
    if (!inp) return false;
    try { inp.focus(); } catch (e) {}
    setNativeValue(inp, text);
    return true;
  };
  L.clickItem = function (itemSel, idx) {
    var items = qAll(itemSel);
    if (idx < 0 || idx >= items.length) return false;
    realClick(items[idx]);
    return true;
  };
  L.pressEscape = function () {
    var t = document.activeElement || document.body;
    pressKeyOn(t, "Escape", "Escape", 27);
    if (t !== document.body) pressKeyOn(document.body, "Escape", "Escape", 27);
    return true;
  };

  // 페이지가 안정될 때까지(DOM 변화가 멎을 때까지) 기다리는 데 쓰는 서명.
  L.domSignature = function () {
    return (document.body ? document.body.innerText.length : 0) + ":" + document.getElementsByTagName("*").length + ":" + document.readyState;
  };

  L.installHooks();
})();
