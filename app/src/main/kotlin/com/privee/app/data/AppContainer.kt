package com.privee.app.data

import android.content.Context
import com.privee.app.BuildConfig
import com.privee.app.PriveeApplication
import com.privee.app.push.PushEndpointSync
import com.privee.app.push.PushRegistration
import com.privee.app.push.BackgroundListening
import com.privee.app.push.BackgroundMessageService
import com.privee.net.AuthResult
import com.privee.net.PriveeApi
import com.privee.net.ServerUrl
import com.privee.net.fetchServerInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The selected server: its API client and the directory holding the account
 * and Signal state created on it.
 */
class ActiveServer(val config: ServerConfig, val api: PriveeApi, val directory: File) {
    internal val accounts = AccountStore(EncryptedFileStorage(File(directory, ACCOUNT_FILE)))

    /** The sessions signed into on this server from this device, see [KnownSessionStore]. */
    val knownSessions = KnownSessionStore(EncryptedFileStorage(File(directory, KNOWN_SESSIONS_FILE)))

    /** The link that opens a conversation with [sessionName] on this server, in a browser. */
    fun shareLink(sessionName: String) = shareLink(config.url, sessionName)

    /** The link that opens a conversation with [sessionName] on this server, in the app. */
    fun appLink(sessionName: String) = appLink(config.url, sessionName)

    companion object {
        const val ACCOUNT_FILE = "account.bin"
        const val KNOWN_SESSIONS_FILE = "sessions.bin"
    }
}

