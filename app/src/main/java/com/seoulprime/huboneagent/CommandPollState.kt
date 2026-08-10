package com.seoulprime.huboneagent

/**
 * CommandPollService(백그라운드 폴링, MainActivity가 안 보여도 계속 돈다)와
 * MainActivity(실제 화면 전환 담당)가 "지금 태블릿이 어느 화면을 보여주고 있다고
 * 생각하는지"를 같은 프로세스 안에서 공유하기 위한 아주 작은 상태 저장소.
 *
 * 같은 프로세스에서 도는 Service/Activity라 SharedPreferences나 IPC 없이 static
 * 객체로 충분하다 — 프로세스가 죽으면(강제종료 등) 초기값("contact")으로 리셋되는데,
 * 이는 실제로 태블릿이 재부팅/재시작되면 접수 화면(/pt)부터 다시 시작하는 기존 정책과
 * 일치한다.
 */
object CommandPollState {
    const val SCREEN_CONTACT = "contact"
    const val SCREEN_RESERVATION = "reservation"
    const val SCREEN_DENTWEB = "dentweb"

    @Volatile
    var currentScreen: String = SCREEN_CONTACT

    // MainActivity.onWindowFocusChanged가 갱신 — 우리 화면이 실제로 입력 포커스를 갖고
    // 있는지(다른 오버레이/시스템 UI에 포커스를 뺏겼을 수 있음)를 Service의 focused 보고에
    // 반영한다. dentweb 화면일 때는 애초에 우리 창이 없으므로 이 값과 무관하게 false로 취급.
    @Volatile
    var windowFocused: Boolean = false

    // 마지막으로 시도한 명령의 결과 메시지 — 다음 폴링에서 status_message로 서버에
    // 보고해 허브원 보드 탭에 바로 보이게 한다(예: "덴트웹 앱을 찾을 수 없습니다: ...").
    @Volatile
    var lastStatusMessage: String = ""
}
