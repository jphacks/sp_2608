package com.theacar.android.navigation

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.theacar.route.RouteAlternative
import com.theacar.route.ScenicPreference

/** 出た候補を、選んだ景色が長い順に並べ直す。null は推薦の順のまま */
internal fun sortRoutes(routes: List<RouteAlternative>, by: ScenicPreference?): List<RouteAlternative> {
    if (by == null) return routes
    return routes.sortedByDescending { route ->
        val meters = route.scenic?.categoryMeters.orEmpty()
        by.weights.entries.sumOf { (category, weight) -> (meters[category] ?: 0) * weight }
    }
}

/** 候補の並べ替えを選ぶボタン */
@Composable
internal fun ScenicSortButton(by: ScenicPreference?, onSort: (ScenicPreference?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text(by?.let { "${it.label}の順" } ?: "おすすめ順") }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(text = { Text("おすすめ順") }, onClick = { onSort(null); open = false })
        ScenicPreference.entries.forEach { preference ->
            DropdownMenuItem(text = { Text("${preference.label}の順") }, onClick = { onSort(preference); open = false })
        }
    }
}
