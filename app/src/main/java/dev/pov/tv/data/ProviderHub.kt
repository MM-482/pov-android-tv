package dev.pov.tv.data

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** All methods that can perform network I/O must be called on a worker thread. */
class ProviderHub internal constructor(private val credentials: ProviderCredentials,
    private val transport: ProviderTransport = LiveProviderTransport) {
    constructor(store: SecureStore) : this(EncryptedProviderCredentials(store), LiveProviderTransport)

    val specs: List<ProviderSpec> get() = SPECS
    private data class Session(val token: String, val username: String, val revision: String, val activation: String)
    private data class Profile(val id: String, val label: String, val token: String, val username: String, val revision: String) {
        fun json(provider: String) = JSONObject().put("id", id).put("provider", provider).put("label", label).put("token", token)
            .put("username", username).put("revision", revision)
    }

    fun connected(id: String): Boolean = synchronized(lock(id)) {
        credentials.get("$id.token").isNotBlank() && (id != "easynews" || credentials.get("easynews.username").isNotBlank())
    }
    fun accountRevision(id: String): String = synchronized(lock(id)) { syncProfiles(id); credentials.get("$id.account_revision") }
    fun accounts(providerId: String): List<ProviderAccountProfile> = synchronized(lock(providerId)) {
        val profiles = syncProfiles(providerId)
        val active = credentials.get("$providerId.profile_id")
        profiles.map { ProviderAccountProfile(it.id, providerId, it.label, it.id == active) }
    }
    fun connect(id: String, token: String, username: String = "", profileLabel: String? = null): ProviderAccount {
        val clean = if (id == "easynews") token else token.trim()
        require(clean.isNotBlank() && clean.length <= 4096 && clean.none { it.code < 32 || it.code == 127 }) { "Enter a valid provider credential." }
        if (id != "easynews") require(clean.all { it.code in 33..126 }) { "Enter a valid provider API key or token." }
        val user = username.trim()
        if (id == "easynews") require(user.isNotBlank() && ':' !in user && user.length <= 256 && user.none { it.code < 32 || it.code == 127 }) { "Enter your Easynews username." }
        val label = profileLabel?.trim()?.also { require(it.isNotBlank() && it.length <= 120 && it.none { c -> c.code < 32 || c.code == 127 }) { "Enter a name between 1 and 120 characters." } }
        val generation = synchronized(lock(id)) { syncProfiles(id); state(id) }
        val account = when (id) {
            "torbox" -> {
                val value = providerObject(transport.request("https://api.torbox.app/v1/api/user/me", headers = mapOf("Authorization" to "Bearer $clean")))
                val user = value.optJSONObject("data")
                if (!value.optBoolean("success") || user == null) throw ApiException(401, "TorBox did not verify this account.")
                ProviderAccount("torbox", user.providerString("email").ifBlank { "TorBox account" }, user.optInt("plan") > 0)
            }
            "easynews" -> EasynewsProvider(transport).account(clean, user)
            else -> backend(id).account(clean, user)
        }
        mutate(id) {
          synchronized(lock(id)) {
            check(state(id) == generation) { "The account changed while connecting. Please try again." }
            val profiles = syncProfiles(id)
            val expected = profileSnapshot(id)
            val activeId = expected["$id.profile_id"].orEmpty()
            val target = if (label != null) UUID.randomUUID().toString() else activeId.ifBlank { UUID.randomUUID().toString() }
            val name = label ?: profiles.singleOrNull { it.id == activeId }?.label ?: account.label.ifBlank { SPECS.first { it.id == id }.label }
            val profile = Profile(target, name, clean, user, UUID.randomUUID().toString())
            check(profiles.size < 100 || profiles.any { it.id == target }) { "Remove a saved account before adding another." }
            persist(id, expected, profiles.filterNot { it.id == target } + profile, profile)
          }
          true
        }
        return account
    }
    fun selectAccount(providerId: String, profileId: String) {
        mutate(providerId) { synchronized(lock(providerId)) {
            val profiles = syncProfiles(providerId)
            val target = profiles.singleOrNull { it.id == profileId } ?: error("This saved account is no longer available.")
            val expected = profileSnapshot(providerId)
            if (expected["$providerId.profile_id"] == profileId) false
            else { persist(providerId, expected, profiles, target); true }
        } }
    }
    fun removeAccount(providerId: String, profileId: String) {
        mutate(providerId) { synchronized(lock(providerId)) {
            val profiles = syncProfiles(providerId)
            require(profiles.any { it.id == profileId }) { "This saved account is no longer available." }
            val expected = profileSnapshot(providerId)
            val removedActive = expected["$providerId.profile_id"] == profileId
            val retained = profiles.filterNot { it.id == profileId }
            if (removedActive) persist(providerId, expected, retained, null)
            else saveProfiles(providerId, expected, retained)
            removedActive
        } }
    }
    fun disconnect(id: String) {
        mutate(id) { synchronized(lock(id)) {
            val profiles = syncProfiles(id)
            val expected = profileSnapshot(id)
            persist(id, expected, profiles.filterNot { it.id == expected["$id.profile_id"] }, null)
        }; true }
    }
    private fun mutate(id: String, work: () -> Boolean): Boolean = if (id == "torbox") TorBoxApi.externalCredentialsChanged(work) else work()
    fun account(id: String): ProviderAccount = session(id) { account ->
        val token = account.token
        when (id) {
            "torbox" -> torboxAccount(snapshotTorBox(account))
            "easynews" -> EasynewsProvider(sessionHttp(id, account)).account(token, account.username)
            else -> backend(id, account).account(token, "")
        }
    }
    fun cloud(id: String, folderId: String = "", expectedRevision: String? = null): List<ProviderFolder> = session(id, expectedRevision) { account ->
        val token = account.token
        when (id) {
            "torbox" -> listOf("torrents", "usenet", "webdl").flatMap { kind -> snapshotTorBox(account).cloud(kind).map {
                ProviderFolder(id, "$kind:${it.id}", it.name, it.ready)
            } }
            "easynews" -> emptyList()
            else -> backend(id, account).cloud(token, folderId)
        }
    }
    fun files(id: String, folderId: String, expectedRevision: String? = null): List<ProviderFile> = session(id, expectedRevision) { account ->
        val token = account.token
        if (id == "torbox") {
            val parts = folderId.split(':'); require(parts.size == 2) { "Invalid TorBox folder." }
            snapshotTorBox(account).folder(parts[0], parts[1].toLong()).files.map {
                ProviderFile("$folderId:${it.id}", it.name, it.size)
            }
        } else backend(id, account).files(token, folderId)
    }
    fun playback(id: String, file: ProviderFile, expectedRevision: String? = null): ProviderPlayback = session(id, expectedRevision) { account ->
        val token = account.token
        when (id) {
            "torbox" -> {
                val parts = file.id.split(':'); require(parts.size == 3) { "Invalid TorBox file." }
                ProviderPlayback(snapshotTorBox(account).streamUrl(parts[0], parts[1].toLong(), parts[2].toLong()))
            }
            "easynews" -> EasynewsProvider(sessionHttp(id, account)).playback(token, account.username, file)
            else -> backend(id, account).playback(token, file)
        }
    }
    /** Caller must compare the captured account revision before presenting the resolved result. */
    fun resolve(id: String, source: SourceItem, identity: PlaybackIdentity, expectedRevision: String? = null): ProviderPlayback =
      if (source.directUrl.isNotBlank()) directPlayback(source) else session(id, expectedRevision) { account ->
        val token = account.token
        when (id) {
            "torbox" -> ProviderPlayback(snapshotResolver(account).resolve(source, identity))
            "easynews" -> throw ApiException(0, "Choose an Easynews search result to play it.")
            else -> backend(id, account).resolve(token, source, identity)
        }
    }
    fun prepare(id: String, source: SourceItem, identity: PlaybackIdentity, expectedRevision: String? = null): ProviderPreparedSource =
      if (source.directUrl.isNotBlank()) ProviderPreparedSource(playback = directPlayback(source)) else session(id, expectedRevision) { account ->
        val token = account.token
        when (id) {
            "torbox" -> when (val value = snapshotResolver(account).prepare(source, identity)) {
                is PreparedSource.Direct -> ProviderPreparedSource(playback = ProviderPlayback(value.url))
                is PreparedSource.Files -> ProviderPreparedSource(files = value.files.map {
                    ProviderFile("${value.folder.kind}:${value.folder.id}:${it.id}", it.name, it.size)
                }, preferredFileId = value.preferredFileId?.let { "${value.folder.kind}:${value.folder.id}:$it" })
            }
            "easynews" -> throw ApiException(0, "Choose an Easynews search result to play it.")
            else -> backend(id, account).prepare(token, source, identity)
        }
    }
    fun searchEasynews(identity: PlaybackIdentity, expectedRevision: String? = null): List<ProviderFile> = session("easynews", expectedRevision) { account ->
        EasynewsProvider(sessionHttp("easynews", account)).search(account.token, account.username, identity)
    }
    private fun directPlayback(source: SourceItem): ProviderPlayback {
        val headers = source.requestHeaders
        require(headers.size <= 30) { "This direct source requires too many request headers." }
        val names = mutableSetOf<String>()
        headers.forEach { (key, value) ->
            val normalized = key.lowercase(java.util.Locale.ROOT)
            require(key.matches(Regex("[A-Za-z0-9-]{1,80}")) && names.add(normalized) &&
                normalized !in setOf("host", "content-length", "connection", "transfer-encoding") &&
                value.length <= 8192 && value.none { it == '\r' || it == '\n' || (it.code < 32 && it != '\t') || it.code == 127 }) {
                "This direct source requires unsupported request headers. Choose another source."
            }
        }
        // Only this source's explicit headers accompany it; the provider account credential is separate.
        return ProviderPlayback(providerHttps(source.directUrl), headers.toMap())
    }
    private fun state(id: String, expectedRevision: String? = null): Session = synchronized(lock(id)) {
        syncProfiles(id)
        val values = credentials.snapshot(listOf("$id.token", "$id.username", "$id.account_revision", "$id.active_generation"))
        val epoch = if (id == "torbox") TorBoxApi.accountEpoch().toString() else ""
        val account = Session(values["$id.token"].orEmpty(), values["$id.username"].orEmpty(), values["$id.account_revision"].orEmpty(), values["$id.active_generation"].orEmpty() + ":" + epoch)
        check(expectedRevision == null || expectedRevision == account.revision) { "The provider account changed. Choose this action again." }
        account
    }
    private fun ensureCurrent(id: String, account: Session) {
        check(state(id) == account) { "The provider account changed. Choose this action again." }
    }
    private fun <T> session(id: String, expectedRevision: String? = null, work: (Session) -> T): T {
        val account = state(id, expectedRevision)
        if (account.token.isBlank()) throw ApiException(401, "Connect this provider account in Settings first.")
        val result = work(account) // No provider lock is held while network I/O runs.
        ensureCurrent(id, account)
        return result
    }
    private fun profileSnapshot(id: String) = credentials.snapshot(listOf("$id.token", "$id.username", "$id.account_revision", "$id.active_generation", "$id.profile_id", "$id.profiles", "$id.profiles_initialized"))
    private fun readProfiles(id: String, raw: String): List<Profile> {
        if (raw.isBlank()) return emptyList()
        return try {
            require(raw.length <= 2 * 1024 * 1024)
            val array = JSONArray(raw)
            require(array.length() <= 101 && array.providerObjects().size == array.length())
            val values = array.providerObjects().map { row ->
                require(row.providerString("provider") == id)
                Profile(row.getString("id"), row.getString("label"), row.getString("token"), row.optString("username"), row.getString("revision")).also {
                    require(it.id.matches(Regex("[a-fA-F0-9-]{36}")) && it.label.isNotBlank() && it.token.isNotBlank() && it.revision.isNotBlank())
                }
            }
            require(values.map { it.id }.toSet().size == values.size)
            values
        } catch (_: Exception) { throw IllegalStateException("Saved provider accounts could not be read. They have not been replaced.") }
    }
    private fun serialized(id: String, values: List<Profile>) = JSONArray().apply { values.forEach { put(it.json(id)) } }.toString()
    private fun saveProfiles(id: String, expected: Map<String, String>, profiles: List<Profile>, extra: Map<String, String> = emptyMap()) {
        check(credentials.putIfUnchanged(expected, extra + ("$id.profiles" to serialized(id, profiles)))) { "The provider account changed. Reopen its account settings." }
    }
    private fun persist(id: String, expected: Map<String, String>, profiles: List<Profile>, active: Profile?) {
        saveProfiles(id, expected, profiles, mapOf("$id.profile_id" to active?.id.orEmpty(), "$id.token" to active?.token.orEmpty(),
            "$id.username" to active?.username.orEmpty(), "$id.account_revision" to (active?.revision ?: UUID.randomUUID().toString()),
            "$id.active_generation" to UUID.randomUUID().toString(), "$id.profiles_initialized" to "1"))
    }
    /** Imports old single-account settings and absorbs direct TorBox device-auth changes before any switch. */
    private fun syncProfiles(id: String): List<Profile> {
        repeat(3) {
            val expected = profileSnapshot(id)
            val profiles = readProfiles(id, expected["$id.profiles"].orEmpty())
            val activeId = expected["$id.profile_id"].orEmpty()
            val active = profiles.singleOrNull { it.id == activeId }
            val token = expected["$id.token"].orEmpty()
            val username = expected["$id.username"].orEmpty()
            val revision = expected["$id.account_revision"].orEmpty()
            if (token.isBlank()) {
                if (activeId.isBlank()) return profiles
                val retained = profiles.filterNot { it.id == activeId }
                if (credentials.putIfUnchanged(expected, mapOf("$id.profiles" to serialized(id, retained), "$id.profile_id" to "", "$id.profiles_initialized" to "1"))) return retained
            } else {
                val changedCredentials = active != null && (active.token != token || active.username != username)
                val actualRevision = if (revision.isBlank() || changedCredentials && revision == active?.revision ||
                    active == null && (id == "torbox" || expected["$id.profiles_initialized"] == "1")) UUID.randomUUID().toString() else revision
                val profile = Profile(active?.id ?: UUID.randomUUID().toString(), active?.label ?: SPECS.first { spec -> spec.id == id }.label + " account", token, username, actualRevision)
                val updated = if (active == null) profiles + profile else profiles.map { if (it.id == profile.id) profile else it }
                val extra = mutableMapOf("$id.profile_id" to profile.id, "$id.profiles" to serialized(id, updated), "$id.profiles_initialized" to "1")
                if (actualRevision != revision) { extra["$id.account_revision"] = actualRevision; extra["$id.active_generation"] = UUID.randomUUID().toString() }
                if (active == profile && expected["$id.profiles"] == extra["$id.profiles"]) return profiles
                if (credentials.putIfUnchanged(expected, extra)) return updated
            }
        }
        error("The provider account changed while reading its saved profiles. Try again.")
    }
    private fun sessionHttp(id: String, account: Session): ProviderTransport = object : ProviderTransport {
        override fun request(url: String, method: String, headers: Map<String, String>, fields: List<Pair<String, String>>?, json: org.json.JSONObject?): String {
            ensureCurrent(id, account)
            val result = transport.request(url, method, headers, fields, json)
            ensureCurrent(id, account)
            return result
        }
    }
    private fun backend(id: String, account: Session? = null): ProviderBackend {
        val http = if (account == null) transport else sessionHttp(id, account)
        return when (id) {
            "realdebrid" -> RealDebridProvider(http)
            "alldebrid" -> AllDebridProvider(http)
            "premiumize" -> PremiumizeProvider(http)
            "offcloud" -> OffcloudProvider(http)
            "debridlink" -> DebridLinkProvider(http)
            "putio" -> PutIoProvider(http)
            else -> throw IllegalArgumentException("Unsupported provider.")
        }
    }
    private fun snapshotTorBox(account: Session): TorBoxApi = TorBoxApi(object : TorBoxCredentials {
        override fun get(key: String): String { ensureCurrent("torbox", account); return if (key == "torbox.token") account.token else "" }
        override fun put(key: String, value: String) { error("Read-only account snapshot.") }
        override fun remove(key: String) { error("Read-only account snapshot.") }
    }, { url, method, headers, body -> providerObject(sessionHttp("torbox", account).request(url, method, headers, json = body)) })
    private fun snapshotResolver(account: Session): SourceResolver {
        val api = snapshotTorBox(account)
        return SourceResolver({ "" }, object : SourceTorBox {
            override fun cached(hashes: List<String>) = api.cached(hashes)
            override fun addMagnet(hash: String) = api.addMagnet(hash)
            override fun folder(kind: String, id: Long) = api.folder(kind, id)
            override fun streamUrl(kind: String, folderId: Long, fileId: Long) = api.streamUrl(kind, folderId, fileId)
        })
    }
    private fun torboxAccount(api: TorBoxApi): ProviderAccount = api.account().let {
        ProviderAccount("torbox", it.providerString("email").ifBlank { "TorBox account" }, it.optInt("plan") > 0, it.providerString("premium_expires_at"))
    }
    private fun lock(id: String): Any = LOCKS[id] ?: throw IllegalArgumentException("Unsupported provider.")
    companion object {
        val SPECS = listOf(
            ProviderSpec("torbox", "TorBox", "API key", "https://torbox.app/settings", true, true),
            ProviderSpec("realdebrid", "Real-Debrid", "Private API token", "https://real-debrid.com/apitoken", true, true),
            ProviderSpec("alldebrid", "AllDebrid", "API key", "https://alldebrid.com/apikeys/", true, true),
            ProviderSpec("premiumize", "Premiumize", "API key", "https://www.premiumize.me/account", true, true),
            ProviderSpec("offcloud", "Offcloud", "API key", "https://offcloud.com/#/account", true, true),
            ProviderSpec("debridlink", "Debrid-Link", "API key or access token", "https://debrid-link.com/webapp/apikey", true, true),
            ProviderSpec("putio", "put.io", "OAuth access token", "https://app.put.io/oauth", true, true),
            ProviderSpec("easynews", "Easynews", "Password", "https://members.easynews.com/", false, false, true))
        private val LOCKS = SPECS.associate { it.id to Any() }
    }
}
