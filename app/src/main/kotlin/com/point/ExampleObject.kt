package com.point

data class ExampleObject(val uri: String, val mime: String, val name: String)

internal const val EXAMPLE_OBJECT_NAME = "Визитка · пример"

internal fun exampleObject(packageName: String, resId: Int = R.raw.example_card): ExampleObject =
    ExampleObject(
        uri = "android.resource://$packageName/$resId",

        mime = "image/jpeg",
        name = EXAMPLE_OBJECT_NAME,
    )
