package com.theacar.dashboard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theacar.ui.CardCorner
import com.theacar.ui.CardGap
import com.theacar.ui.CompactHeaderBand
import com.theacar.ui.Muted
import com.theacar.ui.Sunset
import com.theacar.ui.SunsetDeep
import com.theacar.ui.jost
import com.theacar.ui.Surface as SurfaceWhite

data class TripRecord(
    val id: String,
    val date: String,
    val place: String,
    val distanceKm: Int,
    val minutes: Int,
    val members: List<String>,
    val photoVersion: Long? = null,
)

@Composable
fun RecordScreen(trips: List<TripRecord>, onOpen: (TripRecord) -> Unit, modifier: Modifier = Modifier,
    loadPhoto: suspend (TripRecord) -> ImageBitmap? = { null }) {
    Column(modifier) {
        CompactHeaderBand("RECORD", Sunset, recoColor = SunsetDeep)
        LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(CardGap)) {
            if (trips.isEmpty()) {
                item { Text(
                    "旅を終えると、ここに記録が並びます。",
                    Modifier.padding(top = 16.dp, bottom = 18.dp),
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                ) }
            }
            items(trips, key = { it.id }) { trip ->
                val image by produceState<ImageBitmap?>(null, trip.id, trip.photoVersion) {
                    value = if (trip.photoVersion == null) null else loadPhoto(trip)
                }
                TripCard(trip, image, onClick = { onOpen(trip) })
            }
        }
    }
}

@Composable
private fun TripCard(trip: TripRecord, image: ImageBitmap?, onClick: () -> Unit) {
    Surface(color = SurfaceWhite, shape = RoundedCornerShape(CardCorner), modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column {
            val bandShape = RoundedCornerShape(topStart = CardCorner, topEnd = CardCorner)
            Box(Modifier.fillMaxWidth().height(96.dp).clip(bandShape).background(Sunset)) {
                image?.let { Image(it, contentDescription = null, Modifier.fillMaxWidth().height(96.dp), contentScale = ContentScale.Crop) }
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 日付は Jost、漢字の地名は日本語の書体で出す
                val japanese = MaterialTheme.typography.bodyLarge
                Text(buildAnnotatedString {
                    append("${trip.date} ")
                    withStyle(SpanStyle(fontFamily = japanese.fontFamily, localeList = japanese.localeList)) { append(trip.place) }
                }, style = MaterialTheme.jost.date)
                Row(Modifier.fillMaxWidth()) {
                    Stat("DISTANCE", trip.distanceKm.toString(), "km", Modifier.weight(1f))
                    Stat("TIME", (trip.minutes / 60).toString(), "h", Modifier.weight(1f), (trip.minutes % 60).toString(), "min")
                }
                Text(trip.members.joinToString("・"), color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Stat(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    value2: String? = null,
    unit2: String? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.jost.label)
        val number = MaterialTheme.jost.stat.copy(fontSize = 28.sp, lineHeight = 32.sp)
        val small = MaterialTheme.jost.statUnit.toSpanStyle()
        Text(
            buildAnnotatedString {
                append(value)
                withStyle(small) { append(" $unit") }
                if (value2 != null && unit2 != null) {
                    append(" $value2")
                    withStyle(small) { append(" $unit2") }
                }
            },
            style = number,
            maxLines = 1,
        )
    }
}
