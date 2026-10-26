package dev.pov.tv

import android.content.Context
import dev.pov.tv.data.ApiException
import dev.pov.tv.data.MdbListApi
import dev.pov.tv.data.PlaybackIdentity
import dev.pov.tv.data.SecureStore
import dev.pov.tv.data.SimklApi
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Optional trackers are independent of the existing Trakt path and never block playback on I/O. */
object AdditionalTracking {
    private val worker = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "pov-additional-tracking").apply { isDaemon = true }
    }
    @Volatile private var instance: AdditionalTrackingQueue? = null

    fun record(context: Context, identity: PlaybackIdentity, percent: Double, action: String) {
        runCatching { queue(context).record(identity, percent, action) }
    }
    fun retry(context: Context) { runCatching { queue(context).retry() } }
    fun clear(context: Context, provider: String) { runCatching { queue(context).clear(provider) } }
    fun isEnabled(context: Context, provider: String): Boolean = runCatching { queue(context).isEnabled(provider) }.getOrDefault(false)
    fun setEnabled(context: Context, provider: String, enabled: Boolean): Boolean = runCatching {
        queue(context).setEnabled(provider, enabled); true
    }.getOrDefault(false)

    private fun queue(context: Context): AdditionalTrackingQueue = instance ?: synchronized(this) {
        instance ?: run {
            val store = SecureStore(context.applicationContext)
            val mdblist = MdbListApi(store)
            val simkl = SimklApi(store)
            val trackers = listOf(
                Tracker("mdblist", mdblist::connected, mdblist::accountRevision, mdblist::progressForAccount),
                Tracker("simkl", simkl::connected, simkl::accountRevision, simkl::progressForAccount)
            )
            AdditionalTrackingQueue(object : AdditionalPendingStore {
                override fun read() = store.get("extra_tracking.pending")
                override fun write(value: String) = store.put("extra_tracking.pending", value)
                override fun enabledAccount(provider: String): String = if (store.get("tracking.$provider.enabled") == "true") store.get("tracking.$provider.account_revision") else ""
                override fun writeWithConsent(value: String, provider: String, revision: String) = store.putAll(mapOf(
                    "extra_tracking.pending" to value,
                    "tracking.$provider.enabled" to if (revision.isBlank()) "false" else "true",
                    "tracking.$provider.account_revision" to revision))
            }, trackers, execute = { work -> worker.execute { work() } }, schedule = { delay, work ->
                worker.schedule({ work() }, delay, TimeUnit.MILLISECONDS); Unit
            }).also { instance = it }
        }
    }

    private class Tracker(
        override val id: String, private val isConnected: () -> Boolean, private val revision: () -> String,
        private val progress: (PlaybackIdentity, Double, String, String) -> Boolean
    ) : AdditionalTracker {
        override fun connected() = isConnected()
        override fun accountRevision() = revision()
        override fun send(identity: PlaybackIdentity, percent: Double, action: String, revision: String) = progress(identity, percent, action, revision)
    }
}

internal interface AdditionalTracker {
    val id: String
    fun connected(): Boolean
    fun accountRevision(): String
    fun send(identity: PlaybackIdentity, percent: Double, action: String, revision: String): Boolean
}
internal interface AdditionalPendingStore {
    fun read(): String
    fun write(value: String)
    fun enabledAccount(provider: String): String
    fun writeWithConsent(value: String, provider: String, revision: String)
}

