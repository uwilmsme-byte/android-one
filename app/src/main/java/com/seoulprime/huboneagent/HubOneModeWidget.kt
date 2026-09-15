package com.seoulprime.huboneagent

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** 홈 화면에서 HUBONE 태블릿의 세 가지 상담 화면 + 관리자 설정을 바로 여는 작은 모드 선택 위젯. */
class HubOneModeWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
        widgetIds.forEach { widgetId ->
            val views = RemoteViews(context.packageName, R.layout.widget_hubone_modes).apply {
                setOnClickPendingIntent(
                    R.id.widget_tablet_consult,
                    // screen_id가 있어야 consult.html의 _isNativeSingleMicAvailable()이
                    // HubOneAudio 네이티브 브릿지(서버 Silero VAD 스트리밍, /api/consult/
                    // kiosk/vad-stream)를 쓴다 — 없으면 순수 웹 getUserMedia()로 빠지는데,
                    // 이 앱은 서버를 http 내부 IP로 여는 경우가 많아 보안 컨텍스트가 아니라서
                    // "마이크를 사용할 수 없습니다"로 실패했다(실사용 지적). screen_id 하나
                    // 추가로 기존에 이미 있던 네이티브 브릿지 경로를 타게 한다.
                    openPage(context, 101, "/static/consult.html?quick=1&tablet_only=1&screen_id=tablet_solo&hubone_build=20260901-4", "landscape", popup = true),
                )
                setOnClickPendingIntent(
                    R.id.widget_tablet_consent,
                    openPage(context, 102, "/pt/consent", "portrait"),
                )
                setOnClickPendingIntent(
                    R.id.widget_paired_interpret,
                    openPage(context, 103, "/patient_view.html?autolisten=1", "landscape"),
                )
                setOnClickPendingIntent(R.id.widget_settings, openSettings(context))
            }
            manager.updateAppWidget(widgetId, views)
        }
    }

    // 화면 우상단 5초 꾹누르기 제스처를 태블릿 현장에서 매번 찾기 번거롭다는 실사용
    // 지적 — 위젯에서 SettingsActivity를 바로 띄우는 버튼을 별도로 둔다. 일반 환자용
    // 위젯이 아니라 직원이 홈 화면에 배치해 쓰는 위젯이라 노출해도 된다.
    private fun openSettings(context: Context): PendingIntent {
        val intent = Intent(context, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return PendingIntent.getActivity(
            context,
            104,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openPage(context: Context, requestCode: Int, path: String, orientation: String, popup: Boolean = false): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = "com.seoulprime.huboneagent.OPEN_WIDGET_MODE.$requestCode"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra(MainActivity.EXTRA_SCREEN_COMMAND, path)
            putExtra(MainActivity.EXTRA_SCREEN_PATH, path)
            putExtra(MainActivity.EXTRA_SCREEN_ORIENTATION, orientation)
            putExtra(MainActivity.EXTRA_SCREEN_POPUP, popup)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
