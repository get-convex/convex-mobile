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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import dev.convex.android.ConvexClient
import dev.convex.officialquickstart.ui.theme.OfficialQuickstartTheme
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OfficialQuickstartTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Tasks(
                        client = ConvexClient("https://small-canary-552.convex.cloud"),
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun Tasks(client: ConvexClient, modifier: Modifier = Modifier) {
    var tasks: List<Task> by remember { mutableStateOf(listOf()) }
    LaunchedEffect(key1 = "launch") {
        client.subscribe<List<Task>>("tasks:get").collect { result ->
            result.onSuccess { remoteTasks ->
                tasks = remoteTasks
            }
        }
    }
    LazyColumn(
        modifier = modifier
    ) {
        items(tasks) { task ->
            Text(text = "Text: ${task.text}, Completed?: ${task.isCompleted}")
        }
    }
}

@Serializable
data class Task(val text: String, val isCompleted: Boolean)