package com.theacar.android.memory

import androidx.annotation.DrawableRes

/** アプリにレコードの絵を持たせているかどうか */
interface RoadRecordOwner { val roadRecords: RoadRecordStore }

/** レコードの絵（drawable-nodpi/record_*.webp）。台帳の道の id から引く */
@DrawableRes
fun recordArt(id: String): Int? = when (id) {
    "hakodate-tomoe-bridge" -> R.drawable.record_hakodate_tomoe_bridge
    "onuma-lakeside" -> R.drawable.record_onuma_lakeside
    "niseko-panorama" -> R.drawable.record_niseko_panorama
    "shakotan-coast" -> R.drawable.record_shakotan_coast
    "golden-road" -> R.drawable.record_golden_road
    "karikachi-pass" -> R.drawable.record_karikachi_pass
    "sounkyo-gorge-road" -> R.drawable.record_sounkyo_gorge_road
    "ororon" -> R.drawable.record_ororon
    "selected-07" -> R.drawable.record_selected_07
    "akan-crossing" -> R.drawable.record_akan_crossing
    "kussharo-iou-road" -> R.drawable.record_kussharo_iou_road
    "akkeshi-kiritappu-coast" -> R.drawable.record_akkeshi_kiritappu_coast
    "road-to-sky" -> R.drawable.record_road_to_sky
    "shiretoko-crossing" -> R.drawable.record_shiretoko_crossing
    "notsuke-flower-road" -> R.drawable.record_notsuke_flower_road
    else -> null
}
