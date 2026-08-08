# HUBONE Agent Android

현재 버전은 기존 HUBONE 서버의 환자용 `/pt` 화면만 표시합니다.

## 로컬 검증

앱 실행 후 상단 버튼으로 다음을 확인합니다.

최종 URL은 `${HUBONE_BASE_URL}/pt?screen_id=${SCREEN_ID}`로 구성됩니다.

기본값은 `http://192.168.0.100:8000`과 `kiosk1`입니다. 오른쪽 위 모서리를 5초간 누르거나 WebView 화면을 5회 연속 터치하면 관리자 화면이 열립니다.

WebView는 JavaScript, DOM storage, 쿠키, 파일 업로드, HTTP/HTTPS를 지원합니다. `HUBONE_BASE_URL`의 host 외 이동은 차단하며, 인증서 검증은 우회하지 않습니다. 네트워크 오류 시 다시 시도 화면을 표시합니다.

## GitHub Actions

Android SDK가 없는 환경에서도 저장소 루트의 `.github/workflows/hubone-agent-android-debug.yml`이 GitHub-hosted runner에서 SDK와 Gradle을 설치하고 `app-debug.apk`를 artifact로 업로드합니다.

로컬 빌드는 `./gradlew assembleDebug`로 실행하고, `app/build/outputs/apk/debug/app-debug.apk`를 사용합니다.
