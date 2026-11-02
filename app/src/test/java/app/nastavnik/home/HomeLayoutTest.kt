package app.nastavnik.home

import app.nastavnik.home.HomeLayout.Item
import app.nastavnik.home.HomeLayout.Size
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeLayoutTest {
    private val known = setOf("day", "plan", "cards", "streak", "alarm", "reminders")
    private val default = listOf(Item("day", Size.L), Item("cards", Size.S), Item("streak", Size.S), Item("alarm", Size.S))

    @Test
    fun spansOnPhoneAndTablet() {
        assertEquals(listOf(2, 3, 6), Size.entries.map { HomeLayout.span(it, 6) })
        assertEquals(listOf(3, 6, 12), Size.entries.map { HomeLayout.span(it, 12) })
    }

    @Test
    fun rowsPackInOrder() {
        val items = listOf(
            Item("day", Size.L), Item("cards", Size.S), Item("streak", Size.S), Item("alarm", Size.S),
            Item("plan", Size.M), Item("reminders", Size.S), Item("x", Size.M),
        )
        // Телефон: L | S S S | M S (5 из 6) | M
        assertEquals(listOf(listOf("day"), listOf("cards", "streak", "alarm"), listOf("plan", "reminders"), listOf("x")), HomeLayout.rows(items, 6).map { r -> r.map { it.id } })
        // Планшет: L | S S S (9 из 12, M уже не влезает) | M S (9 из 12) | M
        assertEquals(listOf(listOf("day"), listOf("cards", "streak", "alarm"), listOf("plan", "reminders"), listOf("x")), HomeLayout.rows(items, 12).map { r -> r.map { it.id } })
    }

    @Test
    fun decodeIsForgiving() {
        assertEquals(default, HomeLayout.decode(null, known, default))
        assertEquals(default, HomeLayout.decode("мусор", known, default))
        // Неизвестные и повторы — прочь, размер разбирается.
        assertEquals(listOf(Item("plan", Size.M), Item("day", Size.S)), HomeLayout.decode("plan:M,map:L,day:S,plan:L", known, default))
        // «-» — всё убрано.
        assertEquals(emptyList<Item>(), HomeLayout.decode("-", known, default))
        val items = listOf(Item("day", Size.L), Item("cards", Size.S))
        assertEquals(items, HomeLayout.decode(HomeLayout.encode(items), known, default))
    }

    @Test
    fun editing() {
        val items = listOf(Item("day", Size.L), Item("cards", Size.S), Item("plan", Size.M))
        assertEquals(listOf("cards", "day", "plan"), HomeLayout.move(items, 1, -1).map { it.id })
        assertEquals(items, HomeLayout.move(items, 0, -1))
        assertEquals(Size.L, HomeLayout.resize(items, 1, Size.L)[1].size)
        assertEquals(listOf("day", "plan"), HomeLayout.remove(items, 1).map { it.id })
        assertEquals(listOf("day", "cards", "plan", "alarm"), HomeLayout.add(items, "alarm", Size.S).map { it.id })
        assertEquals(items, HomeLayout.add(items, "day", Size.S))
    }
}
