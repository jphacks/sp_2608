package com.theacar.android.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theacar.navigation.NavigationState
import com.theacar.navigation.NavigationFailure
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Search
import com.theacar.route.Destination
import com.theacar.ui.CardCorner
import com.theacar.ui.Ink
import com.theacar.ui.Mist

@Composable
internal fun DestinationSearchDialog(
    state: NavigationState,
    placeSearchEnabled: Boolean,
    onSearch: (String) -> Unit,
    onSelect: (Destination) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf(state.query) }
    val canSearch = query.isNotBlank() && !state.searching
    val isCurrentQuery = query.trim().isNotEmpty() && query.trim() == state.query
    val results = state.destinations.takeIf { isCurrentQuery }.orEmpty()
    val failure = state.failure.takeIf { isCurrentQuery && !state.searching }

    NavigationDialog(
        title = "目的地を検索",
        onDismiss = onDismiss,
    ) {
        val keyboard = LocalSoftwareKeyboardController.current
        val focus = LocalFocusManager.current
        val search = { if (canSearch) { focus.clearFocus(); keyboard?.hide(); onSearch(query.trim()) } }
        Row(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(if (placeSearchEnabled) "地名・施設名" else "43.061,141.354") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.weight(1f),
                trailingIcon = if (query.isNotEmpty()) {
                    { IconButton(onClick = { query = "" }) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = "入力を消去", modifier = Modifier.size(18.dp))
                    } }
                } else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
            )
            FilledIconButton(onClick = search, enabled = canSearch,
                modifier = Modifier.size(56.dp), shape = CircleShape) {
                Icon(Icons.Rounded.Search, contentDescription = "検索")
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.weight(1f, fill = false)) {
            when {
                state.searching -> SearchStatus("場所を探しています", "検索結果が届くまでお待ちください。", loading = true)
                results.isNotEmpty() -> {
                    Text("検索結果 · ${results.size}件", modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyColumn {
                        items(results, key = { it.id }) { destination ->
                            DestinationResult(destination, onClick = { keyboard?.hide(); onSelect(destination) })
                        }
                    }
                }
                failure == NavigationFailure.NO_RESULTS -> SearchStatus("場所が見つかりませんでした", "別の地名や、短い施設名で検索してみてください。")
                failure == NavigationFailure.NETWORK -> SearchStatus("検索サーバーへ接続できません", "ネット接続と、PCのサーバー・Tunnelの起動状態を確認してください。TunnelのURLが変わった場合はアプリの接続先も更新してください。", error = true)
                failure != null -> SearchStatus("検索できませんでした", failure.message(), error = true)
                else -> SearchStatus(
                    if (placeSearchEnabled) "地名や施設名で検索" else "座標を入力して検索",
                    if (placeSearchEnabled) "たとえば「小樽運河」「札幌駅」。\n検索した場所を選ぶと、地図で確認できます。"
                    else "緯度,経度 の順に入力してください。\n地図をタップして選ぶこともできます。",
                )
            }
        }
        // 目的地を入れずに探すと周遊になることを、検索の画面でも知らせる
        Surface(color = Mist, shape = RoundedCornerShape(CardCorner),
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
            Text("目的地を入力せずに矢印ボタンを押すと周遊モードに切り替わり、決めた時間で現在地に戻れるドライブルートを作れます。",
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodySmall, color = Ink)
        }
        if (placeSearchEnabled) {
            Text("© OpenStreetMap contributors · ODbL",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun DestinationResult(destination: Destination, onClick: () -> Unit) {
    val title = destination.title.substringBefore(" · ")
    val detail = destination.title.substringAfter(" · ", "")
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = CircleShape, color = Mist) {
            Icon(Icons.Rounded.Place, contentDescription = null,
                modifier = Modifier.padding(10.dp).size(20.dp), tint = Ink)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SearchStatus(title: String, description: String, loading: Boolean = false, error: Boolean = false) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
