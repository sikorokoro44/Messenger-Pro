package com.sikorokoro44.messengerpro.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonTest {
    @Test
    fun parsesNestedDocument() {
        val parsed = Json.parse("""{"ok":true,"count":2,"items":[{"name":"a","value":1.5},null],"text":"hi\n\"there\""}""")
        assertEquals(true, Json.obj(parsed)["ok"])
        assertEquals(2L, Json.num(Json.obj(parsed)["count"]))
        val items = Json.arr(Json.obj(parsed)["items"])
        assertEquals("a", Json.str(Json.obj(items[0])["name"]))
        assertEquals(1.5, Json.obj(items[0])["value"])
        assertNull(items[1])
        assertEquals("hi\n\"there\"", Json.str(Json.obj(parsed)["text"]))
    }

    @Test
    fun escapesAndRoundTrips() {
        val value = mapOf(
            "text" to "line\nquote\"slash\\tab\t",
            "list" to listOf(1L, true, null, "x"),
            "nested" to mapOf("a" to false)
        )
        val encoded = Json.stringify(value)
        assertEquals(value, Json.parse(encoded))
    }

    @Test
    fun readsUnicodeEscapes() {
        val parsed = Json.obj(Json.parse("""{"emoji":"\ud83d\udc4d"}"""))
        assertEquals("\uD83D\uDC4D", Json.str(parsed["emoji"]))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTrailingContent() {
        Json.parse("{} garbage")
    }

    @Test
    fun emptyHelpers() {
        assertTrue(Json.obj(null).isEmpty())
        assertTrue(Json.arr("nope").isEmpty())
        assertNull(Json.str(1L))
        assertNull(Json.num("x"))
    }
}
