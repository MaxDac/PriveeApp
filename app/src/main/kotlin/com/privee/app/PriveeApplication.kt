package com.privee.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.privee.app.data.AppContainer
import com.privee.app.push.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class PriveeApplication : Application() {
    lateinit var languages: LanguagePreferences
        private set
    lateinit var container: AppContainer
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        languages = LanguagePreferences(this)
        languages.initialize()
        container = AppContainer(this)
        Notifications.createChannel(this)

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                container.foreground.value = true
                container.session.value?.socket?.connect()
            }

            override fun onStop(owner: LifecycleOwner) {
                container.foreground.value = false
            }
        })

        // New messages announced on the session channel, outside their conversation.
        scope.launch {
            container.session
                .flatMapLatest { session -> session?.incoming?.map { session.server.config.url to it } ?: emptyFlow() }
                .collect { (serverUrl, notice) ->
                    val onScreen = container.foreground.value && container.activeChat.value == notice.from
                    if (!onScreen) Notifications.newMessage(this@PriveeApplication, notice.from, serverUrl)
                }
        }
    }
}
