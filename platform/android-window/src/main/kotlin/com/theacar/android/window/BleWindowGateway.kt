package com.theacar.android.window

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import com.theacar.cabin.CabinAir
import com.theacar.cabin.CabinNotice
import com.theacar.cabin.CabinNoticeSource
import com.theacar.cabin.CabinNotices
import com.theacar.cabin.WindowControlException
import com.theacar.cabin.WindowGateway
import com.theacar.cabin.WindowLink
import com.theacar.cabin.WindowMove
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 窓の機器（ESP32）へ BLE で1文字のコマンドを送り、notify で送られてくるログを読む。
 * 機器はサービス UUID を広告しているので、接続先の事前設定は要らない。
 *
 * 以前は Wi-Fi の TCP を使っていたが、端末の Wi-Fi を機器に占有させると同じ端末で
 * インターネットが使えなくなる。BLE なら Wi-Fi を地図や音楽に残せる。
 *
 * コマンドは機器のファームウェアに合わせる。`d` が窓を下げる操作で、利用者から見た「開ける」。
 * 許可が揃ってから [start] を呼ぶ。
 */
@SuppressLint("MissingPermission") // [start] の前に WindowPermissions.granted で確かめている
class BleWindowGateway(
    private val context: Context,
    private val configuration: WindowConfiguration = WindowConfiguration.Default,
    private val scope: CoroutineScope,
) : WindowGateway, CabinNoticeSource {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = manager?.adapter

    private val mutableLink = MutableStateFlow(WindowLink.UNCONFIGURED)
    private val mutableAir = MutableStateFlow<CabinAir?>(null)
    private val mutableLog = MutableStateFlow<List<String>>(emptyList())

    /** 一度きりの出来事なので保持しない。受け取り手がいなければ捨てる。 */
    private val mutableNotices = MutableSharedFlow<CabinNotice>(extraBufferCapacity = 4)
    private var noticeCount = 0L

    override val link: StateFlow<WindowLink> = mutableLink.asStateFlow()
    override val air: StateFlow<CabinAir?> = mutableAir.asStateFlow()
    override val notices: SharedFlow<CabinNotice> = mutableNotices.asSharedFlow()
    val log: StateFlow<List<String>> = mutableLog.asStateFlow()

    private val writing = Mutex()
    @Volatile private var connection: BluetoothGatt? = null
    @Volatile private var command: BluetoothGattCharacteristic? = null
    private var loop: Job? = null

    /** 許可が揃ってから呼ぶ。何度呼んでも接続は1本のままで、画面を開き直すたびに呼んでよい。 */
    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch { keepConnected() }
    }

    override suspend fun move(move: WindowMove) = when (move) {
        WindowMove.OPEN -> send('d', "窓を開ける指示")
        WindowMove.CLOSE -> send('u', "窓を閉める指示")
    }

    override suspend fun stop() = send('s', "停止の指示")

    /** アプリを終えるときなどに接続を解放する。 */
    fun close() {
        loop?.cancel()
        loop = null
    }

    private suspend fun send(code: Char, label: String) {
        writing.withLock {
            val connected = connection
            val characteristic = command
            if (connected == null || characteristic == null) {
                throw WindowControlException("窓の機器に接続していません")
            }
            val accepted = withContext(Dispatchers.IO) {
                write(connected, characteristic, byteArrayOf(code.code.toByte()))
            }
            if (!accepted) throw WindowControlException("${label}を送信できませんでした")
        }
    }

    private fun write(
        connected: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        bytes: ByteArray,
    ): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        connected.writeCharacteristic(
            characteristic,
            bytes,
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
        ) == BluetoothStatusCodes.SUCCESS
    } else {
        @Suppress("DEPRECATION")
        run {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            characteristic.value = bytes
            connected.writeCharacteristic(characteristic)
        }
    }

    /**
     * 探索と接続を繰り返す。機器はキーを抜くと止まるので、切れたら待って探し直す。
     * Bluetooth が切られている・許可が無いあいだは UNCONFIGURED のまま待つ。
     */
    private suspend fun keepConnected() {
        while (currentCoroutineContext().isActive) {
            if (!usable()) {
                mutableLink.value = WindowLink.UNCONFIGURED
                delay(RETRY_MS)
                continue
            }
            mutableLink.value = WindowLink.CONNECTING
            try {
                session(withTimeout(SCAN_TIMEOUT_MS) { scan() })
            } catch (timeout: TimeoutCancellationException) {
                append("機器が見つかりません。電源が入っているか確認してください")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: WindowControlException) {
                append(failure.message ?: "接続できませんでした")
            }
            mutableLink.value = WindowLink.UNAVAILABLE
            delay(RETRY_MS)
        }
    }

    private fun usable(): Boolean = WindowPermissions.granted(context) && adapter?.isEnabled == true

    /** サービス UUID で機器を探す。名前は走査応答に載るので、拾えたときだけ照合する。 */
    private suspend fun scan(): BluetoothDevice = suspendCancellableCoroutine { continuation ->
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            continuation.resumeWithException(WindowControlException("Bluetooth を使えません"))
            return@suspendCancellableCoroutine
        }
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(configuration.service)).build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (!configuration.matches(result.device.name)) return
                if (!continuation.isActive) return
                runCatching { scanner.stopScan(this) }
                continuation.resume(result.device)
            }

            override fun onScanFailed(errorCode: Int) {
                if (!continuation.isActive) return
                continuation.resumeWithException(
                    WindowControlException("機器の探索に失敗しました（$errorCode）"),
                )
            }
        }
        continuation.invokeOnCancellation { runCatching { scanner.stopScan(callback) } }
        runCatching { scanner.startScan(filters, settings, callback) }.onFailure {
            if (continuation.isActive) {
                continuation.resumeWithException(
                    WindowControlException("機器の探索を始められませんでした", it),
                )
            }
        }
    }

    /**
     * 繋いでから切れるまでを1回分として扱う。
     * 繋ぐ → MTU を広げる → サービスを調べる → notify を有効にする、の順で、
     * 最後まで進んだら接続済みとして扱い、ログの断片を行に組み立てて流す。
     */
    private suspend fun session(device: BluetoothDevice) {
        val chunks = Channel<ByteArray>(Channel.UNLIMITED)
        val ready = CompletableDeferred<BluetoothGattCharacteristic>()
        var found: BluetoothGattCharacteristic? = null

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED ->
                        if (!gatt.requestMtu(PREFERRED_MTU)) gatt.discoverServices()
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        ready.completeExceptionally(WindowControlException("機器との接続が切れました"))
                        chunks.close()
                    }
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                // 広げられなくても困らない。行はこちらで組み立て直す。
                gatt.discoverServices()
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val service = gatt.getService(configuration.service)
                val logs = service?.getCharacteristic(configuration.log)
                val commands = service?.getCharacteristic(configuration.command)
                if (logs == null || commands == null) {
                    ready.completeExceptionally(
                        WindowControlException("機器の口が見つかりません。ファームウェアを確認してください"),
                    )
                    return
                }
                found = commands
                gatt.setCharacteristicNotification(logs, true)
                val descriptor = logs.getDescriptor(CLIENT_CONFIG)
                if (descriptor == null || !enableNotification(gatt, descriptor)) {
                    // 設定の書き込みができなくても、通知が届くことはあるので進める
                    ready.complete(commands)
                }
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                found?.let { ready.complete(it) }
            }

            @Suppress("DEPRECATION") // Android 12 以下はこちらが呼ばれる
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                if (characteristic.uuid != configuration.log) return
                characteristic.value?.let { chunks.trySend(it.copyOf()) }
            }

            // Android 13 以上はこちらが呼ばれる。親の実装は上を呼ぶので、二重に数えないよう上書きする。
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                if (characteristic.uuid != configuration.log) return
                chunks.trySend(value.copyOf())
            }
        }

        val connected = device.connectGatt(context, false, callback)
            ?: throw WindowControlException("窓の機器に接続できませんでした")
        connection = connected
        try {
            command = withTimeout(HANDSHAKE_TIMEOUT_MS) { ready.await() }
            mutableLink.value = WindowLink.CONNECTED
            val lines = BleLines()
            for (chunk in chunks) lines.feed(chunk).forEach { append(it) }
        } finally {
            command = null
            connection = null
            chunks.close()
            runCatching { connected.disconnect() }
            runCatching { connected.close() }
        }
    }

    private fun enableNotification(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(
                descriptor,
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
            }
        }

    private fun append(text: String) {
        val entry = text.trim()
        if (entry.isEmpty()) return
        Esp32Log.air(entry)?.let { mutableAir.value = it }
        // 機器が検知して窓を開けたら知らせる。連打の抑制は機器側のクールダウン（60秒）に任せる。
        if (Esp32Log.smellDrop(entry) != null) {
            mutableNotices.tryEmit(CabinNotices.windowOpened(++noticeCount))
        }
        mutableLog.value = (mutableLog.value + entry).takeLast(LOG_LINES)
    }

    private companion object {
        /** 機器のログ1行はおおよそ 80 バイト。1回の通知に収まると分割されずに届く。 */
        const val PREFERRED_MTU = 185
        const val SCAN_TIMEOUT_MS = 15_000L
        const val HANDSHAKE_TIMEOUT_MS = 15_000L
        const val RETRY_MS = 3000L
        const val LOG_LINES = 60
        val CLIENT_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
