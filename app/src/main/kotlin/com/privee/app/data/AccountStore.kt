package com.privee.app.data

import com.privee.net.SessionInfo
import com.privee.signal.StateStorage
import org.json.JSONObject

/** The signed-in session of this device and its API token. */
data class Account(val token: String, val session: SessionInfo)

/** Persists the [Account], encrypted. */
class AccountStore(private val storage: StateStorage) {
    fun load(): Account? = runCatching {
        val json = JSONObject(storage.load() ?: return null)
        Account(
            token = json.getString("token"),
            session = SessionInfo(
                id = json.getLong("id"),
                sessionName = json.getString("session_name"),
                isQuick = json.getBoolean("is_quick"),
            ),
        )
    }.getOrNull()

    fun save(account: Account) {
        val json = JSONObject()
            .put("token", account.token)
            .put("id", account.session.id)
            .put("session_name", account.session.sessionName)
            .put("is_quick", account.session.isQuick)
        storage.save(json.toString())
    }

    fun clear() = storage.clear()
}
