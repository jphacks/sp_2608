package com.theacar.android.memory

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.theacar.memory.PhotoStyle
import com.theacar.memory.STICKER_HEIGHT_MM
import com.theacar.memory.STICKER_WIDTH_MM
import com.theacar.memory.TripMemory
import com.theacar.memory.TripMemoryEditor
import com.theacar.memory.destinationShortName
import com.theacar.ui.CardCorner
import com.theacar.ui.CardGap
import com.theacar.ui.ChipCorner
import com.theacar.ui.CompactHeaderBand
import com.theacar.ui.Ink
import com.theacar.ui.Mist
import com.theacar.ui.Mountains
import com.theacar.ui.Muted
import com.theacar.ui.Paper
import com.theacar.ui.Sunglow
import com.theacar.ui.Surface
import com.theacar.ui.TheaCarTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val PREVIEW_WIDTH_PX = 600
private const val STATE_PENDING_PHOTO = "pendingPhoto"
private const val PLAY_STORE_PACKAGE = "com.android.vending"
private const val PLAY_STORE_ICON_PX = 96

private val PrintRoute.iconRes: Int
    get() = if (this == PrintRoute.LINE) R.drawable.ic_line else R.drawable.ic_network_print

/** 到着後に写真・デフォルメ・メンバーを入れて、ステッカーを保存・印刷する画面。 */
class MemoryActivity : ComponentActivity() {
    private val runtime get() = (application as AndroidMemoryOwner).memoryRuntime
    private val store get() = runtime.store
    private val stylizer by lazy { PhotoStylizer(this) }
    private val renderer by lazy { StickerRenderer(this, runtime::pageUrl) }
    private val exporter by lazy { StickerExporter(renderer, store) }
    private val submitter: PrintSubmitter by lazy { SharePrintSubmitter(this) }

    private var editor by mutableStateOf<TripMemoryEditor?>(null)
    private val memory get() = editor?.state?.value?.memory
    private var loading by mutableStateOf(true)
    private var original by mutableStateOf<Bitmap?>(null)
    private var styled by mutableStateOf<Bitmap?>(null)
    private var preview by mutableStateOf<Bitmap?>(null)
    private var memberInput: String
        get() = editor?.state?.value?.memberInput.orEmpty()
        set(value) { editor?.enterMember(value) }
    private var placeInput: String
        get() = editor?.state?.value?.placeInput.orEmpty()
        set(value) { editor?.enterPlace(value) }
    private var busy by mutableStateOf(false)
    private var notice by mutableStateOf<String?>(null)
    private var choosePrintRoute by mutableStateOf(false)
    private var missingApp by mutableStateOf<PrintRoute?>(null)
    private var pendingPhoto: Uri? = null
    private var styleJob: Job? = null
    private var styleGeneration = 0L

