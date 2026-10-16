package com.seoulprime.huboneagent

import android.app.Application

class HubOneAgentApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AgentLog.initialize(this)
    }
}
