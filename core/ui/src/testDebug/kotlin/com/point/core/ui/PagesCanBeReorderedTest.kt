package com.point.core.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.PointObject
import com.point.core.model.ScratchRef
import com.point.core.ui.theme.PointTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Страницы набора переставляются с экрана набора (#1207): стрелка двигает страницу на шаг,
 * крайние стрелки выключены, а там, где переставлять нечего или некому, стрелок нет.
 */
@RunWith(RobolectricTestRunner::class)
class PagesCanBeReorderedTest {

    @get:Rule val compose = createComposeRule()

    private fun page(name: String) =
        PointObject(name, "image/jpeg", ScratchRef("/$name"), ObjectState(ObjectKind.IMAGE), mapOf("name" to name))

    private val set = PointObject(
        "set", "inode/directory", ScratchRef("/set"), ObjectState(ObjectKind.COLLECTION), mapOf("name" to "Набор (3)"),
    )

    private val pages = listOf(page("IMG_1.jpg"), page("IMG_2.jpg"), page("IMG_3.jpg"))

    private val moves = mutableListOf<Pair<String, Int>>()

    private fun screen(items: List<PointObject> = pages, onMove: ((PointObject, Int) -> Unit)?) {
        compose.setContent {
            PointTheme {
                FirstScreen(
                    obj = set,
                    bubbles = emptyList(),
                    onBubble = {},
                    items = items,
                    itemsTotal = items.size,
                    onMoveItem = onMove,
                )
            }
        }
    }

    @Test
    fun `стрелка вниз двигает страницу на шаг ниже`() {
        screen { item, by -> moves += item.metadata.getValue("name") to by }

        compose.onAllNodesWithContentDescription(PAGE_DOWN)[0].performScrollTo().performClick()

        assertEquals(listOf("IMG_1.jpg" to 1), moves)
    }

    @Test
    fun `выше первой и ниже последней места нет — крайние стрелки выключены`() {
        screen { _, _ -> }

        compose.onAllNodesWithContentDescription(PAGE_UP)[0].assertIsNotEnabled()
        compose.onAllNodesWithContentDescription(PAGE_UP)[1].assertIsEnabled()
        compose.onAllNodesWithContentDescription(PAGE_DOWN)[2].assertIsNotEnabled()
    }

    @Test
    fun `некому переставлять — стрелок нет`() {
        screen(onMove = null)

        compose.onAllNodesWithContentDescription(PAGE_DOWN).assertCountEquals(0)
    }

    @Test
    fun `список файлов без страниц — стрелок нет`() {
        val files = listOf(
            PointObject("a", "application/zip", ScratchRef("/a.zip"), ObjectState(ObjectKind.ZIP), mapOf("name" to "a.zip")),
            PointObject("b", "audio/ogg", ScratchRef("/b.ogg"), ObjectState(ObjectKind.AUDIO), mapOf("name" to "b.ogg")),
        )
        screen(items = files) { _, _ -> }

        compose.onAllNodesWithContentDescription(PAGE_UP).assertCountEquals(0)
    }

    @Test
    fun `стрелка вниз ходит в пределах показанного — за «Показать ещё» страница не уезжает`() {
        val many = (1..COLLECTION_PAGE + 1).map { page("IMG_%02d.jpg".format(it)) }
        screen(items = many) { _, _ -> }

        compose.onAllNodesWithContentDescription(PAGE_DOWN).assertCountEquals(COLLECTION_PAGE)
        compose.onAllNodesWithContentDescription(PAGE_DOWN)[COLLECTION_PAGE - 1].assertIsNotEnabled()

        compose.onNodeWithText("Показать ещё", substring = true).performScrollTo().performClick()

        compose.onAllNodesWithContentDescription(PAGE_DOWN).assertCountEquals(COLLECTION_PAGE + 1)
        compose.onAllNodesWithContentDescription(PAGE_DOWN)[COLLECTION_PAGE - 1].assertIsEnabled()
        compose.onAllNodesWithContentDescription(PAGE_DOWN)[COLLECTION_PAGE].assertIsNotEnabled()
    }
}
