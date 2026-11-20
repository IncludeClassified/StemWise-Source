package app.stemwise.ui

/** Only the latest picker result may publish a candidate or confirm a switch. */
internal class LibraryRootSelectionRequests {
    private var current = 0L
    fun begin(): Long = ++current
    fun isCurrent(request: Long): Boolean = request == current
}
