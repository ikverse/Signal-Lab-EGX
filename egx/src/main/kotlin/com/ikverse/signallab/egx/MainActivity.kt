package com.ikverse.signallab.egx

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ikverse.signallab.egx.view.EgxRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The page draws behind the gesture bar itself; Android must not lay its own grey strip over it.
        window.isNavigationBarContrastEnforced = false
        val graph = (application as EgxApplication).graph
        if (android.os.Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        setContent { EgxRoot(graph) }
    }
}
