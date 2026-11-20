package app.stemwise.work

import androidx.work.Constraints
import androidx.work.NetworkType

/**
 * Shared policy for unattended network work.
 *
 * WorkManager's unmetered constraint is only a wake-up condition; AppNetworkAccess additionally
 * requires and binds unmetered Wi-Fi before any bytes can leave. Battery and storage guards
 * keep automatic library work from competing with a device that is already under pressure.
 */
internal fun automaticNetworkWorkConstraints(): Constraints = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.UNMETERED)
    .setRequiresBatteryNotLow(true)
    .setRequiresStorageNotLow(true)
    .build()

/** Local recovery must survive network loss; remote effects enforce AppNetworkAccess themselves. */
internal fun localRecoveryWorkConstraints(): Constraints = Constraints.Builder()
    .setRequiresBatteryNotLow(true)
    .setRequiresStorageNotLow(true)
    .build()
