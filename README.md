# HUBONE Agent Android

## 앱의 용도

HUBONE Agent는 서울프라임치과의 접수 태블릿에서 기존 HUBONE 서버의 환자용 접수 화면을 안정적으로 표시하기 위한 Android 네이티브 키오스크 앱입니다.

태블릿에서 별도의 환자용 웹 화면을 새로 개발하지 않고, 서버가 제공하는 `/pt` 페이지를 Android WebView로 엽니다. 환자는 태블릿 화면을 직접 터치해 외국인 접수에 필요한 연락처와 기초 정보를 입력할 수 있습니다.

현재 앱의 책임 범위는 다음과 같습니다.

- HUBONE 환자용 접수 페이지 표시
- 가로 화면 전체화면 운영
- 화면 꺼짐 방지
- JavaScript, DOM Storage, 쿠키, 파일 업로드 지원
- 네트워크 장애 시 오류 화면과 재시도 제공
- 내부 LAN에서 HUBONE 서버 자동 검색
- 관리자 설정을 통한 서버 주소와 화면 식별자 변경

환자 정보 저장, 번역, 통역상담, 예약 로직은 앱에 포함하지 않습니다. 해당 기능은 기존 HUBONE 웹과 서버가 담당합니다.

## 접속 URL

앱은 다음 URL을 조합해 엽니다.

```text
${HUBONE_BASE_URL}/pt?screen_id=${SCREEN_ID}
```

기본값:

```text
HUBONE_BASE_URL = http://192.168.0.100:8001
SCREEN_ID = kiosk1
```

`HUBONE_BASE_URL`은 APK에 운영 주소로 고정하지 않았습니다. 기본값은 초기 설치 편의를 위한 예시 설정이며, 관리자 화면에서 실제 병원 서버 주소로 변경해야 합니다.

## 서버 자동 검색

저장된 주소에 연결하지 못하면 앱은 기존 HUBONE 데스크톱 앱의 검색 규칙을 사용합니다.

1. 저장된 HUBONE_BASE_URL의 `/health`를 먼저 확인합니다.
2. 태블릿의 사설 IPv4 주소를 확인합니다.
3. 같은 `/24` 네트워크 대역의 호스트를 검색합니다.
4. 포트 `8001`, `8000` 순서로 후보를 확인합니다.
5. 후보의 `/health` 응답이 HTTP 성공이고 본문에 `macai-deskchat`이 포함될 때만 HUBONE 서버로 인정합니다.
6. 발견한 서버 주소를 설정에 저장하고 `/pt?screen_id=...`를 다시 엽니다.

검색 대상은 내부 사설 IPv4 대역이며, 인터넷 전체를 검색하지 않습니다. 서버 검색은 Android 백그라운드 스레드에서 실행되고 후보별 연결 timeout은 짧게 설정되어 앱 화면을 멈추지 않습니다.

## 관리자 설정

일반 환자에게 설정 화면을 노출하지 않기 위해 다음 방식으로 진입합니다.

- 화면 오른쪽 위 모서리를 5초간 누르기
- WebView 화면을 5회 연속 터치하기

설정 항목:

- `HUBONE_BASE_URL`
- `SCREEN_ID`

버튼:

- 저장
- 연결 테스트
- 자동 검색
- 현재 URL 열기
- 앱 재시작
- 기본값 복원

저장된 값은 Android 앱 전용 SharedPreferences에 보관되며 다음 실행부터 사용됩니다.

## 태블릿 운영 방식

앱은 가로 방향으로 고정되고 상태바·내비게이션 바를 숨기는 몰입형 화면으로 실행됩니다. 화면 자동 꺼짐을 방지하며, 일반적인 뒤로가기는 WebView 내부 기록이 있을 때만 이전 페이지로 이동합니다. 더 이상 뒤로 갈 페이지가 없으면 앱을 종료하지 않습니다.

Android 완전한 Lock Task Mode는 현재 적용하지 않았습니다. 실제 태블릿에서 관리자 접근과 DentWeb 등 다른 업무 앱의 운영 방식을 확인한 뒤 별도 적용합니다.

부팅 완료 broadcast를 받으면 설정된 자동 실행 정책에 따라 Agent를 시작할 수 있도록 receiver 구조를 포함합니다. 제조사별 배터리 절전 정책에 따라 태블릿에서 자동 실행 허용이 추가로 필요할 수 있습니다.

## 네트워크 조건

실제 태블릿 테스트에서는 태블릿과 HUBONE 서버를 같은 내부 LAN에 연결하는 것을 권장합니다.

- 서버가 태블릿에서 접근 가능한 LAN 주소와 포트에 바인딩되어야 합니다.
- 방화벽에서 HUBONE 포트 `8001` 또는 `8000`을 허용해야 합니다.
- `/health`가 `macai-deskchat`을 포함한 정상 응답을 반환해야 자동 검색됩니다.
- `/pt`가 해당 내부망에서 무인증 접근 가능해야 합니다.
- Cloudflare 외부 주소는 로그인 리다이렉트가 발생할 수 있으며, 앱은 인증을 우회하지 않습니다.
- HTTPS 사용 시 Android/WebView의 정상 인증서 검증을 따릅니다.

## 빌드와 설치

필요 환경은 Android Studio 또는 Java 17이 설치된 환경입니다. 저장소에는 Gradle Wrapper가 포함되어 있습니다.

```bash
./gradlew assembleDebug
```

생성 파일:

```text
app/build/outputs/apk/debug/app-debug.apk
```

ADB 설치:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions의 `Android debug APK` workflow도 동일한 `./gradlew assembleDebug`를 실행하며, 성공 시 `hubone-agent-debug-apk` artifact로 APK를 제공합니다.

## 개인정보와 보안

- 환자 이름, 전화번호, 입력 내용, WebView 원문을 Android 로그에 기록하지 않습니다.
- 서버 URL과 화면 ID만 운영 설정으로 저장합니다.
- `HUBONE_BASE_URL` host 외의 WebView 이동은 차단합니다.
- HTTPS 인증서 검증을 비활성화하지 않습니다.
- 운영 서버 인증이나 Cloudflare 로그인 절차를 우회하지 않습니다.
- WebView 쿠키와 캐시는 일반 로그인·페이지 동작에 필요한 범위에서만 유지합니다.

## 현재 미구현 기능

다음 기능은 서버 계약과 실제 태블릿 운영 검증 이후 추가합니다.

- `/api/ws` WebSocket 원격 명령
- Android Agent 등록·인증 API
- 통역상담 화면
- 예약 캘린더 전환 명령
- DentWeb 앱 전환
- 마이크 및 음성인식
- 완전한 Lock Task 키오스크 모드
- 실제 태블릿 장치별 배포·업데이트 관리
