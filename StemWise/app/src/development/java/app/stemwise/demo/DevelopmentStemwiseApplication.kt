package app.stemwise.demo

import app.stemwise.StemwiseApplication
import app.stemwise.data.LibraryRootState

internal suspend fun initializeDefaultDemoLibrary(
    rootState: () -> LibraryRootState,
    seed: suspend () -> Unit,
    activate: suspend () -> Unit
): Boolean {
    val initial = rootState()
    // Failed recovery does not establish that the owner never selected a Library.
    if (initial.treeUri != null || initial.libraryId != null || initial.errorCode != null) return false
    seed()
    if (rootState() != initial) return false
    activate()
    return true
}

class DevelopmentStemwiseApplication : StemwiseApplication() {
    override fun onCreate() {
        // The exact app-owned provider grant is transient. Restore it before selected-root
        // recovery checks canRead/canWrite; this grants no authority to any external Library.
        UiDemoLibraryDocumentsProvider.grantProcessAccess(this)
        super.onCreate()
    }

    override suspend fun startLibrarySession() {
        val activated = initializeDefaultDemoLibrary(
            rootState = { services.libraryRootManager.state.value },
            seed = { UiDemoLibraryDocumentsProvider.initialize(this) },
            activate = {
                val candidate = services.libraryRootManager
                    .validateCandidate(UiDemoLibraryDocumentsProvider.treeUri).getOrThrow()
                services.librarySessionCoordinator.switchTo(candidate).getOrThrow()
            }
        )
        if (!activated) super.startLibrarySession()
    }
}
