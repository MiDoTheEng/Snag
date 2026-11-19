package com.midotheeng.snag

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Target of "Share → Snag" from the X app or a browser. Shows a small card and closes itself. */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent?.dataString
        val id = XApi.extractId(text)

        setContent {
            MaterialTheme {
                Box(
                    Modifier.fillMaxSize()
                        .background(Color(0x66000000))
                        .clickable(remember { MutableInteractionSource() }, null) { finish() },
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Box(
                        Modifier.fillMaxWidth()
                            .padding(10.dp)
                            .navigationBarsPadding()
                            .background(Color.White, RoundedCornerShape(24.dp))
                            .clickable(remember { MutableInteractionSource() }, null) { /* keep open */ }
                    ) {
                        if (id == null) {
                            Column(Modifier.padding(20.dp)) {
                                Text("That doesn't look like an X post link.", color = Ink)
                            }
                        } else {
                            SnagPanel(id, onAllDone = { finish() })
                        }
                    }
                }
            }
        }
    }
}
