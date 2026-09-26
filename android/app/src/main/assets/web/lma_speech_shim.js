/*
 * Android 시스템 WebView(Chromium)는 SpeechSynthesis(TTS)는 대체로 지원하지만
 * SpeechRecognition(음성 인식)은 지원하지 않는다 - 이 기능을 처리하는 "음성 인식
 * 서비스"가 데스크톱 Chrome/일반 브라우저와 달리 WebView에는 연결돼 있지 않기
 * 때문이다(Android WebView의 알려진 제약). app.js는 전혀 건드리지 않고, app.js가
 * 로드되기 전에 window.SpeechRecognition을 이 폴리필로 먼저 정의해 두어, 실제로는
 * 네이티브 android.speech.SpeechRecognizer(브라우저의 "음성 인식 서비스"와 동일한
 * 안드로이드 시스템 서비스)로 동작하게 만든다. app.js 입장에서는 표준 Web Speech
 * API 객체를 쓰는 것과 완전히 동일하다.
 */
(function () {
  if (window.SpeechRecognition && window.SpeechRecognition.__lmaNative) return;
  var bridge = window.AndroidSpeech;
  if (!bridge) return; // 브리지가 없으면(구형 WebView 등) 원래대로 기능이 없다고만 보고한다.

  var listeners = new Map();
  var nextId = 1;

  function FakeSpeechRecognitionResult(transcript, isFinal) {
    var arr = [{ transcript: transcript }];
    arr.isFinal = isFinal;
    return arr;
  }

  function LmaSpeechRecognition() {
    this._id = nextId++;
    this.lang = "ko-KR";
    this.continuous = false;
    this.interimResults = false;
    this.maxAlternatives = 1;
    this.onstart = null;
    this.onresult = null;
    this.onerror = null;
    this.onend = null;
    this.onaudiostart = null;
    this.onaudioend = null;
    listeners.set(this._id, this);
  }
  LmaSpeechRecognition.__lmaNative = true;

  LmaSpeechRecognition.prototype.start = function () {
    bridge.start(this._id, this.lang || "ko-KR", !!this.continuous, !!this.interimResults);
  };
  LmaSpeechRecognition.prototype.stop = function () {
    bridge.stop(this._id);
  };
  LmaSpeechRecognition.prototype.abort = function () {
    bridge.abort(this._id);
  };
  LmaSpeechRecognition.prototype.addEventListener = function (type, fn) {
    var key = "on" + type;
    if (key in this) this[key] = fn;
  };

  // 네이티브(Kotlin) 쪽에서 evaluateJavascript로 호출한다.
  window.__lmaSpeechEvent = function (id, kind, data) {
    var inst = listeners.get(id);
    if (!inst) return;
    if (kind === "start") {
      if (inst.onaudiostart) inst.onaudiostart({});
      if (inst.onstart) inst.onstart({});
    } else if (kind === "result") {
      if (!inst.onresult) return;
      var results = [];
      results.resultIndex = 0;
      (data.chunks || []).forEach(function (c) {
        results.push(FakeSpeechRecognitionResult(c.text, c.isFinal));
      });
      inst.onresult({ resultIndex: 0, results: results });
    } else if (kind === "error") {
      if (inst.onerror) inst.onerror({ error: data.error || "unknown" });
    } else if (kind === "end") {
      if (inst.onaudioend) inst.onaudioend({});
      if (inst.onend) inst.onend({});
      listeners.delete(id);
    }
  };

  window.SpeechRecognition = LmaSpeechRecognition;
  window.webkitSpeechRecognition = LmaSpeechRecognition;

  // ── SpeechSynthesis(TTS) 폴백 ────────────────────────────────
  // 최신 WebView는 보통 이미 지원하므로, 정말 없을 때만 네이티브 TextToSpeech로 대체한다.
  if (!("speechSynthesis" in window) && window.AndroidTts) {
    var uId = 1;
    var uCallbacks = new Map();
    function LmaUtterance(text) {
      this.text = text || "";
      this.lang = "ko-KR";
      this.rate = 1;
      this.pitch = 1;
      this.volume = 1;
      this.onstart = null;
      this.onend = null;
      this.onerror = null;
    }
    window.SpeechSynthesisUtterance = LmaUtterance;
    window.speechSynthesis = {
      speaking: false,
      pending: false,
      speak: function (utter) {
        var id = uId++;
        uCallbacks.set(id, utter);
        window.speechSynthesis.speaking = true;
        window.AndroidTts.speak(id, utter.text || "", utter.lang || "ko-KR");
      },
      cancel: function () {
        window.AndroidTts.cancel();
        window.speechSynthesis.speaking = false;
      },
      getVoices: function () { return []; },
      onvoiceschanged: null,
    };
    window.__lmaTtsEvent = function (id, kind) {
      var utter = uCallbacks.get(id);
      if (!utter) return;
      if (kind === "start" && utter.onstart) utter.onstart({});
      if (kind === "end") {
        window.speechSynthesis.speaking = false;
        if (utter.onend) utter.onend({});
        uCallbacks.delete(id);
      }
      if (kind === "error") {
        window.speechSynthesis.speaking = false;
        if (utter.onerror) utter.onerror({ error: "synthesis-failed" });
        uCallbacks.delete(id);
      }
    };
  }
})();
