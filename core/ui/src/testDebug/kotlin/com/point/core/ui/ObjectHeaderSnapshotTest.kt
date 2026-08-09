package com.point.core.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import com.point.core.ui.theme.PointTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class ObjectHeaderSnapshotTest {

    @get:Rule val compose = createComposeRule()

    private fun photo(name: String) = PointObject(
        id = "o:photo",
        mime = "image/jpeg",
        uri = ScratchRef("/scratch/$name"),
        state = ObjectState(ObjectKind.IMAGE),
        metadata = mapOf("name" to name),
    )

    private fun screen(obj: PointObject, preview: ImageBitmap?) = compose.setContent {
        PointTheme(darkTheme = true) {
            FirstScreen(obj = obj, bubbles = emptyList(), onBubble = {}, previewBitmap = preview)
        }
    }

    @Test fun `снимок в шапке назван именем объекта`() {
        screen(photo("фото.jpg"), ImageBitmap(64, 40))

        compose.onNodeWithContentDescription("фото.jpg").assertExists()
    }

    @Test fun `объект без имени всё равно показывает снимок`() {
        val nameless = photo("x").copy(metadata = emptyMap())
        screen(nameless, ImageBitmap(40, 64))

        compose.onNodeWithContentDescription(ObjectKind.IMAGE.name).assertExists()
    }
}
