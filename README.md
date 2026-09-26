# LM Arena Android (kgycoder/Lmarena-work 포팅)

Windows용 CLI/브라우저 프로그램인 [kgycoder/Lmarena-work](https://github.com/kgycoder/Lmarena-work)
(LM Arena `https://arena.ai/text/direct`를 자동화해 에이전트처럼 쓰는 도구)를 안드로이드
APK로 그대로 옮긴 프로젝트입니다. `android/` 폴더가 실제 안드로이드 앱이고, GitHub
Actions(`.github/workflows/android-build.yml`)가 푸시할 때마다 APK를 자동으로 빌드합니다.

## 한눈에 보기

- **UI**: 원본 `lm_arena_cli/web_static/{index.html,app.js,styles.css}` 파일을 **한 글자도
  바꾸지 않고** 그대로 사용합니다. Direct/Agent 채팅, 모델 선택, 워크스페이스 트리,
  JARVIS 음성 모드, Work 콘솔 등 화면에 보이는 모든 기능은 원본과 동일한 코드로 동작합니다.
- **백엔드**: 원래 Flask(`webapp.py`) + Playwright(`browser.py` 등) 조합을, 앱 안에 내장된
  NanoHTTPD 로컬 서버(`127.0.0.1`) + 실제 Android `WebView` 기반 자동화 엔진으로 재구현했습니다.
  API 경로/요청·응답 형식은 원본 Flask 서버와 **완전히 동일**하므로 프런트엔드는 자신이
  안드로이드에서 실행 중인지 알 수 없습니다.
- **빌드**: GitHub Actions가 `android/` 아래에서 `./gradlew assembleDebug assembleRelease`를
  실행해 APK를 만듭니다(아래 "빌드" 참고).

## 폴더 구조

```
android/                          안드로이드 프로젝트 루트 (Gradle)
  app/src/main/java/.../lmarena/
    MainActivity.kt                두 WebView(자동화용/화면용) + 권한 + 부트스트랩
    AutomationForegroundService.kt 백그라운드에서 세션을 유지하는 포그라운드 서비스
    PopupWebViewActivity.kt        Google 로그인 팝업(window.open) 전용 창
    bridge/ArenaJsBridge.kt         arena_lib.js <-> Kotlin (프롬프트 전달, 다운로드 가로채기)
    bridge/SpeechBridge.kt          JARVIS 음성 인식: 네이티브 SpeechRecognizer 브리지
    bridge/TtsBridge.kt             음성 합성 폴백(대부분 WebView가 자체 지원)
    engine/AutomationEngine.kt      browser.py+webapp.py의 오케스트레이션 포팅 (핵심)
    engine/WorkAgent.kt             work_agent.py 포팅 (Work 모드 루프)
    engine/WorkProtocol.kt          work_protocol.py 포팅 (모델에게 주는 지침 + 액션 파서)
    engine/Workspace.kt             workspace.py 포팅 (작업 폴더 스냅샷)
    engine/FileWriterUtil.kt        file_writer.py 포팅 (파일명 추출/저장)
    engine/AgentZip.kt              agent_mode.py의 zip 안전 해제 포팅
    server/LocalServer.kt           webapp.py의 Flask 라우트와 동일한 계약의 로컬 서버
    work/WorkAccessibilityService.kt / ComputerAndroid.kt
                                    computer.py 포팅 (Win32 SendInput/UIA -> AccessibilityService)
  app/src/main/assets/web/          원본 web_static 그대로 (index.html/app.js/styles.css)
                                    + lma_speech_shim.js (JARVIS용 SpeechRecognition 폴리필)
  app/src/main/assets/lma/arena_lib.js
                                    browser.py/model_selector.py/agent_mode.py/captcha.py를
                                    WebView 안에서 실행되는 JS로 옮긴 DOM 자동화 라이브러리
.github/workflows/android-build.yml
```

## 빌드

### GitHub Actions (권장)
`main`/`master`에 푸시하거나 `android/**` 변경이 담긴 PR을 열면 자동으로 빌드됩니다.
수동 실행은 Actions 탭 > "Android Build" > **Run workflow**. 빌드가 끝나면 아티팩트
탭에 `lm-arena-debug-apk`(항상 debug 키로 서명, 그대로 설치 가능)와
`lm-arena-release-apk`(release 서명 secrets가 없으면 debug 키로 자동 대체되어 역시
바로 설치 가능한 APK)가 올라옵니다.

**release를 실제 배포용 키로 서명하려면** 저장소 Settings > Secrets and variables >
Actions 에 다음을 등록하세요(없어도 빌드는 실패하지 않습니다):

| Secret | 내용 |
|---|---|
| `LMA_KEYSTORE_BASE64` | `base64 -w0 release.keystore` 결과 |
| `LMA_KEYSTORE_PASSWORD` | keystore 비밀번호 |
| `LMA_KEY_ALIAS` | 키 별칭 |
| `LMA_KEY_PASSWORD` | 키 비밀번호 |

### 로컬 빌드
```bash
cd android
./gradlew assembleDebug
# 결과: app/build/outputs/apk/debug/app-debug.apk
```
JDK 17과(SDK를 직접 관리하지 않으려면) Android Studio가 있으면 그대로 열어서 빌드할 수
있습니다. `gradle/wrapper/`에 wrapper jar가 이미 포함돼 있어 Gradle을 따로 설치할 필요가
없습니다.

## 처음 실행할 때
1. 앱을 설치하고 열면 마이크(JARVIS 음성 모드용)와 알림 권한을 요청하고, 배터리 최적화
   제외를 요청하는 시스템 화면이 뜹니다(백그라운드 세션 유지를 위해 필요, 건너뛰어도
   앱은 동작하지만 화면을 꺼두면 세션이 더 쉽게 끊깁니다).
2. 처음에는 실제 `arena.ai` 로그인 화면이 그대로 보입니다(팝업/모달만 보여주고 나머지는
   가려서 최소한만 노출 - Windows판의 "필요할 때만 창을 보여준다"는 동작과 동일). 구글
   로그인을 누르면 별도 창이 뜨고, 로그인이 끝나면 자동으로 닫히고 원래 채팅 화면으로
   전환됩니다.
3. reCAPTCHA가 뜨는 경우도 로그인과 동일하게 그 순간만 화면에 드러납니다.
4. **Work 모드**(기기 자동 조작)를 처음 켜면 설정 > 접근성에서 "LM Arena" 서비스를 켜
   달라는 안내가 뜹니다. 켜지 않으면 Work 모드의 각 동작이 "접근성 서비스가 꺼져
   있습니다" 오류로 실패합니다(그 외 기능에는 영향 없음).

## 파일/작업 폴더는 어디에 저장되나요
Windows판은 사용자가 지정한 아무 폴더에나 직접 썼지만, 안드로이드는 앱이 임의의
절대경로에 쓰는 것을 막습니다(scoped storage). 그래서 "작업 폴더" 입력값은 앱 전용
저장소 아래 `Android/data/com.kgycoder.lmarena/files/workspace/<이름>` 폴더의 하위
폴더 이름으로 쓰입니다. 별도 승인 절차 없이 항상 즉시 쓰기 가능하고, 앱을 지우기 전까지
남아 있습니다. Agent 모드의 workspace zip 다운로드도 같은 위치에 자동으로 풀립니다.

## 기능 대응표

| 원본(Windows) 기능 | 안드로이드에서 |
|---|---|
| Direct 채팅 스트리밍 | 동일 (WebView 자동화 + 로컬 SSE) |
| Agent 모드(파일 트리, 리뷰, workspace 다운로드) | 동일 |
| 모델 선택기(검색/스크롤/아이콘) | 동일 (DOM 조작을 JS로 이식) |
| 파일 자동 저장(파일명 주석 인식) | 동일 (앱 전용 workspace 폴더에 저장) |
| 워크스페이스 공유(파일 내용을 프롬프트에 포함) | 동일 |
| reCAPTCHA/로그인 화면 최소 노출 | 동일한 동작을, 두 번째 WebView를 필요할 때만 보여주는 방식으로 구현 |
| JARVIS 음성 모드(듣기+TTS) | 동일하게 동작 (WebView에 없는 SpeechRecognition을 네이티브 브리지로 구현, 아래 참고) |
| Work 모드(PC 자동 조작) | 같은 개념을 AccessibilityService 기반으로 재구현 (아래 "제약" 참고) |
| 백그라운드 실행(다른 앱 쓰는 동안 계속 진행) | 포그라운드 서비스 + WakeLock으로 최대한 유지 (OS 제약은 아래 참고) |

## 기술적으로 안드로이드에 맞게 다시 구현한 부분 (포기하지 않고 대안을 구현함)

- **SpeechRecognition**: 안드로이드 시스템 WebView(Chromium)는 음성 합성(TTS)은 보통
  지원하지만 음성 *인식*은 지원하지 않습니다(별도의 "음성 인식 서비스"가 WebView에는
  연결돼 있지 않음 - 잘 알려진 WebView 제약). `app.js`는 전혀 건드리지 않고, 로드
  직전에 표준 `SpeechRecognition` 인터페이스를 그대로 흉내 내는 폴리필
  (`lma_speech_shim.js`)을 끼워 넣어, 실제로는 안드로이드 네이티브
  `android.speech.SpeechRecognizer`가 동작하도록 만들었습니다. `continuous` 모드도
  무음 구간마다 자동으로 다시 듣기를 시작해 체감상 동일하게 동작합니다.
- **PC 자동 조작(Work 모드)**: Windows판은 SendInput + UI Automation으로 화면을
  조작했습니다. 안드로이드에는 이와 동급인 **공식 API**가 있습니다 -
  `AccessibilityService`입니다. 좌표 탭/드래그/스크롤은 `dispatchGesture`로(시스템
  전역에서 동작), 현재 화면의 UI 트리 관찰은 `rootInActiveWindow`로, 입력창에 텍스트
  넣기는 `ACTION_SET_TEXT`로 구현했습니다. 사용자가 설정에서 이 서비스를 한 번 켜야
  하지만, 그 이후에는 다른 앱 화면까지 실제로 조작합니다.
- **로그인 팝업/reCAPTCHA만 보여주기**: Windows판은 창을 숨겼다가 필요할 때만
  보여줬습니다. 안드로이드에서는 화면 전체를 한 앱이 항상 차지하는 구조이므로, 대신
  "평소에는 로컬 서버가 만든 채팅 화면만 보이고, 로그인/캡차가 필요한 순간에만 실제
  `arena.ai` 페이지가 담긴 두 번째 WebView가 전면에 나타났다가 끝나면 다시 숨는" 방식으로
  같은 사용자 경험을 재현했습니다.
- **임의 폴더 저장**: 위 "파일/작업 폴더" 항목 참고 - scoped storage에 맞춰 앱 전용
  폴더로 대체했습니다.

## 기술적으로 정말 불가능한 부분 (안드로이드 OS 자체의 보안 경계)

아래는 "구현 방법을 못 찾은 것"이 아니라, **루팅되지 않은 일반 안드로이드에서 서드파티
앱에게 원천적으로 허용되지 않는** 항목입니다. Work 모드 안에서 모델이 이런 동작을
요청하면 앱은 실패로 처리하되 그 사실과 이유를 그대로 대화에 보여줍니다(조용히
무시하지 않습니다).

1. **임의의 하드웨어 키 조합을 다른 앱에 주입**(예: Ctrl+S, Alt+Tab 임의 조합): 안드로이드는
   `AccessibilityService`가 제공하는 정해진 전역 동작(뒤로가기/홈/최근앱 등)만 허용하고,
   그 밖의 임의 키 이벤트를 다른 프로세스에 주입하는 것은 루트 권한 또는 `adb shell input`
   (개발자 전용, 앱 스스로는 실행 불가) 없이는 막혀 있습니다.
2. **앱 샌드박스 밖 셸 명령 실행**: Windows는 사용자 권한으로 PowerShell을 자유롭게 실행할
   수 있었지만, 안드로이드 앱은 자기 자신의 샌드박스 밖 명령을 실행할 방법이 없습니다
   (루트 필요). `shell` op는 그래서 workspace 안 `dir/ls/pwd`만 제한적으로 지원합니다.
3. **이미 떠 있는 임의의 다른 앱 창을 강제로 앞으로 가져오기**: 데스크톱의 다중 창
   포커스 전환과 달리, 안드로이드는 서드파티 앱이 "이미 실행 중인 다른 앱의 특정 창"을
   임의로 활성화하는 공개 API를 제공하지 않습니다. 대신 해당 앱을 이름으로 찾아 다시
   실행하는 방식(`focus`)으로 대부분의 경우 동일한 효과를 냅니다.
4. **임의의 절대 파일 경로 읽기/쓰기**: Android 10+ scoped storage 때문에 앱은 자기
   폴더나 사용자가 명시적으로 고른 폴더(SAF) 밖의 임의 경로에 접근할 수 없습니다.
   `read_file`/`write_file`/`list_dir`은 앱 전용 workspace 폴더 안으로 제한됩니다.
5. **포그라운드에 있지 않을 때도 100% 보장된 지속 실행**: 안드로이드 OS(그리고 특히
   일부 제조사의 배터리 관리 기능)는 백그라운드 앱을 언제든 회수할 수 있습니다.
   포그라운드 서비스 + 지속 알림 + WakeLock + 배터리 최적화 예외 요청까지 다 갖췄지만,
   이는 "최대한 안 죽게" 만드는 것이지 Windows 서비스처럼 100% 보장되는 것은 아닙니다.
   일부 제조사 기기는 설정에서 "자동 실행/절전 예외"를 추가로 허용해야 할 수 있습니다.
6. **마우스 호버 / 오른쪽 클릭 메뉴**: 터치 화면에는 대응 개념이 없습니다(오른쪽 클릭은
   길게 누르기로 근사치만 제공).

## 알려진 한계 (동작은 하지만 원본과 약간 다른 부분)
- 응답 스트리밍 폴링 간격이 원본(0.1~0.4초)보다 다소 여유 있게(0.25~0.4초) 설정돼
  있습니다 - WebView `evaluateJavascript` 호출 비용을 고려한 것으로, 완성도/정확성에는
  영향이 없고 타이핑 애니메이션이 아주 약간 덜 부드럽게 보일 수 있습니다.
- 워크스페이스 폴더를 다른 앱(파일 관리자, Google Drive 등)과 실시간으로 동기화하지는
  않습니다. 필요하면 안드로이드의 표준 공유 시트로 workspace를 zip으로 내보내는 기능을
  추가하는 것을 권장합니다(이번 버전에는 포함하지 않았습니다).
