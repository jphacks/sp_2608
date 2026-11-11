package com.theacar.android.navigation

/** 地名データの種類（OSM のタグの値）を日本語にする。訳のない英語は出さない */
internal fun placeCategoryLabel(value: String): String? = PlaceCategories[value.trim()]
    ?: value.takeUnless { it.trim().matches(Regex("[a-z_ ]+")) }

private val PlaceCategories = mapOf(
    "city" to "市", "town" to "町", "village" to "村", "hamlet" to "集落", "suburb" to "地区", "quarter" to "地区",
    "neighbourhood" to "地区", "locality" to "地名", "island" to "島", "islet" to "島",
    "station" to "駅", "stop" to "駅", "halt" to "駅", "subway_entrance" to "地下鉄の出入口", "rail" to "線路",
    "bus_station" to "バスターミナル", "ferry_terminal" to "フェリー乗り場", "airport" to "空港",
    "park" to "公園", "garden" to "庭園", "viewpoint" to "展望台", "attraction" to "観光名所", "museum" to "博物館",
    "zoo" to "動物園", "aquarium" to "水族館", "theme_park" to "テーマパーク", "camp_site" to "キャンプ場",
    "picnic_site" to "ピクニック場", "information" to "案内所", "artwork" to "アート", "memorial" to "記念碑",
    "monument" to "記念碑", "castle" to "城", "ruins" to "遺跡", "peak" to "山", "volcano" to "火山",
    "beach" to "海岸", "cape" to "岬", "water" to "水辺", "spring" to "湧き水", "hot_spring" to "温泉",
    "public_bath" to "銭湯・温泉", "hotel" to "ホテル", "guest_house" to "民宿", "hostel" to "ホステル",
    "motel" to "モーテル", "restaurant" to "飲食店", "cafe" to "カフェ", "fast_food" to "ファストフード",
    "pub" to "居酒屋", "bar" to "バー", "convenience" to "コンビニ", "supermarket" to "スーパー",
    "department_store" to "百貨店", "mall" to "ショッピングモール", "fuel" to "ガソリンスタンド",
    "parking" to "駐車場", "car" to "自動車販売店", "car_repair" to "自動車整備", "toilets" to "トイレ",
    "place_of_worship" to "寺社・教会", "school" to "学校", "university" to "大学", "college" to "専門学校",
    "kindergarten" to "幼稚園", "library" to "図書館", "community_centre" to "公民館", "townhall" to "役所",
    "post_office" to "郵便局", "police" to "警察", "fire_station" to "消防署", "hospital" to "病院",
    "clinic" to "診療所", "doctors" to "診療所", "dentist" to "歯科", "pharmacy" to "薬局", "bank" to "銀行",
    "atm" to "ATM", "sports_centre" to "スポーツ施設", "pitch" to "グラウンド", "stadium" to "スタジアム",
    "golf_course" to "ゴルフ場", "swimming_pool" to "プール", "ski" to "スキー場", "marina" to "マリーナ",
    "social_facility" to "福祉施設", "tree" to "木",
)
