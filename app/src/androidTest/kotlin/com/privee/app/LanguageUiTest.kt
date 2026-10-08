package com.privee.app

import android.app.NotificationManager
import android.app.Notification
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.privee.app.data.ServerConfig
import com.privee.app.data.Invite
import com.privee.app.push.Notifications
import com.privee.app.push.BackgroundMessageService
import com.privee.net.AuthResult
import com.privee.net.SessionInfo
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue

@RunWith(AndroidJUnit4::class)
class LanguageUiTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as PriveeApplication
    private lateinit var server: MockWebServer
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun prepare() {
        runBlocking { app.container.signOut(remote = false) }
        instrumentation.runOnMainSync {
            app.container.changeServer()
            app.languages.select(AppLanguage.English)
        }
        server = MockWebServer()
        val socket = object : WebSocketListener() {
            private var identity: String? = null

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = Json.parseToJsonElement(text).jsonArray
                val topic = frame[2].jsonPrimitive.content
                val event = frame[3].jsonPrimitive.content
                val payload = frame[4].jsonObject
                val reply = when (event) {
                    "phx_join" -> buildJsonObject {
                        if (topic.startsWith("chat:")) { put("peer_id", 456); put("peer_session_name", "other-session") }
                    }
                    "signal_status" -> buildJsonObject {
                        identity?.let { put("identity_key", it) }
                        put("opk_count", 100)
                        put("max_opk_id", 100)
                    }
                    "publish_identity", "reset_identity" -> {
                        identity = payload.getValue("identity_key").jsonPrimitive.content
                        JsonObject(emptyMap())
                    }
                    "open_conversation" -> buildJsonObject { put("epoch", "test-epoch") }
                    "fetch_messages" -> buildJsonObject { put("messages", JsonArray(emptyList())); put("next_cursor", JsonNull) }
                    else -> JsonObject(emptyMap())
                }
                webSocket.send(JsonArray(listOf(frame[0], frame[1], frame[2], JsonPrimitive("phx_reply"),
                    buildJsonObject { put("status", "ok"); put("response", reply) })).toString())
            }
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return if (request.url.encodedPath.endsWith("/websocket")) {
                    MockResponse.Builder().webSocketUpgrade(socket).build()
                } else if (request.url.encodedPath.endsWith("/sessions")) {
                    val message = if (request.headers["Accept-Language"] == "it") "nome già utilizzato" else "name already taken"
                    MockResponse.Builder().code(422)
                        .body("""{"error":"invalid","errors":{"session_name":["$message"]}}""").build()
                } else {
                    MockResponse.Builder().code(404).body("""{"error":"not_found"}""").build()
                }
            }
        }
        server.start()
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(app.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    @After
    fun cleanUp() {
        scenario?.close()
        runBlocking { app.container.signOut(remote = false) }
        instrumentation.runOnMainSync {
            app.container.changeServer()
            app.languages.select(AppLanguage.English)
        }
        server.close()
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
    }

    private fun select(language: AppLanguage, menuTag: String? = null) {
        menuTag?.let { compose.onNodeWithTag(it).performClick() }
        compose.onNodeWithTag("language").performClick()
        compose.onNodeWithTag("language-${language.tag}").performClick()
        compose.waitUntil(10_000) { app.languages.language == language }
        compose.waitForIdle()
    }

    @Test
    fun firstLaunchAndEveryLanguagePreserveServerAddressAndBackgroundResources() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ITALIAN)
            instrumentation.runOnMainSync {
                app.getSharedPreferences("language", 0).edit().clear().commit()
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    app.getSystemService(android.app.LocaleManager::class.java).applicationLocales = android.os.LocaleList.getEmptyLocaleList()
                } else {
                    androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(androidx.core.os.LocaleListCompat.getEmptyLocaleList())
                }
                app.languages.initialize()
            }
            assertEquals(AppLanguage.English, app.languages.language)
            launch()
            compose.onNodeWithText("Choose your server").assertIsDisplayed()
            compose.onNodeWithTag("server-address").performTextInput("https://chat.example.org")
            val headings = mapOf(
                AppLanguage.Italian to "Scegli il server",
                AppLanguage.Portuguese to "Escolha o servidor",
                AppLanguage.Spanish to "Elige tu servidor",
                AppLanguage.French to "Choisissez votre serveur",
                AppLanguage.English to "Choose your server",
            )
            val channels = mapOf(
                AppLanguage.Italian to "Messaggi",
                AppLanguage.Portuguese to "Mensagens",
                AppLanguage.Spanish to "Mensajes",
                AppLanguage.French to "Messages",
                AppLanguage.English to "Messages",
            )
            for ((language, heading) in headings) {
                select(language)
                compose.onNodeWithText(heading).assertIsDisplayed()
                compose.onNodeWithTag("server-address").assertTextContains("https://chat.example.org")
                assertEquals(language, LanguagePreferences(app).language)
                val context = app.languages.localizedContext()
                assertEquals(channels.getValue(language), context.getString(R.string.notification_channel_messages))
                Notifications.createChannel(app)
                assertEquals(
                    channels.getValue(language),
                    app.getSystemService(NotificationManager::class.java).getNotificationChannel("messages").name.toString(),
                )
                assertEquals(
                    context.getString(R.string.notification_channel_listener),
                    app.getSystemService(NotificationManager::class.java)
                        .getNotificationChannel("background-listening").name.toString(),
                )
                for (connected in listOf(false, true)) {
                    val listener = Notifications.listener(app, connected)
                    assertEquals(context.getString(R.string.background_alerts), listener.extras.getString(Notification.EXTRA_TITLE))
                    assertEquals(
                        context.getString(if (connected) R.string.listener_connected else R.string.listener_connecting),
                        listener.extras.getString(Notification.EXTRA_TEXT),
                    )
                    assertEquals(context.getString(R.string.listener_stop), listener.actions.first().title.toString())
                }
                val manager = app.getSystemService(NotificationManager::class.java)
                Notifications.newMessage(app, "sender-original", "https://chat.example.org")
                val title = context.getString(R.string.notification_new_message_from, "sender-original")
                compose.waitUntil(10_000) {
                    manager.activeNotifications.any { it.tag == "sender-original" && it.notification.extras.getString(Notification.EXTRA_TITLE) == title }
                }
                Notifications.newMessage(app, null, null)
                val genericTitle = context.getString(R.string.notification_new_message)
                compose.waitUntil(10_000) {
                    manager.activeNotifications.any { it.tag == "" && it.notification.extras.getString(Notification.EXTRA_TITLE) == genericTitle }
                }
                manager.cancel("sender-original", 1)
                manager.cancel("", 1)
                scenario!!.recreate()
                compose.waitForIdle()
                compose.onNodeWithText(heading).assertIsDisplayed()
                compose.onNodeWithTag("server-address").assertTextContains("https://chat.example.org")
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun activeBackgroundListenerAndSettingsSurviveLanguageChanges() {
        instrumentation.runOnMainSync {
            app.container.selectServer(ServerConfig(server.url("/").toString().trimEnd('/'), null))
            app.container.signedIn(app.container.server.value!!, AuthResult("test-token", SessionInfo(123, "own-session", true)))
        }
        launch()
        val session = app.container.session.value!!
        compose.onNodeWithTag("background-alert-settings").performClick()
        compose.onNodeWithTag("enable-background-alerts").performClick()
        val manager = app.getSystemService(NotificationManager::class.java)
        compose.waitUntil(15_000) { app.container.backgroundListening.running.value && session.connected.value }
        scenario!!.recreate()
        compose.waitForIdle()
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("Stop listening"))
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        compose.onNodeWithText("Stop listening").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        for (language in listOf(AppLanguage.Italian, AppLanguage.Portuguese, AppLanguage.Spanish, AppLanguage.French, AppLanguage.English)) {
            select(language, "menu")
            val strings = app.languages.localizedContext()
            compose.waitUntil(10_000) {
                val notification = manager.activeNotifications.firstOrNull { it.id == Notifications.LISTENER_ID }?.notification
                notification?.extras?.getString(Notification.EXTRA_TITLE) == strings.getString(R.string.background_alerts) &&
                    notification.extras.getString(Notification.EXTRA_TEXT) == strings.getString(R.string.listener_connected) &&
                    notification.actions.first().title.toString() == strings.getString(R.string.listener_stop)
            }
            assertTrue(app.container.backgroundListening.enabled.value)
            assertTrue(app.container.backgroundListening.running.value)
            assertEquals(session, app.container.session.value)
            compose.onNodeWithTag("background-alert-settings").performClick()
            compose.onNodeWithText(strings.getString(R.string.listener_stop)).performScrollTo().assertIsDisplayed()
            scenario!!.recreate()
            compose.waitForIdle()
            compose.waitUntil(10_000) {
                compose.onAllNodes(androidx.compose.ui.test.hasText(strings.getString(R.string.listener_stop)))
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
            }
            compose.onNodeWithText(strings.getString(R.string.listener_stop)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(strings.getString(R.string.listener_close)).performClick()
        }
        instrumentation.runOnMainSync {
            app.container.backgroundListening.disable()
            BackgroundMessageService.stop(app)
        }
        compose.waitUntil(10_000) { !app.container.backgroundListening.running.value }
    }

    @Test
    fun delayedStatusErrorsAcrossLanguageSwitchUseCurrentLocaleAndKeepFormValues() {
        instrumentation.runOnMainSync { app.container.selectServer(ServerConfig(server.url("/").toString().trimEnd('/'), null)) }
        launch()
        compose.onNodeWithTag("go-register").performClick()
        compose.onNodeWithTag("session-name").performTextInput("entered-session-123456789")
        compose.onNodeWithTag("recovery-phrase").performTextInput("a sufficiently long recovery phrase")

        for ((status, language) in listOf(401 to AppLanguage.Italian, 429 to AppLanguage.French)) {
            val received = CountDownLatch(1)
            val release = CountDownLatch(1)
            val originalLanguage = app.languages.language.tag
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    received.countDown()
                    check(release.await(20, TimeUnit.SECONDS)) { "Test did not release delayed response" }
                    return MockResponse.Builder().code(status)
                        .body("""{"error":"${if (status == 401) "invalid_credentials" else "rate_limited"}"}""")
                        .build()
                }
            }
            try {
                compose.onNodeWithTag("recovery-phrase").performImeAction()
                compose.onNodeWithTag("submit").performScrollTo().assertIsDisplayed().performClick()
                assertTrue("Authentication request was not received", received.await(10, TimeUnit.SECONDS))
                select(language)
                compose.onNodeWithTag("session-name").assertTextContains("entered-session-123456789")
                compose.onNodeWithTag("recovery-phrase").performScrollTo().assertIsDisplayed()
                release.countDown()
                val strings = app.languages.localizedContext()
                val expected = if (status == 401) strings.getString(R.string.auth_invalid)
                    else strings.getString(R.string.http_error, status)
                compose.waitUntil(10_000) {
                    compose.onAllNodes(androidx.compose.ui.test.hasText(expected)).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText(expected).assertIsDisplayed()
                compose.onNodeWithTag("submit").assertIsEnabled()
                val request = server.takeRequest()
                assertEquals(originalLanguage, request.headers["Accept-Language"])
                assertEquals(
                    "a sufficiently long recovery phrase",
                    Json.parseToJsonElement(request.body!!.utf8()).jsonObject.getValue("recovery_phrase").jsonPrimitive.content,
                )
                compose.onNodeWithText("invalid_credentials").assertDoesNotExist()
                compose.onNodeWithText("rate_limited").assertDoesNotExist()
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun switchingLanguageClearsServerValidationButRetainsFormAndRoute() {
        instrumentation.runOnMainSync { app.container.selectServer(ServerConfig(server.url("/").toString().trimEnd('/'), null)) }
        launch()
        compose.onNodeWithTag("go-register").performClick()
        compose.onNodeWithTag("session-name").performTextInput("name-already-taken-123456")
        compose.onNodeWithTag("recovery-phrase").performTextInput("a sufficiently long recovery phrase")
        compose.onNodeWithTag("recovery-phrase").performImeAction()
        compose.onNodeWithTag("submit").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("name already taken")).fetchSemanticsNodes().isNotEmpty()
        }
        server.takeRequest()
        select(AppLanguage.Italian)
        compose.onNodeWithTag("session-name").assertTextContains("name-already-taken-123456")
        compose.onNodeWithTag("recovery-phrase").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("name already taken").assertDoesNotExist()
        compose.onNodeWithTag("submit").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("nome già utilizzato")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("nome già utilizzato").performScrollTo().assertIsDisplayed()
        val request = server.takeRequest()
        assertEquals("it", request.headers["Accept-Language"])
        assertEquals(
            "a sufficiently long recovery phrase",
            Json.parseToJsonElement(request.body!!.utf8()).jsonObject.getValue("recovery_phrase").jsonPrimitive.content,
        )
    }

    @Test
    fun conversationDraftAndLanguageSurviveRecreationLogoutForgetAndServerChange() {
        instrumentation.runOnMainSync {
            app.container.selectServer(ServerConfig(server.url("/").toString().trimEnd('/'), null))
            app.container.signedIn(app.container.server.value!!, AuthResult("test-token", SessionInfo(123, "own-session", true)))
        }
        launch()
        val session = app.container.session.value!!
        compose.onNodeWithTag("peer-name").performTextInput("other-session")
        compose.onNodeWithTag("open-chat").performClick()
        val vmStore = session.signal
        compose.waitUntil(15_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("composer") and androidx.compose.ui.test.isEnabled())
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("composer").assertIsEnabled().performTextInput("Draft not sent")
        select(AppLanguage.French, "chat-menu")
        compose.onNodeWithText("other-session").assertIsDisplayed()
        assertEquals(vmStore, app.container.session.value!!.signal)
        compose.onNodeWithTag("composer").assertTextContains("Draft not sent")
        scenario!!.recreate()
        compose.waitForIdle()
        compose.onNodeWithTag("composer").assertTextContains("Draft not sent")
        app.container.pendingInvite.value = Invite("invited-session", "https://other.example.org")
        compose.waitForIdle()
        compose.onNodeWithText("Serveur différent").assertIsDisplayed()
        scenario!!.recreate()
        compose.waitForIdle()
        compose.onNodeWithText("Serveur différent").assertIsDisplayed()
        compose.onNodeWithText("Annuler").performClick()
        compose.onNodeWithTag("composer").assertTextContains("Draft not sent")
        compose.onNodeWithTag("chat-menu").performClick()
        compose.onNodeWithText("Numéro de sécurité").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("Pas encore disponible.")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Pas encore disponible.").assertIsDisplayed()
        compose.onNodeWithText("Fermer").performClick()
        runBlocking { app.container.signOut(remote = false) }
        compose.waitForIdle()
        assertEquals(AppLanguage.French, app.languages.language)
        instrumentation.runOnMainSync {
            app.container.signedIn(app.container.server.value!!, AuthResult("test-token", SessionInfo(123, "own-session", true)))
        }
        compose.waitForIdle()
        runBlocking { app.container.forgetDevice() }
        compose.waitForIdle()
        assertEquals(AppLanguage.French, app.languages.language)
        instrumentation.runOnMainSync { app.container.changeServer() }
        compose.waitForIdle()
        compose.onNodeWithText("Choisissez votre serveur").assertIsDisplayed()
        scenario!!.recreate()
        compose.waitForIdle()
        assertEquals(AppLanguage.French, LanguagePreferences(app).language)
    }
}
