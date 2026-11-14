package com.ikverse.signallab.egx.view

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ikverse.signallab.egx.EgxGraph
import com.ikverse.signallab.egx.pass.NotificationTexts
import com.ikverse.signallab.egx.pass.Schedule

/** Placeholder until the screens of the accepted design (milestone E4): the sentence, the calls, and a run button. */
@Composable
fun EgxRoot(graph: EgxGraph) {
    val calls by graph.ledger.dao().latestCalls().collectAsState(initial = emptyList())
    val pass by graph.ledger.dao().lastPass().collectAsState(initial = null)
    val tr = graph.tr()
    val context = LocalContext.current
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(24.dp)) {
                Text(NotificationTexts.sentence(calls, tr), style = MaterialTheme.typography.headlineSmall)
                for (c in calls) Text("${c.action} ${graph.name(c.code)} ${c.shares}")
                Text(pass?.message ?: "")
                Button(onClick = { Schedule.runNow(context) }) { Text(tr.t("Run the evening pass now")) }
            }
        }
    }
}
