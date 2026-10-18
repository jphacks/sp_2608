package com.theacar.cabin

import kotlinx.coroutines.flow.SharedFlow

/**
 * 車内の空気について、起きたことを一度だけ伝える知らせ。
 *
 * 文面は**原因に触れません**。においを検知したことを言わずに、換気したという事実だけを伝えます。
 * 同乗者がいる場面で誰も気まずくならないことを優先しています。
 *
 * [CabinSuggestion] とは別物です。あちらは「開けませんか？」という提案で、
 * こちらは既に起きたことの報告です。
 */
data class CabinNotice(
    /** 読み上げる文。画面の見出しにも使う。 */
    val spoken: String,
    /** 画面に添える一行。読み上げない。 */
    val detail: String,
    /** 同じ知らせを二重に出さないための番号。出るたびに増える。 */
    val id: Long,
)

/** 知らせの文面。増やすときはここに集める。 */
object CabinNotices {
    fun windowOpened(id: Long): CabinNotice = CabinNotice(
        spoken = "空気を入れ替えますね",
        detail = "窓を少し開けました",
        id = id,
    )
}

/**
 * 知らせの配信元。実装は platform が持つ。
 * 一度きりの出来事なので、状態ではなく流れとして配る。受け取り手がいなければ捨てる。
 */
interface CabinNoticeSource {
    val notices: SharedFlow<CabinNotice>
}

/** Application が知らせの配信元を持つことを示す。車載画面やスマホ画面がここから受け取る。 */
interface CabinNoticeOwner {
    val cabinNotices: CabinNoticeSource
}
