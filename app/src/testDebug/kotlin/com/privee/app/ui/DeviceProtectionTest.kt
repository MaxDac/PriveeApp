package com.privee.app.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// In testDebug: ComponentActivity is only declared by the debug-only ui-test-manifest.
@RunWith(AndroidJUnit4::class)
class DeviceProtectionTest {
    private fun protectedActivity(): ComponentActivity {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.HIDE_OVERLAY_WINDOWS)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create()
        val activity = controller.get()
        protectWindow(activity.window)
        activity.setContent { Text("Privee") }
        controller.start().resume().visible()
        return activity
    }

    private fun View.descendants(): Sequence<View> = sequence {
        yield(this@descendants)
        if (this@descendants is ViewGroup) {
            for (i in 0 until childCount) yieldAll(getChildAt(i).descendants())
        }
    }

    @Test
    fun `the window is never captured`() {
        val flags = protectedActivity().window.attributes.flags
        assertNotEquals(0, flags and WindowManager.LayoutParams.FLAG_SECURE)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.R])
    fun `touches through overlays are dropped before Android 12`() {
        assertTrue(protectedActivity().window.decorView.filterTouchesWhenObscured)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.S])
    fun `overlays are hidden from Android 12`() {
        // The system ignores touches through untrusted overlays itself, and hides them anyway.
        assertFalse(protectedActivity().window.decorView.filterTouchesWhenObscured)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
    fun `only accessibility tools can read the Compose UI`() {
        val decor = protectedActivity().window.decorView
        assertTrue(decor.isAccessibilityDataSensitive)
        val compose = decor.descendants().single { it.javaClass.simpleName == "AndroidComposeView" }
        assertTrue(compose.isAccessibilityDataSensitive)
    }

    @Test
    fun `the app may hide overlays`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        @Suppress("DEPRECATION")
        val requested = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions
        assertTrue(requested.orEmpty().contains(Manifest.permission.HIDE_OVERLAY_WINDOWS))
    }

    @Test
    fun `keyboards are asked not to learn`() {
        val options = withoutPersonalizedLearning(EditorInfo.IME_ACTION_SEND)
        assertEquals(EditorInfo.IME_ACTION_SEND, options and EditorInfo.IME_MASK_ACTION)
        assertNotEquals(0, options and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
    }
}
