package com.theacar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 画面上端の見出し帯。ステータスバーの裏まで色を敷き、文字はその下に置く。
 * 「旅れこ」の「れこ」に合わせ、RECORD の RECO だけを [recoColor] にできる。
 */
@Composable
fun HeaderBand(title: String, subtitle: String, color: Color, modifier: Modifier = Modifier, recoColor: Color? = null) {
    Column(
        modifier.fillMaxWidth().background(color).statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(buildAnnotatedString {
            val text = title.uppercase()
            if (recoColor != null && text.startsWith("RECO")) {
                withStyle(SpanStyle(color = recoColor)) { append("RECO") }
                append(text.removePrefix("RECO"))
            } else {
                append(text)
            }
        }, style = MaterialTheme.jost.cardTitle)
        Text(subtitle, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

/** 見出しだけの低い帯。地図画面のように、下の面積を優先したい画面で使う。 */
@Composable
fun CompactHeaderBand(title: String, color: Color, modifier: Modifier = Modifier, recoColor: Color? = null) {
    Column(modifier.fillMaxWidth().background(color).statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 8.dp)) {
        Text(buildAnnotatedString {
            val text = title.uppercase()
            if (recoColor != null && text.startsWith("RECO")) {
                withStyle(SpanStyle(color = recoColor)) { append("RECO") }
                append(text.removePrefix("RECO"))
            } else {
                append(text)
            }
        }, style = MaterialTheme.jost.cardTitle.copy(fontSize = 28.sp, lineHeight = 32.sp))
    }
}
