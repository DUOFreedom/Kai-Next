package com.kainext.duo.data

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoalStoreTest {

    private fun store(settings: MapSettings = MapSettings()) = GoalStore(AppSettings(settings))

    @Test
    fun `start creates an active goal with the configured round budget`() {
        val settings = MapSettings()
        val appSettings = AppSettings(settings)
        appSettings.setGoalMaxRounds(7)
        val store = GoalStore(appSettings)

        val goal = store.start("c1", "  ship the release  ")

        assertEquals("ship the release", goal.objective)
        assertEquals(AgentGoalStatus.ACTIVE, goal.status)
        assertEquals(0, goal.rounds)
        assertEquals(7, goal.maxRounds)
        assertEquals(7, goal.roundsLeft)
        assertTrue(goal.isActive)
        assertEquals(goal, store.forConversation("c1"))
    }

    @Test
    fun `maxRounds argument is clamped to the supported range`() {
        val store = store()
        assertEquals(GoalStore.MIN_MAX_ROUNDS, store.start("c1", "a", maxRounds = 0).maxRounds)
        assertEquals(GoalStore.MAX_MAX_ROUNDS, store.start("c2", "b", maxRounds = 999).maxRounds)
    }

    @Test
    fun `objective is truncated rather than rejected`() {
        val store = store()
        val long = "x".repeat(GoalStore.MAX_OBJECTIVE_CHARS + 250)

        val goal = store.start("c1", long)

        assertEquals(GoalStore.MAX_OBJECTIVE_CHARS, goal.objective.length)
    }

    @Test
    fun `goals are per conversation and replacing keeps only the newest`() {
        val store = store()
        store.start("c1", "first")
        store.start("c2", "other chat")

        val replaced = store.start("c1", "second")

        assertEquals("second", store.forConversation("c1")?.objective)
        assertEquals(replaced, store.forConversation("c1"))
        assertEquals("other chat", store.forConversation("c2")?.objective)
        assertNull(store.forConversation("nope"))
    }

    @Test
    fun `recordRound increments the persisted count`() {
        val settings = MapSettings()
        val store = GoalStore(AppSettings(settings))
        store.start("c1", "keep going")

        store.recordRound("c1")
        val second = store.recordRound("c1")

        assertNotNull(second)
        assertEquals(2, second.rounds)
        assertEquals(GoalStore.DEFAULT_MAX_ROUNDS - 2, second.roundsLeft)
    }

    @Test
    fun `recordRound returns null for a conversation without a goal`() {
        assertNull(store().recordRound("missing"))
    }

    @Test
    fun `setStatus records a blocked reason and clears it when moving back to active`() {
        val store = store()
        store.start("c1", "long job")

        val blocked = store.setStatus("c1", AgentGoalStatus.BLOCKED, "needs the API key")
        assertEquals(AgentGoalStatus.BLOCKED, blocked?.status)
        assertEquals("needs the API key", blocked?.blockedReason)
        assertFalse(blocked!!.isActive)

        val resumed = store.setStatus("c1", AgentGoalStatus.ACTIVE)
        assertEquals(AgentGoalStatus.ACTIVE, resumed?.status)
        assertNull(resumed?.blockedReason)
    }

    @Test
    fun `setStatus returns null when there is no goal to move`() {
        assertNull(store().setStatus("missing", AgentGoalStatus.COMPLETED))
    }

    @Test
    fun `clear removes only one conversation`() {
        val store = store()
        store.start("c1", "a")
        store.start("c2", "b")

        store.clear("c1")

        assertNull(store.forConversation("c1"))
        assertNotNull(store.forConversation("c2"))
    }

    @Test
    fun `goals survive a new store instance`() {
        val settings = MapSettings()
        GoalStore(AppSettings(settings)).start("c1", "persisted")
        GoalStore(AppSettings(settings)).recordRound("c1")

        val reloaded = GoalStore(AppSettings(settings)).forConversation("c1")

        assertEquals("persisted", reloaded?.objective)
        assertEquals(1, reloaded?.rounds)
    }

    @Test
    fun `a corrupt stored blob degrades to no goals instead of throwing`() {
        val settings = MapSettings()
        AppSettings(settings).setAgentGoalsJson("{not json")

        assertNull(GoalStore(AppSettings(settings)).forConversation("c1"))
    }

    @Test
    fun `the number of tracked conversations is bounded`() {
        val store = store()
        repeat(GoalStore.MAX_CONVERSATIONS + 5) { index ->
            store.start("c$index", "objective $index")
        }

        // Which ones survive depends on timestamps, but the stored map must stay bounded.
        assertEquals(GoalStore.MAX_CONVERSATIONS, store.goals.value.size)
    }
}
