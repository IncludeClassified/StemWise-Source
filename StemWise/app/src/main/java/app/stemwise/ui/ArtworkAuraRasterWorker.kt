package app.stemwise.ui

import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher

/** One raster at a time; each mounted owner keeps only its latest pending request. */
internal val artworkAuraRasterDispatcher = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "ArtworkAuraRaster").apply { isDaemon = true }
}.asCoroutineDispatcher()
