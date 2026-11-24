package com.kainext.duo.data

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TodoStoreTest {

    private fun store(settings: MapSettings = MapSettings()) = TodoStore(AppSettings(settings))

    private fun item(text: String, status: TodoStatus = TodoStatus.PENDING) = TodoItem(id = TodoStore.newId(), text = text, status = status)

    @Test
    fun `replace stores the list per conversation`() {
        val store = store()
        val items = listOf(item("book train"), item("add to calendar"))

        store.replace("c1", items)

        assertEquals(items, store.forConversation("c1"))
        assertTrue(store.forConversation("c2").isEmpty())
    }

    @Test
    fun `replace with an empty list clears the entry instead of storing it`() {
        val store = store()
        store.replace("c1", listOf(item("one")))

        store.replace("c1", emptyList())

        assertTrue(store.forConversation("c1").isEmpty())
        assertTrue(store.todos.value.isEmpty())
    }

    @Test
    fun `replace is a full rewrite so removed rows do not linger`() {
        val store = store()
        store.replace("c1", listOf(item("a"), item("b"), item("c")))

        store.replace("c1", listOf(item("a")))

        assertEquals(1, store.forConversation("c1").size)
    }

    @Test
    fun `setStatus flips one row and leaves the rest alone`() {
        val store = store()
        val first = item("a")
        val second = item("b")
        store.replace("c1", listOf(first, second))

        store.setStatus("c1", second.id, TodoStatus.COMPLETED)

        val result = store.forConversation("c1")
        assertEquals(TodoStatus.PENDING, result[0].status)
        assertEquals(TodoStatus.COMPLETED, result[1].status)
    }

    @Test
    fun `setStatus ignores an unknown id`() {
        val store = store()
        store.replace("c1", listOf(item("a")))

        store.setStatus("c1", "no-such-id", TodoStatus.COMPLETED)

        assertEquals(TodoStatus.PENDING, store.forConversation("c1").single().status)
    }

    @Test
    fun `clear drops only one conversation`() {
        val store = store()
        store.replace("c1", listOf(item("a")))
        store.replace("c2", listOf(item("b")))

        store.clear("c1")

        assertTrue(store.forConversation("c1").isEmpty())
        assertEquals(1, store.forConversation("c2").size)
    }

    @Test
    fun `todos survive a new store instance`() {
        val settings = MapSettings()
        TodoStore(AppSettings(settings)).replace("c1", listOf(item("persisted", TodoStatus.COMPLETED)))

        val reloaded = TodoStore(AppSettings(settings)).forConversation("c1")

        assertEquals(1, reloaded.size)
        assertEquals("persisted", reloaded.single().text)
        assertEquals(TodoStatus.COMPLETED, reloaded.single().status)
    }

    @Test
    fun `a corrupt stored blob degrades to an empty list`() {
        val settings = MapSettings()
        AppSettings(settings).setTodosJson("[[[")

        assertTrue(TodoStore(AppSettings(settings)).forConversation("c1").isEmpty())
    }

    @Test
    fun `parseStatus accepts the spellings the model actually emits`() {
        assertEquals(TodoStatus.COMPLETED, TodoStore.parseStatus("completed"))
        assertEquals(TodoStatus.COMPLETED, TodoStore.parseStatus("done"))
        assertEquals(TodoStatus.IN_PROGRESS, TodoStore.parseStatus("in_progress"))
        assertEquals(TodoStatus.IN_PROGRESS, TodoStore.parseStatus(" In-Progress "))
        assertEquals(TodoStatus.IN_PROGRESS, TodoStore.parseStatus("running"))
        assertEquals(TodoStatus.PENDING, TodoStore.parseStatus("pending"))
        assertEquals(TodoStatus.PENDING, TodoStore.parseStatus(null))
        assertEquals(TodoStatus.PENDING, TodoStore.parseStatus("nonsense"))
    }

    @Test
    fun `newId is unique`() {
        val ids = List(200) { TodoStore.newId() }.toSet()
        assertEquals(200, ids.size)
    }
}
