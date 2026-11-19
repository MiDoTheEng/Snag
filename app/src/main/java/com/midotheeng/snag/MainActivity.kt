package com.midotheeng.snag

import android.Manifest
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
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

val Ink = Color(0xFF0F1419)

/** Opened from the launcher: explains the share flow and lets you paste a link instead. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        setContent { MaterialTheme { Home() } }
    }
}

@Composable
private fun Home() {
    val ctx = LocalContext.current
    var link by remember { mutableStateOf("") }
    val ok = Net.firstUrl(link) != null || XApi.extractId(link) != null

    Column(Modifier.fillMaxSize().background(Color.White).systemBarsPadding().padding(22.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
            Text("Snag", fontSize = 34.sp, fontWeight = FontWeight.Black, color = Ink)
            Spacer(Modifier.width(8.dp))
            Text("v" + ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName, color = Color(0xFF8B98A5), fontSize = 13.sp, modifier = Modifier.padding(bottom = 7.dp))
        }
        Text("Full-resolution media from X, Instagram, Pinterest, Facebook and Google", color = Color(0xFF536471), fontSize = 15.sp)
        Spacer(Modifier.height(24.dp))

        Column(Modifier.fillMaxWidth().background(Color(0xFFF7F9F9), RoundedCornerShape(18.dp)).padding(18.dp)) {
            Text("How to use", fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.height(6.dp))
            Text(
                "Tap Share on a post or image → Snag. It works in the background — keep using your phone.\n\n" +
                    "• Photos: original size\n• Videos: highest quality\n• GIFs: saved as real .gif (send them on WhatsApp)\n\n" +
                    "Saved to Pictures/Snag and Movies/Snag.",
                color = Color(0xFF536471), fontSize = 14.sp, lineHeight = 20.sp
            )
        }

        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = link, onValueChange = { link = it },
            label = { Text("…or paste a link") },
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
                onClick = {
                    SnagService.startLink(ctx, link)
                    Toast.makeText(ctx, "Snag: on it", Toast.LENGTH_SHORT).show()
                    link = ""
                },
                enabled = ok,
                colors = ButtonDefaults.buttonColors(containerColor = Ink)
            ) { Text("Download") }
        }
    }
}
