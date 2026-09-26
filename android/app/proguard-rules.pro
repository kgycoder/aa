# 현재 빌드는 isMinifyEnabled = false 이므로 이 파일은 실제로 적용되지 않는다.
# 추후 코드 축소를 켤 경우를 대비해 JS 브리지(@JavascriptInterface)와
# NanoHTTPD 리플렉션 대상만 최소한으로 보존 규칙을 남겨둔다.
-keepclassmembers class com.kgycoder.lmarena.bridge.** {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class fi.iki.elonen.** { *; }
