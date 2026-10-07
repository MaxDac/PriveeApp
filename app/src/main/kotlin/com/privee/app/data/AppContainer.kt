package com.privee.app.data

import android.content.Context
import com.privee.app.BuildConfig
import com.privee.app.push.PushRegistration
import com.privee.net.AuthResult
import com.privee.net.PriveeApi
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

/** Application-wide dependencies and the signed-in session. */
class AppContainer(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val api = PriveeApi(BuildConfig.SERVER_URL, client)

    private val accounts = AccountStore(EncryptedFileStorage(File(context.noBackupFilesDir, "account.bin")))

    private val _session = MutableStateFlow<PriveeSession?>(null)

    /** The signed-in session, or `null` when signed out. */
    val session: StateFlow<PriveeSession?> = _session.asStateFlow()

    /** The session name of the conversation on screen, to skip its notifications. */
    val activeChat = MutableStateFlow<String?>(null)

    /** Whether the app is in the foreground. */
    val foreground = MutableStateFlow(false)

    init {
        accounts.load()?.let(::start)
    }

    fun signedIn(result: AuthResult) {
        _session.value?.stop()
        val account = Account(result.token, result.session)
        accounts.save(account)
        start(account)
    }

    private fun start(account: Account) {
        val session = PriveeSession(context, account, client) {
            scope.launch { signOut(remote = false) }
        }
        _session.value = session
        session.start()
    }

    /** Called by the push distributor with the endpoint of this installation. */
    suspend fun onPushEndpoint(endpoint: String) {
        val account = _session.value?.account ?: return
        runCatching { api.registerPush(account.token, endpoint) }
    }

    /** Signs out; the keys stay on the device for the next sign-in. */
    suspend fun signOut(remote: Boolean = true) {
        val session = _session.value ?: return
        _session.value = null
        session.stop()
        PushRegistration.unregister(context)
        if (remote) {
            runCatching { api.unregisterPush(session.account.token) }
            runCatching { api.logOut(session.account.token) }
        }
        accounts.clear()
    }

    /** Deletes every key and message of the session from the device, then signs out. */
    suspend fun forgetDevice() {
        val session = _session.value ?: return
        session.signal.wipe()
        signOut()
    }
}
