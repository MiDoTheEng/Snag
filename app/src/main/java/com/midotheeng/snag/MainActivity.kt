package com.midotheeng.snag

import android.content.ClipboardManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Opened from the launcher: explains the share flow and lets you paste a link instead. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MaterialTheme { Home() } }
    }
}

@Composable
private fun Home() {
    val ctx = LocalContext.current
    var link by remember { mutableStateOf("") }
    var activeId by remember { mutableStateOf<String?>(null) }
    var run by remember { mutableIntStateOf(0) }
    val parsed = XApi.extractId(link)

    Column(
        Modifier.fillMaxSize().background(Color.White).systemBarsPadding().padding(22.dp)
    ) {
        Text("Snag", fontSize = 34.sp, fontWeight = FontWeight.Black, color = Ink)
        Text("Full-resolution media from X", color = Color(0xFF536471), fontSize = 15.sp)
        Spacer(Modifier.height(24.dp))

        Column(Modifier.fillMaxWidth().background(Color(0xFFF7F9F9), RoundedCornerShape(18.dp)).padding(18.dp)) {
            Text("How to use", fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.height(6.dp))
            Text(
                "In the X app, tap Share on a post → Share via… → Snag.\n" +
                    "Photos are saved at original size, videos at the highest quality, GIFs as looping MP4s.",
                color = Color(0xFF536471), fontSize = 14.sp, lineHeight = 20.sp
            )
        }

        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = link, onValueChange = { link = it },
            label = { Text("…or paste a post link") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        )
        Spacer(Modifier.height(10.dp))
        Row {
            OutlinedButton(onClick = {
                val cm = ctx.getSystemService(ClipboardManager::class.java)
                link = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
            }) { Text("Paste") }
            Spacer(Modifier.width(10.dp))
            Button(
                onClick = { activeId = parsed; run++ },
                enabled = parsed != null,
                colors = ButtonDefaults.buttonColors(containerColor = Ink)
            ) { Text("Download") }
        }

        activeId?.let { id ->
            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth().background(Color(0xFFF7F9F9), RoundedCornerShape(18.dp))) {
                key(run) { SnagPanel(id) }
            }
        }
    }
}
