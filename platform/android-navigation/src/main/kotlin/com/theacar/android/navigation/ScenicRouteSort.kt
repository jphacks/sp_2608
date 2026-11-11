package com.theacar.android.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    // Box で包み、選択肢をボタンの真下に出す
    Box {
    // 下向きの小さな三角で、押すと選べることを示す
    TextButton(onClick = { open = true }, contentPadding = PaddingValues(start = 8.dp, end = 4.dp)) {
        Text(by?.let(::sortLabel) ?: "おすすめ順")
        Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, modifier = Modifier.size(20.dp))
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(text = { Text("おすすめ順") }, onClick = { onSort(null); open = false })
        ScenicPreference.entries.forEach { preference ->
            DropdownMenuItem(text = { Text(sortLabel(preference)) }, onClick = { onSort(preference); open = false })
        }
    }
    }
}

private fun sortLabel(by: ScenicPreference): String = when (by) {
    ScenicPreference.SEA -> "海が多い"
    ScenicPreference.FOREST -> "森が多い"
    ScenicPreference.BALANCED -> "自然が多い"
    ScenicPreference.WATER -> "湖・川が多い"
    ScenicPreference.VALLEY -> "渓谷が多い"
    ScenicPreference.FARMLAND -> "田園が多い"
    ScenicPreference.URBAN -> "街なかが多い"
}
