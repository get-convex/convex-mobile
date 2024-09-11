package dev.convex.officialquickstart

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import dev.convex.android.ConvexClient
import dev.convex.officialquickstart.ui.theme.OfficialQuickstartTheme
import kotlinx.serialization.Serializable

val client: ConvexClient by lazy { ConvexClient("https://small-canary-552.convex.cloud") }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OfficialQuickstartTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Tasks(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun Tasks(modifier: Modifier = Modifier) {
    val tasks: Result<List<Task>> by client.subscribe<List<Task>>("tasks:get")
        .collectAsState(Result.success(listOf()))
    LazyColumn(
        modifier = modifier
    ) {
        items(tasks.getOrElse { emptyList() }) { task ->
            Text(text = "Text: ${task.text}, Completed?: ${task.isCompleted}")
        }
    }
}

@Serializable
data class Task(val text: String, val isCompleted: Boolean)