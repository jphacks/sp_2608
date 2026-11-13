package com.theacar.android.navigation

import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer

// 名前は日本語だけにし、縁取りを太くして読みやすくする
internal fun applyTabiMapLabels(style: Style) {
    style.layers.filterIsInstance<SymbolLayer>()
        .filter { !it.textField.isNull && !it.id.contains("shield") }
        .forEach { layer ->
            layer.setProperties(
                PropertyFactory.textField(
                    Expression.coalesce(Expression.get("name:ja"), Expression.get("name:nonlatin"), Expression.get("name")),
                ),
                PropertyFactory.textHaloWidth(1.6f),
            )
        }
}
