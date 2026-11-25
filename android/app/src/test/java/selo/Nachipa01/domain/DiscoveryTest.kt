package selo.Nachipa01.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import selo.Nachipa01.data.events.BehaviourEvent
import selo.Nachipa01.data.events.Keys
import selo.Nachipa01.data.repo.DemoBackend
import selo.Nachipa01.data.repo.PublishRequest
import selo.Nachipa01.ui.ListingForm
import selo.Nachipa01.ui.screens.seller.framePlanMs

class DiscoveryTest {
    @Test fun uuidv7IsTimeOrderedAndVersioned() {
        val ids = (0 until 2000).map { Keys.uuidv7(1_800_000_000_000L + it) }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(ids.sorted(), ids)
        assertTrue(ids.all { it[14] == '7' })
    }

    /** Same values as functions/src/algorithm.ts jumpHash, so events land on the same shard either side. */
    @Test fun jumpHashMatchesServer() {
        val expected = listOf(Triple("user-1", 61, 2), Triple("abc", 21, 3), Triple("seller-42", 44, 2), Triple("Zm9v", 60, 1), Triple("", 22, 5))
        expected.forEach { (k, s64, s7) -> assertEquals(k, s64, Keys.jumpHash(k, 64)); assertEquals(k, s7, Keys.jumpHash(k, 7)) }
    }

    @Test fun eventKeysMatchTheSecurityRule() {
        assertTrue(Regex("evt_[0-9a-f-]{36}_s[0-9]{2}").matches(Keys.eventKey("user-1")))
        assertTrue(Keys.eventKey("user-1").endsWith("_s61"))
    }

    @Test fun framePlanFollowsSpec() {
        assertEquals(listOf(0L, 1000, 2000, 3000, 4000, 5000, 6000, 7000, 8000, 9000, 10000, 15000, 20000, 25000), framePlanMs(30_000))
        assertEquals(11, framePlanMs(15_000).size) // 0..9 s + 10 s
        assertEquals(16, framePlanMs(60_000).size) // 10 + 10 capped at 16
    }

    @Test fun onDeviceLabelsMapToTaxonomy() {
        assertEquals("Fresh produce", Taxonomy.fromLabels(listOf("Tomato" to .9f)))
        assertEquals("Phones & accessories", Taxonomy.fromLabels(listOf("Furniture" to .4f, "Mobile phone" to .8f)))
        assertNull(Taxonomy.fromLabels(listOf("Sky" to .9f)))
        assertEquals(16, Taxonomy.CATEGORIES.size)
    }

    @Test fun reviewRequiresEveryField() {
        val f = ListingForm(title = "x", reviewed = ListingForm.FIELDS.toSet() - "tags")
        assertFalse(f.allReviewed)
        assertTrue(f.copy(reviewed = f.reviewed + "tags").allReviewed)
    }

    @Test fun demoFeedLearnsAndRespectsNotInterested() = runBlocking {
        val b = DemoBackend()
        val first = b.feed(null, 8)
        assertEquals(8, first.items.size)
        first.items.zipWithNext().forEach { (a, c) -> assertNotEquals(a.sellerUid, c.sellerUid) }
        val phone = DemoData.listings.first { it.category == "Phones & accessories" }
        repeat(5) { b.logEvents(listOf(BehaviourEvent("like", phone.id, phone.sellerUid, "Fashion")), null) }
        val hated = DemoData.listings.first { it.category == "Fast food" }
        b.logEvents(listOf(BehaviourEvent("not_interested", hated.id, hated.sellerUid, hated.category)), null)
        val next = b.feed(first.sessionId, 8)
        assertTrue(next.items.none { it.id == hated.id })
        assertTrue("no repeats within 72 h", next.items.none { n -> first.items.any { it.id == n.id } })
        assertEquals("Fashion", next.items.first().category)
    }

    @Test fun demoGuardrailsWarnAt130AndStopRankingAt145() = runBlocking {
        val b = DemoBackend()
        b.minutesTodayOverride = 131
        assertTrue(b.feed(null).guard.warn); assertFalse(b.feed(null).guard.capReached)
        b.minutesTodayOverride = 146
        val p = b.feed(null)
        assertTrue(p.guard.capReached); assertTrue(p.items.all { it.rankSource == "unranked_cap" })
    }

    @Test fun postedListingAppearsInSearch() = runBlocking {
        val b = DemoBackend()
        b.publishListingV2(PublishRequest("video", "v", emptyList(), null, 20_000, "Kapenta dried fish 1 kg", "Lake Kariba kapenta.", 12_000,
            "Groceries", "New", "In Stock", "Lusaka", "Soweto", "", listOf("kapenta"), 0.0, true, false))
        assertEquals(1, b.search("kapenta", selo.Nachipa01.data.model.SearchFilters()).results.size)
    }
}

private val DemoData = selo.Nachipa01.data.repo.DemoData
