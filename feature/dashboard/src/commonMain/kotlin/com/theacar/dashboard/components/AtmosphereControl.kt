package com.theacar.dashboard.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theacar.ui.Mountains
import com.theacar.ui.Ink
import com.theacar.ui.Muted

@Composable
fun AtmosphereControl(value: Float, onValueChange: (Float) -> Unit, onCoverSound: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("車内のにぎわい", color = Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("会話が増えると音楽をそっと下げます", color = Muted, fontSize = 10.sp)
            }
            Text("特殊音テスト", color = Mountains, fontSize = 11.sp, modifier = Modifier.clickable(onClick = onCoverSound))
        }
        Slider(value = value, onValueChange = onValueChange)
    }
}
