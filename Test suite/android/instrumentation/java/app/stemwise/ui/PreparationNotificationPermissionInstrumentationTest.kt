package app.stemwise.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.material3.Text
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import app.stemwise.playback.requireDisposablePlaybackTarget
import app.stemwise.ui.theme.StemwiseTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class PreparationNotificationPermissionInstrumentationTest {
    @get:Rule val compose = createStemwiseComposeRule()

    @Test fun firstPreparationRequestsPermissionAndDenialDoesNotBlockFurtherPreparation() {
        requireDisposablePlaybackTarget()
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        val grantedBefore = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        fun shell(command: String) {
            automation.executeShellCommand(command).use { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
            }
        }
        val permission = "${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}"
        shell("pm revoke $permission")
        shell("pm clear-permission-flags $permission user-set user-fixed")
        val scheduled = AtomicInteger()
        val denied = AtomicInteger()
        try {
            compose.setContent {
                StemwiseTheme {
                    val request = rememberPreparationNotifications { denied.incrementAndGet() }
                    app.stemwise.ui.design.StemwiseTextButton(onClick = {
                        scheduled.incrementAndGet()
                        request()
                    }) { Text("Prepare fixture") }
                }
            }
            compose.onNodeWithText("Prepare fixture").performClick()
            assertEquals(1, scheduled.get())
            compose.onNodeWithText("Continue").performClick()
            // The request runs after the real explanation's closing animation completes.
            compose.waitForIdle()
            val deadline = SystemClock.uptimeMillis() + 8_000L
            var deny: AccessibilityNodeInfo? = null
            while (deny == null && SystemClock.uptimeMillis() < deadline) {
                val root = automation.rootInActiveWindow
                deny = listOfNotNull(root?.packageName?.toString(), "com.android.permissioncontroller")
                    .distinct().firstNotNullOfOrNull { owner ->
                        root?.findAccessibilityNodeInfosByViewId("$owner:id/permission_deny_button")?.firstOrNull()
                    }
                if (deny == null) SystemClock.sleep(40L)
            }
            assertTrue("Android notification request did not appear; active=${automation.rootInActiveWindow?.packageName}, denied=${denied.get()}", deny != null)
            assertTrue(checkNotNull(deny).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            compose.waitUntil(5_000L) { denied.get() == 1 }
            compose.onNodeWithText("Prepare fixture").performClick()
            assertEquals(2, scheduled.get())
            assertEquals(PackageManager.PERMISSION_DENIED,
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
            compose.onNodeWithText("Show preparation progress").assertDoesNotExist()
        } finally {
            if (grantedBefore) shell("pm grant $permission")
        }
    }
}
