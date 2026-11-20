package app.stemwise.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.Activity
import android.util.Log
import app.stemwise.StemwiseApplication
import app.stemwise.data.LibraryRootException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Shell-armed Development seam that selects the separately signed qualification SAF root. */
class QualificationLibraryGrantReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        check(intent.action == ACTION)
        val treeUri = checkNotNull(intent.data) { "Qualification tree URI is required" }
        check(treeUri.authority == EXPECTED_AUTHORITY) { "Unexpected qualification provider" }
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(treeUri, flags)
                val app = context.applicationContext as StemwiseApplication
                val candidate = app.services.libraryRootManager.validateCandidate(treeUri)
                    .getOrThrow()
                app.services.librarySessionCoordinator.switchTo(candidate).getOrThrow()
                pending.resultCode = Activity.RESULT_OK
                pending.resultData = "PASS"
            } catch (failure: Throwable) {
                Log.e(TAG, "Qualification root binding failed", failure)
                pending.resultCode = Activity.RESULT_CANCELED
                pending.resultData =
                    "FAIL ${(failure as? LibraryRootException)?.code ?: failure.javaClass.name}"
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val ACTION = "app.stemwise.qualification.BIND_EXTERNAL_LIBRARY"
        const val EXPECTED_AUTHORITY = "app.stemwise.testprovider.documents"
        const val TAG = "StemWiseQualification"
    }
}
