package app.stemwise.playback

import androidx.test.platform.app.InstrumentationRegistry
import app.stemwise.R
import app.stemwise.work.MvsepPreparationWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@app.stemwise.qualification.ManagedAudioTest
class NotificationIconOwnershipInstrumentationTest {
    @Test fun mergedMedia3ResourcesKeepTheBrandedMarkAndDistinctWhiteTransportIcons() {
        requireDisposablePlaybackTarget()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun pixels(resource: Int): List<Int> {
            val bitmap = android.graphics.Bitmap.createBitmap(48, 48, android.graphics.Bitmap.Config.ARGB_8888)
            try {
                requireNotNull(context.getDrawable(resource)).apply {
                    setBounds(0, 0, 48, 48)
                    draw(android.graphics.Canvas(bitmap))
                }
                return IntArray(48 * 48).also { bitmap.getPixels(it, 0, 48, 0, 0, 48, 48) }.toList()
            } finally { bitmap.recycle() }
        }
        assertEquals(pixels(R.drawable.ic_launcher_monochrome), pixels(R.drawable.media3_notification_small_icon))
        val geometry = hashSetOf<List<Int>>()
        for (resource in listOf(R.drawable.media3_icon_previous, R.drawable.media3_icon_play,
                R.drawable.media3_icon_pause, R.drawable.media3_icon_next)) {
            val rendered = pixels(resource)
            assertTrue("Transport icon has no visible pixels", rendered.any { android.graphics.Color.alpha(it) > 0 })
            assertTrue("Transport icons lost distinct geometry", geometry.add(rendered))
            assertTrue("Transport icon must be white", rendered.filter { android.graphics.Color.alpha(it) > 0 }.all {
                android.graphics.Color.red(it) == 255 && android.graphics.Color.green(it) == 255 &&
                    android.graphics.Color.blue(it) == 255 })
        }
    }

    @Suppress("RestrictedApi")
    @Test fun preparationWorkerBuildsBrandedNotificationWithoutActions() {
        requireDisposablePlaybackTarget()
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val manager = androidx.work.impl.WorkManagerImpl.getInstance(application)
        val configuration = manager.configuration
        val parameters = androidx.work.WorkerParameters(java.util.UUID.randomUUID(), androidx.work.Data.EMPTY,
            emptyList(), androidx.work.WorkerParameters.RuntimeExtras(), 0, 0, configuration.executor,
            configuration.workerCoroutineContext, manager.workTaskExecutor, configuration.workerFactory,
            androidx.work.impl.utils.WorkProgressUpdater(manager.workDatabase, manager.workTaskExecutor),
            androidx.work.impl.utils.WorkForegroundUpdater(manager.workDatabase, manager.processor, manager.workTaskExecutor))
        val worker = MvsepPreparationWorker(application, parameters)
        val info = MvsepPreparationWorker::class.java.getDeclaredMethod("notification")
            .apply { isAccessible = true }.invoke(worker) as androidx.work.ForegroundInfo
        val notification = info.notification
        assertEquals(R.drawable.ic_launcher_monochrome, notification.smallIcon.resId)
        assertTrue("Preparation exposed an unsupported pause/cancel action", notification.actions.isNullOrEmpty())
        assertTrue(notification.contentIntent != null)
    }
}