/** Injectable queue core. Every acknowledgement re-reads storage and compares the exact event ID. */
internal class AdditionalTrackingQueue(
    private val store: AdditionalPendingStore,
    trackers: List<AdditionalTracker>,
    private val execute: (() -> Unit) -> Unit,
    private val schedule: (Long, () -> Unit) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val capacity: Int = 80
) {
    private val trackers = trackers.associateBy { it.id }
    private data class Event(
        val provider: String, val account: String, val identity: PlaybackIdentity,
        val percent: Double, val action: String, val revision: String = UUID.randomUUID().toString(),
        val createdAt: Long, val nextAttempt: Long = 0, val attempts: Int = 0
    ) {
        val durable get() = action != "start"
        val key get() = "$provider|$account|${identity.type}|${identity.imdbId}|${identity.season}|${identity.episode}"
    }
    private data class Accepted(val action: String, val percent: Double, val at: Long)

    fun record(identity: PlaybackIdentity, percent: Double, action: String) {
        if (action !in setOf("start", "pause", "stop") || !percent.isFinite() ||
            !identity.imdbId.matches(Regex("tt[0-9]{5,12}")) || identity.type !in setOf("movie", "series") ||
            (identity.type == "series" && (identity.season < 0 || identity.episode <= 0))) return
        // Capture connected account revisions synchronously, before any asynchronous work begins.
        val accounts = trackers.values.mapNotNull { tracker ->
            runCatching {
                if (!tracker.connected()) null else tracker.accountRevision().takeIf {
                    it.isNotBlank() && store.enabledAccount(tracker.id) == it
                }?.let { tracker.id to it }
            }.getOrNull()
        }
        accounts.forEach { (provider, account) ->
            val event = Event(provider, account, identity, percent.coerceIn(0.0, 100.0), action, createdAt = clock())
            val accepted = synchronized(queueLock) {
                if (store.enabledAccount(provider) != account || trackers[provider]?.accountRevision() != account) return@synchronized false
                val previous = recent[event.key]
                val close = previous != null && event.createdAt - previous.at in 0..2000 && abs(previous.percent - event.percent) < 0.2
                if (close && (previous!!.action == action || (previous.action == "stop" && action == "pause"))) false
                else {
                    recent[event.key] = Accepted(action, event.percent, event.createdAt)
                    while (recent.size > 320) recent.remove(recent.keys.first())
                    if (event.durable) newest.remove(event.key) else newest[event.key] = event.revision
                    val pending = readEvents().filterNot { it.provider == provider &&
                        (it.account != account || it.key == event.key) }.toMutableList()
                    // A real resume supersedes an older unsent pause, even if the start is transient.
                    if (event.durable) pending += event
                    writeEvents(pending)
                    true
                }
            }
            if (accepted) execute { dispatch(event) }
        }
    }

    /** Explicit foreground/network/account-recovery retry; respects a persisted Retry-After deadline. */
    fun retry() {
        execute {
            val snapshot = synchronized(queueLock) { readEvents() }
            snapshot.forEach { event -> if (clock() >= event.nextAttempt) dispatch(event) }
        }
    }

    fun clear(provider: String) {
        if (provider !in trackers) return
        synchronized(queueLock) {
            clearWork(provider, "")
        }
    }

    fun isEnabled(provider: String): Boolean = synchronized(queueLock) {
        val tracker = trackers[provider] ?: return@synchronized false
        tracker.connected() && tracker.accountRevision().let { it.isNotBlank() && store.enabledAccount(provider) == it }
    }

    /** Explicit opt-in starts with future player actions; it never replays old pending transactions. */
    fun setEnabled(provider: String, enabled: Boolean) = synchronized(queueLock) {
        val tracker = trackers[provider] ?: throw IllegalArgumentException("Unknown tracking provider.")
        val revision = if (enabled) tracker.accountRevision().takeIf { tracker.connected() && it.isNotBlank() }
            ?: throw IllegalStateException("Connect this account before enabling automatic tracking.") else ""
        clearWork(provider, revision)
    }

    private fun clearWork(provider: String, consentRevision: String) {
            store.writeWithConsent(encodeEvents(readEvents().filterNot { it.provider == provider }), provider, consentRevision)
            newest.keys.filter { it.startsWith("$provider|") }.toList().forEach(newest::remove)
            recent.keys.filter { it.startsWith("$provider|") }.toList().forEach(recent::remove)
    }

    private fun dispatch(event: Event) { synchronized(dispatchLock) {
        var attempted = event
        try {
            val tracker = trackers[event.provider] ?: return
            if (!tracker.connected() || tracker.accountRevision() != event.account || store.enabledAccount(event.provider) != event.account) { acknowledge(event); return }
            val current = synchronized(queueLock) {
                if (event.durable) readEvents().firstOrNull { it.key == event.key && it.revision == event.revision }
                else event.takeIf { newest[it.key] == it.revision }
            } ?: return
            attempted = current
            if (clock() < current.nextAttempt) return
            // Snapshot was already captured at record time; account changes never inherit this event.
            if (!isEnabled(current.provider)) { acknowledge(current); return }
            val sent = tracker.send(current.identity, current.percent, current.action, current.account)
            if (sent || !tracker.connected() || tracker.accountRevision() != current.account) acknowledge(current)
            else failed(current, ApiException(0, "The tracking update was not confirmed."))
        } catch (error: Exception) {
            failed(attempted, error)
        }
    } }

    private fun acknowledge(event: Event) = synchronized(queueLock) {
        if (event.durable) writeEvents(readEvents().filterNot { it.key == event.key && it.revision == event.revision })
        if (newest[event.key] == event.revision) newest.remove(event.key)
    }

    private fun failed(event: Event, error: Exception) {
        val api = error as? ApiException
        val rateLimited = api?.code == 429 || (api?.code == 400 && api.errorKey.equals("RATE_LIMIT", true))
        val delay = if (rateLimited) maxOf(if (event.provider == "simkl") 20 else 5, api?.retryAfterSeconds ?: 0) * 1000L
            else if (api?.code in setOf(401, 403, 412)) 60_000L else 5_000L
        val retryable = rateLimited || api == null || api.code == 0 || api.code in 500..599
        val updated = event.copy(attempts = event.attempts + 1, nextAttempt = clock() + delay)
        val stillCurrent = synchronized(queueLock) {
            if (event.durable) {
                val pending = readEvents().toMutableList()
                val index = pending.indexOfFirst { it.key == event.key && it.revision == event.revision }
                if (index < 0) false else { pending[index] = updated; writeEvents(pending); true }
            } else newest[event.key] == event.revision
        }
        // One delayed retry per event is a response to a real transaction, never a progress heartbeat.
        if (stillCurrent && retryable && updated.attempts <= 1 && delay <= 120_000) {
            schedule(delay) { execute { dispatch(updated) } }
        } else if (!event.durable) synchronized(queueLock) {
            if (newest[event.key] == event.revision) newest.remove(event.key)
        }
    }

    private fun readEvents(): List<Event> {
        val raw = store.read()
        if (raw.isBlank()) return emptyList()
        val rows = try { JSONObject(raw).optJSONArray("events") } catch (_: Exception) { null } ?: return emptyList()
        return (0 until minOf(rows.length(), 160)).mapNotNull { index ->
            try {
                val row = rows.getJSONObject(index)
                val identity = PlaybackIdentity.fromJson(row.getJSONObject("media"))
                val provider = row.getString("provider")
                val account = row.getString("account")
                val action = row.getString("action")
                val percent = row.getDouble("percent")
                val revision = row.getString("event_revision")
                if (provider !in trackers || account.isBlank() || account.length > 256 || revision.isBlank() ||
                    action !in setOf("pause", "stop") || !percent.isFinite() || percent !in 0.0..100.0 ||
                    !identity.imdbId.matches(Regex("tt[0-9]{5,12}")) || identity.type !in setOf("movie", "series") ||
                    (identity.type == "series" && (identity.season < 0 || identity.episode <= 0))) null
                else Event(provider, account, identity, percent, action, revision, row.optLong("created_at"),
                    row.optLong("next_attempt"), row.optInt("attempts").coerceIn(0, 10000))
            } catch (_: Exception) { null }
        }.distinctBy { it.key }
    }

    private fun writeEvents(events: List<Event>) {
        store.write(encodeEvents(events))
    }
    private fun encodeEvents(events: List<Event>): String {
        val bounded = events.asReversed().sortedByDescending { it.createdAt }.take(capacity.coerceIn(1, 80))
        val rows = JSONArray()
        bounded.forEach { event -> rows.put(JSONObject().put("provider", event.provider).put("account", event.account)
            .put("media", event.identity.toJson()).put("percent", event.percent).put("action", event.action)
            .put("event_revision", event.revision).put("created_at", event.createdAt)
            .put("next_attempt", event.nextAttempt).put("attempts", event.attempts)) }
        return JSONObject().put("version", 1).put("events", rows).toString()
    }

    companion object {
        private val queueLock = Any()
        private val dispatchLock = Any()
        private val newest = mutableMapOf<String, String>()
        private val recent = linkedMapOf<String, Accepted>()
    }
}
