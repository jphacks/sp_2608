package com.theacar.android.navigation

import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer

// 名前は日本語だけ、地名は太字、ほかは斜体をやめてふつうの書体にする
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
            layer.setProperties(PropertyFactory.textFont(arrayOf(if (layer.id.startsWith("label_")) "Noto Sans Bold" else "Noto Sans Regular")))
        }
}
