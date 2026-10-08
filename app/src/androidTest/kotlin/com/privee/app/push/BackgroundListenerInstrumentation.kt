package com.privee.app.push

import android.app.Activity
import android.app.Instrumentation
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import com.privee.app.MainActivity
import com.privee.app.PriveeApplication
import com.privee.app.localizedContext
import com.privee.app.data.EncryptedFileStorage
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger

/** Runs on a fresh debug install using only platform test APIs and a device-local mock server. */
class BackgroundListenerInstrumentation : Instrumentation() {
    private var prepareRestart = false
    private var cleanupRestart = false

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        prepareRestart = arguments?.getString("prepareRestart") == "true"
        cleanupRestart = arguments?.getString("cleanupRestart") == "true"
        start()
    }

    override fun onStart() {
        waitForIdleSync()
        val result = Bundle()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val container = (targetContext.applicationContext as PriveeApplication).container
        var ownsAccount = false
        var keepAccount = false
        var resultCode = Activity.RESULT_CANCELED
        try {
            if (cleanupRestart) {
                val selected = checkNotNull(container.server.value) {
                    EncryptedFileStorage(File(targetContext.noBackupFilesDir, "server.bin")).load()
                    "Test server was not restored"
                }
                val session = checkNotNull(container.session.value) {
                    EncryptedFileStorage(File(selected.directory, "account.bin")).load()
                    "Test account was not restored"
                }
                check(session.account.token == "local-test" &&
                    session.account.session.sessionName == "listener-test" &&
                    session.server.config.url.startsWith("http://127.0.0.1:")
                ) { "Refusing to clear an account not created by this test" }
                runBlocking { container.signOut(remote = false) }
                await("restart fixture cleanup") { !container.backgroundListening.running.value }
                targetContext.getSystemService(NotificationManager::class.java).cancelAll()
                result.putString("stream", "Passed: restart fixture cleanup.\n")
                finish(Activity.RESULT_OK, result)
                return
            }
            check(container.session.value == null) { "Use a fresh debug install; refusing to replace an existing account" }
            check(Notifications.listenerAllowed(targetContext)) { "Grant notification permission before running" }
            targetContext.getSystemService(NotificationManager::class.java).cancelAll()
            ListenerTestServer().use { server ->
                val config = runBlocking { container.checkServer(server.url) }
                runOnMainSync { container.selectServer(config) }
                val selected = requireNotNull(container.server.value)
                val auth = runBlocking { selected.api.register(null, null, true) }
                runOnMainSync { container.signedIn(selected, auth) }
                ownsAccount = true
                val activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                val session = requireNotNull(container.session.value)
                await("foreground session connection") { container.foreground.value && session.connected.value && server.joined.get() > 0 }
                val notices = AtomicInteger()
                scope.launch(start = CoroutineStart.UNDISPATCHED) { session.incoming.collect { notices.incrementAndGet() } }
                val manager = targetContext.getSystemService(NotificationManager::class.java)

                runOnMainSync {
                    container.backgroundListening.enable()
                    check(BackgroundMessageService.start(activity))
                }
                await("foreground listener") { container.backgroundListening.running.value && manager.activeNotifications.any { it.id == Notifications.LISTENER_ID } }
                runOnMainSync { check(BackgroundMessageService.start(activity)) }
                await("connected status after repeated start") {
                    manager.activeNotifications.firstOrNull { it.id == Notifications.LISTENER_ID }
                        ?.notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ==
                        targetContext.localizedContext().getString(com.privee.app.R.string.listener_connected)
                }

                if (prepareRestart) {
                    runOnMainSync { activity.moveTaskToBack(true) }
                    await("restart fixture background lifecycle") { !container.foreground.value }
                    keepAccount = true
                    result.putString("stream", "Prepared restart fixture: local test account and enabled listener retained; mock server will close.\n")
                    return@use
                }

                container.activeChat.value = "smoke-sender"
                server.emit()
                await("visible conversation notice") { notices.get() >= 1 }
                runOnMainSync { }
                Thread.sleep(250)
                check(manager.activeNotifications.none { it.tag == "smoke-sender" }) { "Visible chat produced an alert" }
                container.activeChat.value = null
                server.emit()
                await("foreground outside-chat alert") { manager.activeNotifications.any { it.tag == "smoke-sender" } }
                manager.cancel("smoke-sender", 1)

                runOnMainSync { activity.moveTaskToBack(true) }
                await("background lifecycle") { !container.foreground.value }
                server.emit()
                await("background message alert") { manager.activeNotifications.any { it.tag == "smoke-sender" } }
                manager.cancel("smoke-sender", 1)

                try {
                    shell("input keyevent 223")
                    await("screen off") { !targetContext.getSystemService(PowerManager::class.java).isInteractive }
                    server.emit()
                    await("screen-off message alert") { manager.activeNotifications.any { it.tag == "smoke-sender" } }
                    manager.cancel("smoke-sender", 1)
                } finally {
                    shell("input keyevent 224")
                    shell("wm dismiss-keyguard")
                }

                val joinsBefore = server.joined.get()
                server.disconnect()
                await("socket reconnect and session rejoin") { session.connected.value && server.joined.get() > joinsBefore }
                server.emit()
                await("message alert after reconnection") { manager.activeNotifications.any { it.tag == "smoke-sender" } }

                val status = manager.activeNotifications.first { it.id == Notifications.LISTENER_ID }
                status.notification.actions.first().actionIntent.send()
                await("notification Stop action") {
                    !container.backgroundListening.enabled.value && !container.backgroundListening.running.value &&
                        manager.activeNotifications.none { it.id == Notifications.LISTENER_ID }
                }
                check(!targetContext.getSharedPreferences("background-listening", 0).getBoolean("enabled", true))
                manager.cancel("smoke-sender", 1)
                runOnMainSync {
                    targetContext.startActivity(Intent(targetContext, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                await("foreground connection after Stop") {
                    container.foreground.value && session.connected.value && server.joined.get() > joinsBefore + 1
                }
                server.emit()
                await("foreground chat still receiving after Stop") {
                    manager.activeNotifications.any { it.tag == "smoke-sender" }
                }
                runOnMainSync {
                    container.backgroundListening.enable()
                    check(BackgroundMessageService.start(activity))
                }
                await("listener before sign-out") { container.backgroundListening.running.value }
                runBlocking { container.signOut(remote = false) }
                check(container.session.value == null)
                await("sign-out stops listener") {
                    !container.backgroundListening.enabled.value && !container.backgroundListening.running.value &&
                        manager.activeNotifications.none { it.id == Notifications.LISTENER_ID }
                }
                check(!BackgroundMessageService.start(targetContext))
                result.putString("stream", "Passed: foreground suppression/alerts, background and screen-off delivery, foreground service status, reconnect/rejoin, notification Stop, persisted disable, foreground recovery, active sign-out, and no-account guards.\n")
            }
            resultCode = Activity.RESULT_OK
        } catch (error: Throwable) {
            result.putString("stream", error.stackTraceToString())
        } finally {
            scope.cancel()
            if (ownsAccount && !keepAccount && container.session.value != null) runBlocking { container.signOut(remote = false) }
        }
        finish(resultCode, result)
    }

    private fun await(label: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 30_000_000_000
        while (!predicate()) {
            check(System.nanoTime() < deadline) { "Timed out waiting for $label" }
            Thread.sleep(100)
        }

    }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }
}
