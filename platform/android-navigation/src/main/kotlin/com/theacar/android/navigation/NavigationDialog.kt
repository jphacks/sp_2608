package com.theacar.android.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.theacar.ui.CardCorner
import com.theacar.ui.Ink

@Composable
internal fun NavigationDialog(
    title: String,
    onDismiss: () -> Unit,
    dismissEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * .86f
    Dialog(
        onDismissRequest = { if (dismissEnabled) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(
            modifier = Modifier.imePadding().systemBarsPadding().padding(16.dp)
                .widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight),
            shape = RoundedCornerShape(CardCorner),
            color = MaterialTheme.colorScheme.surface,
            border = FloatingBorder,
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 16.dp, bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, style = MaterialTheme.typography.titleLarge, color = Ink, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss, enabled = dismissEnabled) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = "閉じる")
                    }
                }
                content()
            }
        }
    }
}
