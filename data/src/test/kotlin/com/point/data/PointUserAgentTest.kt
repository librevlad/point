package com.point.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PointUserAgentTest {

    @Test
    fun `Point называет себя в каждом запросе`() {
        val headers = pointHeaders(emptyMap(), emptyMap())
        assertEquals("Point/0.2 (Android)", headers["User-Agent"])
    }

    @Test
    fun `заголовки транспорта остаются на месте`() {
        val headers = pointHeaders(mapOf("Content-Type" to "application/json"), emptyMap())
        assertEquals("application/json", headers["Content-Type"])
        assertEquals(POINT_USER_AGENT, headers["User-Agent"])
    }

    @Test
    fun `вызывающий сильнее умолчания — иначе паспорт стал бы потолком`() {
        val headers = pointHeaders(
            own = mapOf("Content-Type" to "application/json"),
            caller = mapOf("Content-Type" to "text/plain", "User-Agent" to "Point-Bot/1.0"),
        )
        assertEquals("text/plain", headers["Content-Type"])
        assertEquals("Point-Bot/1.0", headers["User-Agent"])
    }

    @Test
    fun `ключ вызывающего доезжает рядом с паспортом, а не вместо него`() {
        val headers = pointHeaders(emptyMap(), mapOf("Authorization" to "Bearer free-key"))
        assertEquals("Bearer free-key", headers["Authorization"])
        assertEquals(POINT_USER_AGENT, headers["User-Agent"])
    }
}
