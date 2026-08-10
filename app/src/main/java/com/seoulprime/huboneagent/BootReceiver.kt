package com.seoulprime.huboneagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // 화면전환 명령 폴링은 MainActivity를 열지 않아도 부팅 직후부터 바로 받을 수
        // 있어야 한다(예: 태블릿이 덴트웹 앱으로 부팅되는 배포).
        CommandPollService.start(context)
        if (!AgentConfig.load(context).autoLaunch) return
        val launch = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.startActivity(launch)
    }
}
