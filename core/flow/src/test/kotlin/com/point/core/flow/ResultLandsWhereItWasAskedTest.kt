package com.point.core.flow

import com.point.core.flow.capabilities.DropLinkCapability
import com.point.core.flow.capabilities.sharedCapabilities
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Результат, выходящий на устройстве, достаётся тому, кто нажал (#1034).
 *
 * Человек на телефоне нажал «Дать ссылку», согласился выложить файл в интернет, прочитал
 * «Ссылка в буфере — живёт сутки» — и ссылки у него не было: она легла в буфер компьютера,
 * который для этого действия оказался сильнейшим исполнителем. Файл при этом сутки открыт
 * любому по ссылке, которой у выложившего нет.
 */
class ResultLandsWhereItWasAskedTest {

    @Test
    fun `ссылка выдаётся там, где её попросили`() {
        assertTrue(DropLinkCapability().meta.resultLandsHere)
    }

    @Test
    fun `такое действие чужому устройству не рекламируется`() {
        val advertised = advertisedActions(sharedCapabilities()).map { it.id }

        assertFalse("чужое устройство берётся выдать ссылку", DropLinkCapability.ID.value in advertised)
    }

    /** Прочие общие способности рекламируются, как и раньше: правило узкое. */
    @Test
    fun `остальные способности едут на ту сторону по-прежнему`() {
        val advertised = advertisedActions(sharedCapabilities()).map { it.id }

        assertTrue("реклама опустела целиком", advertised.isNotEmpty())
    }

    /** Наружу объект по-прежнему уходит: режим приватности и согласие остаются в силе. */
    @Test
    fun `выдача ссылки остаётся сетевым действием`() {
        assertTrue(DropLinkCapability().meta.network)
    }
}
