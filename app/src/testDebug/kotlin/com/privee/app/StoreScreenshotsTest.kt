package com.privee.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.privee.app.data.ChatItem
import com.privee.app.ui.ChatContent
import com.privee.app.ui.HomeContent
import com.privee.app.ui.PriveeTheme
import com.privee.app.ui.Recent
import com.privee.app.ui.SafetyNumberDialog
import com.privee.app.ui.ServerContent
import com.privee.app.ui.WelcomeScreen
import com.privee.signal.DeviceState
import java.io.File
import java.util.TimeZone
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The F-Droid/store screenshots (skill store-screenshots), rendered from made-up state: FLAG_SECURE
 * keeps the running app out of screenshots. They only render unless Gradle gets
 * `-PrecordStoreScreenshots`, which writes fastlane's phoneScreenshots/1.png to 5.png.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS-" + RobolectricDeviceQualifiers.Pixel7)
class StoreScreenshotsTest {
    @get:Rule
    val compose = createComposeRule()

    private val directory = File(System.getProperty("privee.storeScreenshots") ?: "build/outputs/roborazzi")
    private val server = "https://chat.example.org"
    private val own = "quiet-harbor-lantern-evening"
    private val peer = "amber-falcon-north-meadow"
    private val timeZone = TimeZone.getDefault()

    // 2026-01-01 21:29 UTC, shown as 9:29 PM.
    private val start = 1_767_302_940_000L
    private val messages = listOf(
        ChatItem("1", "Hi! Is this the session name you shared at the meetup?", outgoing = false, pending = false, ts = start),
        ChatItem("2", "Yes, that is me. Nice to hear from you!", outgoing = true, pending = false, ts = start + 60_000),
        ChatItem("3", "Great. No phone number needed, which I love.", outgoing = false, pending = false, ts = start + 70_000),
        ChatItem("4", "Same here. Let us verify the safety number first.", outgoing = true, pending = false, ts = start + 80_000),
        ChatItem("5", "Done, it matches on my side.", outgoing = false, pending = false, ts = start + 90_000),
    )

    @Before
    fun utc() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    @After
    fun restore() = TimeZone.setDefault(timeZone)

    private fun capture(name: String, content: @Composable () -> Unit) {
        compose.setContent { PriveeTheme(dark = false, content = content) }
        compose.waitForIdle()
        captureScreenRoboImage(File(directory, name))
    }

    @Test
    fun `1 server choice`() = capture("1.png") {
        ServerContent(
            address = "",
            onAddressChange = {},
            busy = false,
            error = null,
            canConnect = false,
            suggestion = "",
            onConnect = {},
            onSettings = {},
        )
    }

    @Test
    fun `2 welcome`() = capture("2.png") {
        WelcomeScreen(
            serverLabel = server,
            serverName = "Privee demo",
            pendingPeer = null,
            onRegister = {},
            onLogIn = {},
            onChangeServer = {},
            onSettings = {},
        )
    }

    @Test
    fun `3 home`() = capture("3.png") {
        HomeContent(
            ownName = own,
            serverLabel = server,
            connected = true,
            deviceState = DeviceState.Ready,
            recents = listOf(Recent(peer, messages.last().text, messages.last().ts)),
            peer = "",
            peerError = false,
            onPeerChange = {},
            onOpen = {},
            onOpenChat = {},
            onShare = {},
            onSettings = {},
            onLogOut = {},
            onForgetDevice = {},
            onResetIdentity = {},
            alerts = {},
        )
    }

    @Composable
    private fun Conversation() = ChatContent(
        peerName = peer,
        items = messages,
        notFound = false,
        notice = null,
        identityChanged = false,
        hasPeer = true,
        deviceState = DeviceState.Ready,
        connected = true,
        draft = "",
        onDraftChange = {},
        onSend = {},
        onBack = {},
        onSettings = {},
        onShowSafetyNumber = {},
        onClearHistory = {},
        onApprove = {},
        onDismissNotice = {},
        onResetIdentity = {},
    )

    @Test
    fun `4 conversation`() = capture("4.png") { Conversation() }

    @Test
    fun `5 safety number`() = capture("5.png") {
        Conversation()
        SafetyNumberDialog(peer, "174661715637810304099390534490312391864873105488604742230704", onDismiss = {})
    }
}