/** Application-wide dependencies, the selected server and the signed-in session. */
class AppContainer(private val context: Context) {
    private val languages get() = (context.applicationContext as PriveeApplication).languages
    val languageTag: String get() = languages.language.tag
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listenerPreferences = context.getSharedPreferences("background-listening", Context.MODE_PRIVATE)
    val backgroundListening = BackgroundListening(listenerPreferences.getBoolean("enabled", false)) { enabled ->
        check(listenerPreferences.edit().putBoolean("enabled", enabled).commit()) {
            "Could not save background listening preference"
        }
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // Never re-send the token or the recovery phrase elsewhere: a 3xx is an error.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val pushEndpoint = PushEndpointSync()

    private val servers = ServerStore(EncryptedFileStorage(File(context.noBackupFilesDir, "server.bin")))

    private val _server = MutableStateFlow<ActiveServer?>(null)

    /** The selected server, or `null` until the user picks one: nothing else is reachable without it. */
    val server: StateFlow<ActiveServer?> = _server.asStateFlow()

    private val _session = MutableStateFlow<PriveeSession?>(null)

    /** The signed-in session, or `null` when signed out. */
    val session: StateFlow<PriveeSession?> = _session.asStateFlow()

    /** The session name of the conversation on screen, to skip its notifications. */
    val activeChat = MutableStateFlow<String?>(null)

    /** A conversation requested by an app link or a notification, opened once signed in. */
    val pendingInvite = MutableStateFlow<Invite?>(null)

    /** Whether the app is in the foreground. */
    val foreground = MutableStateFlow(false)

    init {
        migrateLegacyData()
        servers.load()?.let { config ->
            val server = activate(config)
            server.accounts.load()?.let { start(server, it) }
        }
    }

    /**
     * Normalizes [address] and checks that it is a supported Privee server.
     * Throws [com.privee.net.ServerCheckException] otherwise.
     */
    suspend fun checkServer(address: String): ServerConfig {
        val url = ServerUrl.normalize(address, allowCleartext = BuildConfig.ALLOW_CLEARTEXT)
        val info = fetchServerInfo(url, client) { languages.language.tag }
        return ServerConfig(url, info.name)
    }

    /**
     * Selects a checked server; only while signed out. An account kept on the
     * device for that server (e.g. migrated from before servers were
     * configurable) signs in again.
     */
    @Synchronized
    fun selectServer(config: ServerConfig) {
        check(_session.value == null) { "Sign out before changing server" }
        servers.save(config)
        val server = activate(config)
        server.accounts.load()?.let { start(server, it) }
    }

    /** Forgets the selected server, back to the server screen; only while signed out. */
    @Synchronized
    fun changeServer() {
        check(_session.value == null) { "Sign out before changing server" }
        servers.clear()
        activeChat.value = null
        pendingInvite.value = null
        _server.value = null
    }

    private fun activate(config: ServerConfig): ActiveServer {
        val server = ActiveServer(
            config = config,
            api = PriveeApi(config.url, client) { languages.language.tag },
            directory = serverDirectory(context, config),
        )
        _server.value = server
        return server
    }

    fun signedIn(server: ActiveServer, result: AuthResult) {
        check(_server.value === server) { "The server changed while signing in" }
        _session.value?.stop()
        val account = Account(result.token, result.session)
        server.accounts.save(account)
        start(server, account)
        scope.launch { pushEndpoint.signedIn { server.api.registerPush(account.token, it) } }
    }

    private fun start(server: ActiveServer, account: Account) {
        forgetDeletedSession(server, account)
        val session = PriveeSession(server, account, client) {
            scope.launch { signOut(remote = false) }
        }
        _session.value = session
        session.start()
    }

    /**
     * Records the session as used on this server. When its name had another id,
     * the server deleted that session (after inactivity) and the name was
     * registered again: the Signal state kept for the old id is dropped.
     */
    private fun forgetDeletedSession(server: ActiveServer, account: Account) {
        runCatching {
            val deleted = server.knownSessions.record(account.session) ?: return
            EncryptedFileStorage(PriveeSession.signalFile(server, deleted)).clear()
        }
    }

    /** Called by the push distributor with the endpoint of this installation. */
    suspend fun onPushEndpoint(endpoint: String) {
        val session = _session.value
        pushEndpoint.update(endpoint, session?.let { { url -> it.server.api.registerPush(it.account.token, url) } })
    }

    /** Called by the push distributor when this installation is unregistered. */
    fun onPushUnregistered() = pushEndpoint.forget()

    /**
     * Signs out; the keys stay on the device for the next sign-in on the same server,
     * but the local hints about peers are removed.
     */
    suspend fun signOut(remote: Boolean = true) {
        val session = _session.value ?: return
        backgroundListening.disable()
        BackgroundMessageService.stop(context)
        _session.value = null
        session.stop()
        runCatching { session.signal.clearPeerHints() }
        PushRegistration.unregister(context)
        pushEndpoint.forget()
        val api = session.server.api
        if (remote) {
            runCatching { api.unregisterPush(session.account.token) }
            runCatching { api.logOut(session.account.token) }
        }
        session.server.accounts.clear()
    }

    /** Deletes every key and message of the session from the device, then signs out. */
    suspend fun forgetDevice() {
        val session = _session.value ?: return
        session.signal.wipe()
        runCatching { session.server.knownSessions.remove(session.account.session.sessionName) }
        signOut()
    }

    /**
     * Before servers were configurable, the account and Signal state lived at
     * the root of the storage and belonged to [BuildConfig.LEGACY_SERVER_URL].
     * They move to that server's directory, so they are reused only if the user
     * picks that server again. The legacy server is never selected automatically.
     */
    private fun migrateLegacyData() {
        val root = context.noBackupFilesDir
        val legacy = root.listFiles { file -> file.isFile && LEGACY_FILE.matches(file.name) }.orEmpty()
        if (legacy.isEmpty()) return
        val target = serverDirectory(context, ServerConfig(BuildConfig.LEGACY_SERVER_URL, null))
        target.mkdirs()
        for (file in legacy) {
            val destination = File(target, file.name)
            if (!destination.exists()) file.renameTo(destination)
        }
    }

    companion object {
        // AtomicFile may leave `.bak`/`.new` companions next to the files.
        private val LEGACY_FILE = Regex("^(account|signal-\\d+)\\.bin(\\.bak|\\.new)?$")

        fun serverDirectory(context: Context, config: ServerConfig) =
            File(File(context.noBackupFilesDir, "servers"), config.directoryName)
    }
}
