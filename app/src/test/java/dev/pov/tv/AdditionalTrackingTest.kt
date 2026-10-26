package dev.pov.tv

import dev.pov.tv.data.ApiException
import dev.pov.tv.data.PlaybackIdentity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AdditionalTrackingTest {
    private val media = PlaybackIdentity("tt0133093", "movie", "The Matrix")
    private class Store : AdditionalPendingStore {
        var value = ""
        val consent = mutableMapOf<String, String>()
        override fun read() = value
        override fun write(value: String) { this.value = value }
        override fun enabledAccount(provider: String) = consent[provider].orEmpty()
        override fun writeWithConsent(value: String, provider: String, revision: String) { this.value = value; consent[provider] = revision }
        fun rows() = JSONObject(value.ifBlank { "{}" }).optJSONArray("events")
        fun count() = rows()?.length() ?: 0
    }
    private class Tracker(override val id: String = "simkl") : AdditionalTracker {
        var signedIn = true
        var revision = UUID.randomUUID().toString()
        val sent = mutableListOf<Triple<String, Double, String>>()
        var onSend: (() -> Unit)? = null
        override fun connected() = signedIn
        override fun accountRevision() = revision
        override fun send(identity: PlaybackIdentity, percent: Double, action: String, revision: String): Boolean {
            assertEquals(this.revision, revision)
            sent += Triple(action, percent, revision)
            onSend?.invoke()
            return true
        }
    }
    private class Harness(val tracker: Tracker = Tracker(), val store: Store = Store(), capacity: Int = 80,
        val trackers: List<AdditionalTracker> = listOf(tracker), optIn: Boolean = true) {
        var now = 1000L
        val work = ArrayDeque<() -> Unit>()
        val later = mutableListOf<Pair<Long, () -> Unit>>()
        val queue = AdditionalTrackingQueue(store, trackers, { work.add(it) }, { delay, task -> later += delay to task }, { now }, capacity)
        init { if (optIn) trackers.filter { it.connected() }.forEach { queue.setEnabled(it.id, true) } }
        fun one() { work.removeFirst().invoke() }
        fun drain() { while (work.isNotEmpty()) one() }
    }

    @Test fun accountIsCapturedBeforeAsyncDispatchAndNewAccountCannotInheritIt() {
        val h = Harness()
        h.queue.record(media, 30.0, "pause")
        assertEquals(1, h.store.count())
        h.tracker.revision = UUID.randomUUID().toString()
        h.drain()
        assertTrue(h.tracker.sent.isEmpty())
        assertEquals(0, h.store.count())
    }

    @Test fun connectingAfterAnEventDoesNotRetroactivelyCreateATransaction() {
        val h = Harness(); h.tracker.signedIn = false
        h.queue.record(media, 30.0, "pause")
        h.tracker.signedIn = true
        h.queue.retry(); h.drain()
        assertTrue(h.tracker.sent.isEmpty())
    }

    @Test fun latestStopSupersedesQueuedPauseForTheSameMedia() {
        val h = Harness()
        h.queue.record(media, 25.0, "pause")
        h.queue.record(media, 26.0, "stop")
        assertEquals(1, h.store.count())
        h.drain()
        assertEquals(listOf("stop"), h.tracker.sent.map { it.first })
        assertEquals(0, h.store.count())
    }

    @Test fun lateAcknowledgementCannotDeleteANewerEvent() {
        val h = Harness()
        h.tracker.onSend = { h.tracker.onSend = null; h.queue.record(media, 46.0, "stop") }
        h.queue.record(media, 45.0, "pause")
        h.one()
        assertEquals("stop", h.store.rows()!!.getJSONObject(0).getString("action"))
        h.drain()
        assertEquals(listOf("pause", "stop"), h.tracker.sent.map { it.first })
    }

    @Test fun retryDeadlineHonors429AndHasOnlyOneAutomaticRetry() {
        val h = Harness()
        h.tracker.onSend = { throw ApiException(429, "Wait.", retryAfterSeconds = 45) }
        h.queue.record(media, 40.0, "pause"); h.drain()
        assertEquals(45_000L, h.later.single().first)
        h.now += 20_000; h.queue.retry(); h.drain()
        assertEquals(1, h.tracker.sent.size)
        h.now += 25_000; h.later.single().second.invoke(); h.drain()
        assertEquals(2, h.tracker.sent.size)
        assertEquals(1, h.later.size)
        assertEquals(2, h.store.rows()!!.getJSONObject(0).getInt("attempts"))
        h.tracker.onSend = null; h.now += 45_000
        h.queue.retry(); h.drain()
        assertEquals(3, h.tracker.sent.size)
        assertEquals(0, h.store.count())
    }

    @Test fun simkl400RateLimitWaitsAtLeastTwentySeconds() {
        val h = Harness()
        h.tracker.onSend = { throw ApiException(400, "Rate limit.", "RATE_LIMIT") }
        h.queue.record(media, 55.0, "stop"); h.drain()
        assertEquals(20_000L, h.later.single().first)
        assertEquals(1, h.store.count())
    }

    @Test fun clearInvalidatesDurableAndTransientWorkAlreadyQueued() {
        val h = Harness()
        h.queue.record(media, 40.0, "pause")
        val other = media.copy(imdbId = "tt1375666")
        h.queue.record(other, 1.0, "start")
        h.queue.clear("simkl"); h.drain()
        assertEquals(0, h.store.count())
        assertTrue(h.tracker.sent.isEmpty())
    }

    @Test fun repeatedPlatformStopAndLatePauseAreCoalesced() {
        val h = Harness()
        h.queue.record(media, 80.0, "stop")
        h.queue.record(media, 80.05, "stop")
        h.queue.record(media, 80.0, "pause")
        h.drain()
        assertEquals(1, h.tracker.sent.size)
        assertEquals("stop", h.tracker.sent.single().first)
    }

    @Test fun startSupersedesUnsentPauseWithoutPersistingAHeartbeat() {
        val h = Harness()
        h.queue.record(media, 25.0, "pause")
        h.queue.record(media, 25.0, "start")
        assertEquals(0, h.store.count())
        h.drain()
        assertEquals(listOf("start"), h.tracker.sent.map { it.first })
    }

    @Test fun oneProviderFailureDoesNotLoseTheOtherProvidersAcknowledgement() {
        val simkl = Tracker("simkl")
        val mdb = Tracker("mdblist")
        val h = Harness(simkl, trackers = listOf(simkl, mdb))
        simkl.onSend = { throw ApiException(500, "Unavailable.") }
        h.queue.record(media, 65.0, "pause"); h.drain()
        assertEquals(1, mdb.sent.size)
        assertEquals(1, h.store.count())
        assertEquals("simkl", h.store.rows()!!.getJSONObject(0).getString("provider"))
    }

    @Test fun pendingTransactionsAreBoundedAndContainNoRequestSecrets() {
        val h = Harness(capacity = 3)
        repeat(5) { index ->
            h.now++
            h.queue.record(media.copy(imdbId = "tt${1_000_000 + index}"), index.toDouble(), "pause")
        }
        assertEquals(3, h.store.count())
        assertFalse(h.store.value.contains("Authorization"))
        assertFalse(h.store.value.contains("api_key"))
        assertFalse(h.store.value.contains("https://"))
        assertTrue(h.store.value.contains("event_revision"))
    }

    @Test fun staleAcknowledgementAcrossQueueInstancesPreservesNewerStoredEvent() {
        val sharedStore = Store()
        val tracker = Tracker()
        val first = Harness(tracker, sharedStore)
        val second = Harness(tracker, sharedStore)
        tracker.onSend = { tracker.onSend = null; second.queue.record(media, 76.0, "stop") }
        first.queue.record(media, 75.0, "pause"); first.one()
        assertEquals("stop", sharedStore.rows()!!.getJSONObject(0).getString("action"))
        second.drain()
        assertEquals(0, sharedStore.count())
    }

    @Test fun defaultOffMeansNoQueueOrNetworkEvenWhenAccountIsConnected() {
        val h = Harness(optIn = false)
        assertFalse(h.queue.isEnabled("simkl"))
        h.queue.record(media, 25.0, "pause"); h.queue.retry(); h.drain()
        assertEquals(0, h.store.count())
        assertTrue(h.tracker.sent.isEmpty())
    }

    @Test fun explicitConsentEnablesOnlyTheChosenService() {
        val simkl = Tracker("simkl"); val mdb = Tracker("mdblist")
        val h = Harness(simkl, trackers = listOf(simkl, mdb), optIn = false)
        h.queue.setEnabled("simkl", true)
        h.queue.record(media, 25.0, "pause"); h.drain()
        assertEquals(1, simkl.sent.size)
        assertTrue(mdb.sent.isEmpty())
        assertFalse(h.queue.isEnabled("mdblist"))
    }

    @Test fun disablingClearsLatestWorkAndInvalidatesScheduledRetries() {
        val h = Harness()
        h.tracker.onSend = { throw ApiException(429, "Wait.", retryAfterSeconds = 20) }
        h.queue.record(media, 25.0, "pause"); h.drain()
        h.queue.setEnabled("simkl", false)
        assertEquals(0, h.store.count())
        assertFalse(h.queue.isEnabled("simkl"))
        h.now += 20_000; h.later.single().second.invoke(); h.queue.retry(); h.drain()
        assertEquals(1, h.tracker.sent.size)
    }

    @Test fun accountRevisionChangeRequiresFreshConsentEvenAfterReconnect() {
        val h = Harness()
        h.queue.record(media, 25.0, "pause")
        h.tracker.revision = UUID.randomUUID().toString()
        assertFalse(h.queue.isEnabled("simkl"))
        h.queue.record(media, 26.0, "pause"); h.drain()
        assertTrue(h.tracker.sent.isEmpty())
        h.queue.setEnabled("simkl", true)
        h.queue.record(media, 27.0, "pause"); h.drain()
        assertEquals(1, h.tracker.sent.size)
    }
}