    // Photo Picker は位置情報を落として渡すので、書類の選択画面で選ぶ。最初からカメラの写真が入るフォルダを開く
    private val pickPhoto = registerForActivityResult(object : ActivityResultContracts.OpenDocument() {
        override fun createIntent(context: Context, input: Array<String>): Intent =
            super.createIntent(context, input).putExtra(
                DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:DCIM/Camera"),
            )
    }) { uri ->
        if (uri == null) return@registerForActivityResult
        pendingPhoto = uri
        lifecycleScope.launch {
            // 権限ダイアログの間に画面が作り直されても消えないよう、先に元写真を保存する
            if (!copyOriginal(uri)) { notice = "写真を読み込めませんでした"; return@launch }
            if (Build.VERSION.SDK_INT >= 29 &&
                ContextCompat.checkSelfPermission(this@MemoryActivity, Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                mediaLocationRequest.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
            } else {
                loadPhoto(uri)
            }
        }
    }
    private val mediaLocationRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingPhoto?.let(::loadPhoto)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_MEMORY_ID)
        pendingPhoto = savedInstanceState?.getString(STATE_PENDING_PHOTO)?.let(Uri::parse)
        setContent {
            TheaCarTheme {
                val edit = editor?.state?.collectAsState()?.value
                val current = edit?.memory
                Column(Modifier.fillMaxSize().background(Paper)) {
                    CompactHeaderBand("STICKER", Sunglow)
                    if (current == null) {
                        Text(if (loading) "読み込み中…" else "記録が見つかりません", Modifier.padding(20.dp), style = MaterialTheme.typography.bodyLarge)
                    } else {
                        Column(
                            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding()
                                .padding(horizontal = 20.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(CardGap),
                        ) {
                            // 入力中の文字も引数で渡す。memory だけだと、打った文字で描き直されず入力欄が戻ってしまう
                            MemoryForm(current, edit.placeInput, edit.memberInput)
                        }
                    }
                }
            }
        }
        lifecycleScope.launch {
            editor = id?.let { store.read(it) }?.let { TripMemoryEditor(it, store) }
            loading = false
            restorePhotos()
            refreshPreview()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingPhoto?.let { outState.putString(STATE_PENDING_PHOTO, it.toString()) }
    }

    override fun onPause() {
        super.onPause()
        editor?.let(runtime::persist)
    }

    private fun appIcon(packageName: String): Bitmap? = try {
        val drawable = packageManager.getApplicationIcon(packageName)
        val size = PLAY_STORE_ICON_PX
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(bitmap))
        }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /** Play ストアでそのアプリのページを開く。ストアが無ければブラウザで開く。 */
    private fun openStore(route: PrintRoute) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(route.storeUri)))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(route.storeWebUrl)))
        }
    }

    /** 選んだ経路で渡す。アプリが無ければストアへ行くか聞く。 */
    private fun print(route: PrintRoute) {
        choosePrintRoute = false
        if (!isAppInstalled(route.packageName)) { missingApp = route; return }
        lifecycleScope.launch {
            val sheet = saveSheet() ?: return@launch
            notice = submitter.submit(sheet, route).message
        }
    }

    /** 画面の作り直し後、保存してある写真を読み直す。 */
    private suspend fun restorePhotos() {
        val current = memory ?: return
        val (restoredOriginal, restoredStyled) = withContext(Dispatchers.IO) {
            val originalFile = store.originalFile(current.id)
            val photoFile = store.photoFile(current.id)
            (if (originalFile.isFile) stylizer.load(Uri.fromFile(originalFile)) else null) to
                (if (photoFile.isFile) BitmapFactory.decodeFile(photoFile.path) else null)
        }
        original = restoredOriginal
        styled = restoredStyled
        if (restoredOriginal != null && restoredStyled == null) applyStyle()
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun MemoryForm(current: TripMemory, placeText: String, memberText: String) {
        val scope = rememberCoroutineScope()
        Text("写真", style = MaterialTheme.typography.titleLarge)
        OutlinedButton(onClick = { pickPhoto.launch(arrayOf("image/*")) }) {
            Text("写真を選ぶ")
        }
        Text("デフォルメ", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val selected = current.photoStyle ?: PhotoStyle.WATERCOLOR
            PhotoStyle.entries.forEach { style ->
                StyleChip(style.label, selected = style == selected) { changeStyle(current, style) }
            }
        }
        Text("地名", style = MaterialTheme.typography.titleLarge)
        val commitPlace = { scope.launch { commitInput(); refreshPreview() } }
        OutlinedTextField(
            value = placeText,
            onValueChange = { text -> if (text.contains('\n')) { placeInput = text.replace("\n", ""); commitPlace() } else placeInput = text },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(destinationShortName(current.destinationTitle), color = Muted) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commitPlace() }),
        )
        Text("メンバー", style = MaterialTheme.typography.titleLarge)
        if (current.members.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                current.members.forEachIndexed { index, name ->
                    MemberChip(name) { scope.launch { update { it.copy(members = it.members.filterIndexed { i, _ -> i != index }) }; refreshPreview() } }
                }
            }
        }
        val commitMember = { scope.launch { commitInput(); refreshPreview() } }
        OutlinedTextField(
            value = memberText,
            onValueChange = { text -> if (text.contains('\n')) { memberInput = text.replace("\n", ""); commitMember() } else memberInput = text },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("名前を入れて改行", color = Muted) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commitMember() }),
        )
        Text("プレビュー", style = MaterialTheme.typography.titleLarge)
        val frame = preview
        val frameModifier = Modifier.fillMaxWidth().aspectRatio((STICKER_WIDTH_MM / STICKER_HEIGHT_MM).toFloat())
            .clip(RoundedCornerShape(CardCorner)).background(Mist)
        if (frame != null) Image(frame.asImageBitmap(), contentDescription = "ステッカーのプレビュー", modifier = frameModifier, contentScale = ContentScale.Fit)
        else Spacer(frameModifier)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { scope.launch { val sheet = saveSheet() ?: return@launch; saveToGallery(sheet); notice = "シールの画像を保存しました" } }, enabled = !busy) { Text("シールの画像を保存") }
            Button(
                onClick = { choosePrintRoute = true },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = Sunglow, contentColor = Ink),
            ) { Text("コンビニで印刷") }
        }
        Text(NetworkPrint.STEPS, color = Muted, style = MaterialTheme.typography.bodyMedium)
        notice?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(CardGap))
        if (choosePrintRoute) PrintRouteDialog()
        missingApp?.let { route -> MissingAppDialog(route) }
    }

    @Composable
    private fun PrintRouteDialog() {
        AlertDialog(
            onDismissRequest = { choosePrintRoute = false },
            title = { Text("どちらで印刷しますか", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PrintRoute.entries.forEachIndexed { index, route ->
                        val shape = RoundedCornerShape(CardCorner)
                        Row(
                            Modifier.fillMaxWidth().clip(shape).background(Surface, shape).border(1.dp, Muted, shape)
                                .clickable { print(route) }.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Image(painterResource(route.iconRes), contentDescription = null, Modifier.size(40.dp))
                            Column {
                                Text("${index + 1}. ${route.label}", color = Ink, style = MaterialTheme.typography.titleMedium)
                                Text(route.detail, color = Muted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    TextButton(onClick = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(NetworkPrint.LINE_ADD_FRIEND_URL))) }) {
                        Text(NetworkPrint.LINE_ADD_FRIEND_LABEL)
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choosePrintRoute = false }) { Text("やめる") } },
            containerColor = Paper,
            titleContentColor = Ink,
            textContentColor = Ink,
        )
    }

    @Composable
    private fun MissingAppDialog(route: PrintRoute) {
        AlertDialog(
            onDismissRequest = { missingApp = null },
            icon = { Image(painterResource(route.iconRes), contentDescription = null, Modifier.size(56.dp)) },
            title = { Text(route.requiredTitle, style = MaterialTheme.typography.titleLarge) },
            text = { Text(route.requiredMessage) },
            confirmButton = {
                TextButton(onClick = { missingApp = null; openStore(route) }) {
                    appIcon(PLAY_STORE_PACKAGE)?.let { Image(it.asImageBitmap(), contentDescription = null, Modifier.padding(end = 8.dp).size(20.dp)) }
                    Text(NetworkPrint.OPEN_STORE_LABEL)
                }
            },
            dismissButton = { TextButton(onClick = { missingApp = null }) { Text("あとで") } },
            containerColor = Paper,
            titleContentColor = Ink,
            textContentColor = Ink,
        )
    }

    @Composable
    private fun StyleChip(label: String, selected: Boolean, onClick: () -> Unit) {
        val shape = RoundedCornerShape(ChipCorner)
        Text(
            label,
            Modifier.clip(shape).background(if (selected) Mountains else Surface, shape)
                .border(1.dp, if (selected) Mountains else Muted, shape).clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            color = if (selected) Surface else Ink,
            style = MaterialTheme.typography.labelLarge,
        )
    }

    @Composable
    private fun MemberChip(name: String, onRemove: () -> Unit) {
        val shape = RoundedCornerShape(ChipCorner)
        Row(
            Modifier.clip(shape).background(Surface, shape).border(1.dp, Muted, shape).padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(name, color = Ink, style = MaterialTheme.typography.labelLarge)
            Text(
                "×",
                Modifier.padding(start = 6.dp).clip(RoundedCornerShape(ChipCorner)).clickable(onClick = onRemove).padding(horizontal = 6.dp, vertical = 2.dp),
                color = Muted,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }

    /** 選んだ写真を files/memories/{id}/original.jpg に写す。Photo の URI は後から読めないため。 */
    private suspend fun copyOriginal(uri: Uri): Boolean {
        val current = memory ?: return false
        return withContext(Dispatchers.IO) {
            runCatching {
                contentResolver.openInputStream(uri)?.use { input -> store.originalFile(current.id).outputStream().use { input.copyTo(it) } } != null
            }.getOrDefault(false)
        }
    }

    private fun loadPhoto(uri: Uri) = lifecycleScope.launch {
        busy = true
        val location = PhotoMetadata.readLocation(this@MemoryActivity, uri)
        val current = memory
        val bitmap = current?.let { stylizer.load(Uri.fromFile(store.originalFile(it.id))) }
        if (bitmap == null) { busy = false; notice = "写真を読み込めませんでした"; return@launch }
        original = bitmap
        update { it.copy(photoLocation = location, photoStyle = it.photoStyle ?: PhotoStyle.WATERCOLOR) }
        applyStyle()
    }

    private fun changeStyle(current: TripMemory, style: PhotoStyle) {
        if (current.photoStyle == style) return
        lifecycleScope.launch {
            update { it.copy(photoStyle = style) }
            if (original != null) applyStyle() else refreshPreview()
        }
    }

    private fun applyStyle() {
        styleJob?.cancel()
        val generation = ++styleGeneration
        styleJob = lifecycleScope.launch {
            val source = original ?: return@launch
            val current = memory ?: return@launch
            busy = true
            try {
                val result = stylizer.stylize(source, current.photoStyle ?: PhotoStyle.WATERCOLOR)
                styled = result
                store.writePhoto(current.id) { StickerSheet.writeJpeg(result, it) }
                update { it }
                refreshPreview()
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                notice = "写真を保存できませんでした"
            } finally {
                if (generation == styleGeneration) busy = false
            }
        }
    }

    private suspend fun refreshPreview() {
        val current = memory ?: return
        val photo = styled
        preview = withContext(Dispatchers.Default) { renderer.render(current, photo, PREVIEW_WIDTH_PX) }
    }

    /** 1 コマを 600dpi で描いて 4 面付けし、filesDir に書く。 */
    private suspend fun saveSheet(): File? {
        if (busy) return null
        busy = true
        return try {
            val current = commitInput() ?: return null
            exporter.export(current, styled).also { runtime.publish(current.id) }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            notice = "保存できませんでした"
            null
        } finally {
            busy = false
        }
    }

    /** ギャラリーに書く。前回の行が残っていれば上書きし、無ければ新しく入れて記録に残す。 */
    private suspend fun saveToGallery(sheet: File) {
        val current = memory ?: return
        val uri = withContext(Dispatchers.IO) {
            StickerSheet.saveToGallery(this@MemoryActivity, sheet, MemoryFiles.sheetDisplayName(current), current.galleryUri?.let(Uri::parse))
        } ?: return
        if (uri.toString() != current.galleryUri) update { it.copy(galleryUri = uri.toString()) }
    }

    /** 記録の更新はすべて store を通し、書いた後の最新の記録で画面の状態を置き換える。 */
    private suspend fun update(transform: (TripMemory) -> TripMemory) {
        editor?.update(transform)
    }

    private suspend fun commitInput(): TripMemory? = try {
        editor?.commit()
    } catch (error: Exception) {
        if (error is kotlinx.coroutines.CancellationException) throw error
        notice = "入力を保存できませんでした"
        null
    }

    companion object {
        const val EXTRA_MEMORY_ID = "memoryId"
        fun intent(context: Context, memoryId: String): Intent = Intent(context, MemoryActivity::class.java).putExtra(EXTRA_MEMORY_ID, memoryId)
    }
}
