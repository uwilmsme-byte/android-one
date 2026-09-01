package com.seoulprime.huboneagent

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** 홈 화면에서 HUBONE 태블릿의 세 가지 상담 화면을 바로 여는 작은 모드 선택 위젯. */
class HubOneModeWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
        widgetIds.forEach { widgetId ->
            val views = RemoteViews(context.packageName, R.layout.widget_hubone_modes).apply {
                setOnClickPendingIntent(
                    R.id.widget_tablet_consult,
                    openPage(context, 101, "/static/consult.html?quick=1&tablet_only=1", "landscape"),
                )
                setOnClickPendingIntent(
                    R.id.widget_tablet_consent,
                    openPage(context, 102, "/pt/consent", "portrait"),
                )
                setOnClickPendingIntent(
                    R.id.widget_paired_interpret,
                    openPage(context, 103, "/patient_view.html?autolisten=1", "landscape"),
                )
            }
            manager.updateAppWidget(widgetId, views)
        }
    }

    private fun openPage(context: Context, requestCode: Int, path: String, orientation: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = "com.seoulprime.huboneagent.OPEN_WIDGET_MODE.$requestCode"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra(MainActivity.EXTRA_SCREEN_COMMAND, path)
            putExtra(MainActivity.EXTRA_SCREEN_PATH, path)
            putExtra(MainActivity.EXTRA_SCREEN_ORIENTATION, orientation)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
