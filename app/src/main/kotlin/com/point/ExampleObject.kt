package com.point

import com.point.core.model.tr

data class ExampleObject(val uri: String, val mime: String, val name: String)

internal val EXAMPLE_OBJECT_NAME: String get() = tr("Визитка · пример")

internal fun exampleObject(packageName: String, resId: Int = R.raw.example_card): ExampleObject =
    ExampleObject(
        uri = "android.resource://$packageName/$resId",

        mime = "image/jpeg",
        name = EXAMPLE_OBJECT_NAME,
    )
