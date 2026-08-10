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
}
