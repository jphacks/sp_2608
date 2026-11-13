package com.theacar.android.memory

import android.content.res.AssetManager
import com.theacar.model.Coordinate
import java.io.DataInputStream

/**
 * 北海道の海岸線。国土数値情報（行政区域データ）を加工して作成（CC BY 4.0、作り方は tools/hokkaido_outline.py）。
 * 線の数、線ごとの点の数と緯度・経度（1e-5 度）を big-endian の Int で並べる。
 */
internal fun readHokkaidoOutline(assets: AssetManager): List<List<Coordinate>> =
    DataInputStream(assets.open("hokkaido_outline.bin").buffered()).use { input ->
        List(input.readInt()) { List(input.readInt()) { Coordinate(input.readInt() / 1e5, input.readInt() / 1e5) } }
    }
