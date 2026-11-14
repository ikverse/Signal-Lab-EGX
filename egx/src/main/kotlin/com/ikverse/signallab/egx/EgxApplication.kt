package com.ikverse.signallab.egx

import android.app.Application
import com.ikverse.signallab.egx.pass.Schedule

class EgxApplication : Application() {
    lateinit var graph: EgxGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = EgxGraph(this)
        Schedule.arm(this)
    }
}
