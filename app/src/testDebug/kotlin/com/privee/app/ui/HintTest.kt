package com.privee.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.privee.signal.DeviceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

// In testDebug: createComposeRule needs the debug-only ui-test-manifest.
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en")
class HintTest {
    @get:Rule
    val compose = createComposeRule()

    private val advice = "Don't write their name"

    @Test
    fun `the editor always advises against the name and saves a normalized hint`() {
        var saved: String? = "unset"
        compose.setContent { HintDialog("amber-falcon", current = null, onSave = { saved = it }, onDismiss = {}) }

        compose.onNodeWithTag("hint-advice").assertIsDisplayed()
        compose.onNodeWithText(advice, substring = true).assertIsDisplayed()
        compose.onNodeWithTag("hint-input").performTextReplacement("  book   club  ")
        compose.onNodeWithTag("hint-save").performClick()

        assertEquals("book club", saved)
    }

    @Test
    fun `an existing hint can be removed`() {
        var saved: String? = "unset"
        compose.setContent { HintDialog("amber-falcon", current = "book club", onSave = { saved = it }, onDismiss = {}) }

        compose.onNodeWithTag("hint-advice").assertIsDisplayed()
        compose.onNodeWithTag("hint-remove").performClick()

        assertNull(saved)
    }

    @Test
    fun `conversations show their hint and a long press asks to edit it`() {
        var editing by mutableStateOf<Recent?>(null)
        val recent = Recent("amber-falcon", "hello", 1, peerId = 2, hint = "book club")
        compose.setContent {
            HomeContent(
                ownName = "quiet-harbor",
                serverLabel = "chat.example.org",
                connected = true,
                deviceState = DeviceState.Ready,
                recents = listOf(recent),
                peer = "",
                peerError = false,
                onPeerChange = {},
                onOpen = {},
                onOpenChat = {},
                onShare = {},
                onLanguage = {},
                onLogOut = {},
                onForgetDevice = {},
                onResetIdentity = {},
                alerts = {},
                onEditHint = { editing = it },
            )
        }

        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("recent-amber-falcon"))
        compose.onNodeWithTag("recent-hint", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("recent-amber-falcon").performTouchInput { longClick() }

        assertEquals(recent, editing)
    }

    @Test
    fun `the chat header shows the hint`() {
        compose.setContent {
            ChatContent(
                peerName = "amber-falcon",
                items = emptyList(),
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
                onLanguage = {},
                onShowSafetyNumber = {},
                onClearHistory = {},
                onApprove = {},
                onDismissNotice = {},
                onResetIdentity = {},
                hint = "book club",
            )
        }

        compose.onNodeWithTag("chat-hint").assertIsDisplayed()
        compose.onNodeWithText("book club").assertIsDisplayed()
    }
}
