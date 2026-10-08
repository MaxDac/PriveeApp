package com.privee.app.ui

import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

/*
 * Defences against other, unrooted apps on the same device (docs/ARCHITECTURE.md, "Other apps on
 * the device"): screen capture, overlays and tapjacking, accessibility scraping and keyboards that
 * learn what is typed.
 */

/**
 * Protects the activity window, before its content is set. FLAG_SECURE is on in every build: the
 * app never appears in screenshots, recordings, casts or the recents thumbnail. Compose dialogs and
 * popups inherit it (their default `SecureFlagPolicy.Inherit`); [ProtectedWindow] covers the rest.
 */
fun protectWindow(window: Window) {
    window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    // Needs android.permission.HIDE_OVERLAY_WINDOWS.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) window.setHideOverlayWindows(true)
    protectViewTree(window.decorView)
}

/** Per-window protections, for the root view of the activity, a dialog or a popup. */
fun protectViewTree(root: View) {
    // Android 12+ already blocks touches through untrusted overlays, and they are hidden anyway.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) root.filterTouchesWhenObscured = true
    // Descendants default to AUTO and inherit it: only accessibility tools (such as TalkBack) see the UI.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        root.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
    }
}

/** Applies [protectViewTree] to the window this composable is in; call it inside dialogs and popups. */
@Composable
fun ProtectedWindow() {
    val view = LocalView.current
    DisposableEffect(view) {
        protectViewTree(view.rootView)
        onDispose { }
    }
}

/** The app's [AlertDialog], with [ProtectedWindow] applied to its window. */
@Composable
fun PriveeAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            ProtectedWindow()
            confirmButton()
        },
        modifier = modifier,
        dismissButton = dismissButton,
        title = title,
        text = text,
    )
}

/** Asks keyboards not to learn from, or store, anything typed in [content]'s text fields. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NoPersonalizedLearning(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(interceptor = NoPersonalizedLearningInterceptor, content = content)
}

@OptIn(ExperimentalComposeUiApi::class)
internal val NoPersonalizedLearningInterceptor = PlatformTextInputInterceptor { request, nextHandler ->
    nextHandler.startInputMethod(NoPersonalizedLearningRequest(request))
}

private class NoPersonalizedLearningRequest(private val request: PlatformTextInputMethodRequest) :
    PlatformTextInputMethodRequest {
    override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
        val connection = request.createInputConnection(outAttributes)
        outAttributes.imeOptions = withoutPersonalizedLearning(outAttributes.imeOptions)
        return connection
    }
}

internal fun withoutPersonalizedLearning(imeOptions: Int): Int =
    imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
