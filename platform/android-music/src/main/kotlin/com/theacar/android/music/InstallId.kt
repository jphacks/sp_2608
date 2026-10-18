package com.theacar.android.music

import android.content.Context
import java.util.UUID

/** この端末を表す ID。ステッカー側も同じ名前・キーで共有するので変えない。 */
fun installId(context: Context): String {
    val preferences = context.applicationContext.getSharedPreferences("tabireco", Context.MODE_PRIVATE)
    preferences.getString("installId", null)?.takeIf { it.isNotBlank() }?.let { return it }
    return UUID.randomUUID().toString().also { preferences.edit().putString("installId", it).apply() }
}
