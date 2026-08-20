package com.seoulprime.huboneagent

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * 절전(sleep) 명령용 최소 기기 관리자 리시버 — force-lock 정책 하나만 쓴다
 * (device_admin_policies.xml 참고). 사용자가 설정에서 직접 켜고 끌 수 있는
 * 가벼운 권한이고, "기기 소유자(Device Owner)"처럼 재설정이 필요하지 않다.
 *
 * 별도로 하는 일은 없다 — 활성화 여부만 DevicePolicyManager.isAdminActive()로
 * 확인해서 CommandPollService가 lockNow()를 호출할 수 있는지 판단하는 용도.
 */
class HubOneDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
    }
}
