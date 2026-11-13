package com.theacar.memory.records

/** 道を走り抜けるレコードか、景色の場所を通るレコードか。レコードの下の文字（区間か、町の名前か）に合わせる */
enum class RoadRecordKind { ROAD, PLACE }

/**
 * 手に入れられるレコード1枚。[id] は景観道路台帳（infra/scenic/data/hokkaido-recommended-roads.json）の道の id。
 * [title] と [area] はレコードの盤面に書いてある文字と同じにする。
 */
data class RoadRecordInfo(
    val id: String,
    val name: String,
    val title: String,
    val area: String,
    val kind: RoadRecordKind,
)

/** 台帳に本登録された名のある道のうち、レコードの絵がある 15 本。並びは RECORD タブの棚の順（南西から北東へ） */
val RoadRecords: List<RoadRecordInfo> = listOf(
    RoadRecordInfo("hakodate-tomoe-bridge", "ともえ大橋", "TOMOE BRIDGE", "HAKODATE • HOKKAIDO", RoadRecordKind.PLACE),
    RoadRecordInfo("onuma-lakeside", "大沼", "LAKE ONUMA", "NANAE • HOKKAIDO", RoadRecordKind.PLACE),
    RoadRecordInfo("niseko-panorama", "ニセコパノラマライン", "NISEKO PANORAMA", "NISEKO – IWANAI", RoadRecordKind.ROAD),
    RoadRecordInfo("shakotan-coast", "積丹の海岸", "SHAKOTAN COAST", "SHAKOTAN • HOKKAIDO", RoadRecordKind.PLACE),
    RoadRecordInfo("golden-road", "黄金道路", "GOLDEN ROAD", "HIROO – ERIMO", RoadRecordKind.ROAD),
    RoadRecordInfo("karikachi-pass", "狩勝峠", "KARIKACHI PASS", "SHINTOKU • HOKKAIDO", RoadRecordKind.PLACE),
    RoadRecordInfo("sounkyo-gorge-road", "層雲峡", "SOUNKYO GORGE", "KAMIKAWA • HOKKAIDO", RoadRecordKind.PLACE),
    RoadRecordInfo("ororon", "オロロンライン", "ORORON LINE", "TESHIO – WAKKANAI", RoadRecordKind.ROAD),
    RoadRecordInfo("selected-07", "宗谷丘陵", "SOYA HILLS", "WAKKANAI • HOKKAIDO", RoadRecordKind.PLACE),
    RoadRecordInfo("akan-crossing", "阿寒横断道路", "AKAN CROSSING", "AKAN – TESHIKAGA", RoadRecordKind.ROAD),
    RoadRecordInfo("kussharo-iou-road", "屈斜路湖", "LAKE KUSSHARO", "TESHIKAGA • HOKKAIDO", RoadRecordKind.PLACE),
    RoadRecordInfo("akkeshi-kiritappu-coast", "霧多布の海岸", "KIRITAPPU COAST", "AKKESHI – HAMANAKA", RoadRecordKind.ROAD),
    RoadRecordInfo("road-to-sky", "天に続く道", "ROAD TO THE SKY", "SHARI • HOKKAIDO", RoadRecordKind.PLACE),
    RoadRecordInfo("shiretoko-crossing", "知床峠", "SHIRETOKO PASS", "SHARI – RAUSU", RoadRecordKind.ROAD),
    RoadRecordInfo("notsuke-flower-road", "野付半島", "NOTSUKE PENINSULA", "BETSUKAI • HOKKAIDO", RoadRecordKind.PLACE),
)

fun roadRecord(id: String): RoadRecordInfo? = RoadRecords.firstOrNull { it.id == id }
