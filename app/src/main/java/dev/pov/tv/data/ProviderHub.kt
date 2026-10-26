package dev.pov.tv.data

import java.util.UUID

/** All methods that can perform network I/O must be called on a worker thread. */
class ProviderHub internal constructor(private val credentials: ProviderCredentials,
    private val transport: ProviderTransport = LiveProviderTransport) {
    constructor(store: SecureStore) : this(EncryptedProviderCredentials(store), LiveProviderTransport)

    val specs: List<ProviderSpec> get() = SPECS
    private data class Session(val token: String, val username: String, val revision: String)

    fun connected(id: String): Boolean = synchronized(lock(id)) {
        credentials.get("$id.token").isNotBlank() && (id != "easynews" || credentials.get("easynews.username").isNotBlank())
    }
    fun accountRevision(id: String): String = synchronized(lock(id)) { credentials.get("$id.account_revision") }
    fun connect(id: String, token: String, username: String = ""): ProviderAccount {
        val clean = if (id == "easynews") token else token.trim()
        require(clean.isNotBlank() && clean.length <= 4096 && clean.none { it.code < 32 || it.code == 127 }) { "Enter a valid provider credential." }
        if (id != "easynews") require(clean.all { it.code in 33..126 }) { "Enter a valid provider API key or token." }
        val user = username.trim()
        if (id == "easynews") require(user.isNotBlank() && ':' !in user && user.length <= 256 && user.none { it.code < 32 || it.code == 127 }) { "Enter your Easynews username." }
        val generation = state(id)
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
        synchronized(lock(id)) {
            check(state(id) == generation) { "The account changed while connecting. Please try again." }
            credentials.putAll(mapOf("$id.token" to clean, "$id.username" to user,
                "$id.account_revision" to UUID.randomUUID().toString()))
        }
        return account
    }
    fun disconnect(id: String) = synchronized(lock(id)) {
        credentials.putAll(mapOf("$id.token" to "", "$id.username" to "", "$id.account_revision" to UUID.randomUUID().toString()))
    }
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
    fun resolve(id: String, source: SourceItem, identity: PlaybackIdentity, expectedRevision: String? = null): ProviderPlayback = session(id, expectedRevision) { account ->
        val token = account.token
        if (source.directUrl.isNotBlank()) return@session ProviderPlayback(providerHttps(source.directUrl))
        when (id) {
            "torbox" -> ProviderPlayback(snapshotResolver(account).resolve(source, identity))
            "easynews" -> throw ApiException(0, "Choose an Easynews search result to play it.")
            else -> backend(id, account).resolve(token, source, identity)
        }
    }
    fun prepare(id: String, source: SourceItem, identity: PlaybackIdentity, expectedRevision: String? = null): ProviderPreparedSource = session(id, expectedRevision) { account ->
        val token = account.token
        if (source.directUrl.isNotBlank()) return@session ProviderPreparedSource(playback = ProviderPlayback(providerHttps(source.directUrl)))
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
    private fun state(id: String, expectedRevision: String? = null): Session = synchronized(lock(id)) {
        val account = Session(credentials.get("$id.token"), credentials.get("$id.username"), credentials.get("$id.account_revision"))
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
            ProviderSpec("easynews", "Easynews", "Password", "https://members.easynews.com/", false, false, true))
        private val LOCKS = SPECS.associate { it.id to Any() }
    }
}
