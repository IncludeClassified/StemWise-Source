package app.stemwise.qualification

import android.app.Application
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnitRunner
import app.stemwise.StemwiseApplication
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.security.MessageDigest

/**
 * Fails before a standard test can create the production application under any package other than
 * the disposable rehearsal identity. The APK manifest also targets this identity exactly; this
 * runtime guard is the independent fail-closed boundary for stale or incorrectly packaged roles.
 */
class DevelopmentStandardInstrumentationRunner : AndroidJUnitRunner() {
    override fun onStart() {
        // Application recovery may switch the demo Library and temporarily close the shared work
        // gate. Admit ordinary fixtures only after that real startup operation has completed.
        waitForIdleSync()
        val application = targetContext.applicationContext as StemwiseApplication
        runBlocking { withTimeout(15_000L) { application.workerInitialization.await() } }
        super.onStart()
    }

    override fun callActivityOnCreate(activity: Activity, savedInstanceState: Bundle?) {
        StandardInstrumentationTargetGuard.requireExactTargetPackage(activity.packageName)
        // Rehearsal activities need real layout and frame callbacks while the owner keeps the
        // phone locked. These window flags neither dismiss keyguard nor change the owner app.
        activity.setShowWhenLocked(true)
        activity.setTurnScreenOn(true)
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.callActivityOnCreate(activity, savedInstanceState)
    }

    override fun newApplication(
        cl: ClassLoader,
        className: String,
        context: Context
    ): Application {
        StandardInstrumentationTargetGuard.requireExactTargetPackage(context.packageName)
        return super.newApplication(cl, className, context)
    }

    override fun onCreate(arguments: Bundle) {
        StandardInstrumentationTargetGuard.requireExactTargetPackage(targetContext.packageName)
        for ((argument, apk) in listOf(
            "stemwiseExpectedApplicationSha256" to targetContext.applicationInfo.sourceDir,
            "stemwiseExpectedTestSha256" to context.applicationInfo.sourceDir
        )) {
            StandardInstrumentationTargetGuard.requireInstalledApk(arguments.getString(argument), File(apk))
        }
        super.onCreate(arguments)
    }
}

internal object StandardInstrumentationTargetGuard {
    const val DEVELOPMENT_PACKAGE = "app.stemwise.development"
    const val OWNER_PACKAGE = "app.stemwise"

    fun requireCurrentTarget() {
        requireExactTargetPackage(
            InstrumentationRegistry.getInstrumentation().targetContext.packageName
        )
    }

    fun requireExactTargetPackage(actualTargetPackage: String) {
        check(actualTargetPackage == DEVELOPMENT_PACKAGE) {
            "Standard instrumentation requires exact disposable target $DEVELOPMENT_PACKAGE; " +
                "actual target was $actualTargetPackage"
        }
    }

    fun requireInstalledApk(expected: String?, apk: File) {
        check(expected?.matches(Regex("[0-9a-fA-F]{64}")) == true) { "Missing exact installed APK identity" }
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        check(digest.digest().joinToString("") { "%02x".format(it) }.equals(expected, true)) {
            "Installed Development APK differs"
        }
    }
}

/** Makes production-style apply() calls synchronous only inside an explicitly isolated fixture. */
internal fun SharedPreferences.withDurableTestWrites(): SharedPreferences =
    DurableTestSharedPreferences(this)

private class DurableTestSharedPreferences(
    private val delegate: SharedPreferences
) : SharedPreferences by delegate {
    override fun edit(): SharedPreferences.Editor = DurableTestEditor(delegate.edit())
}

private class DurableTestEditor(
    private val delegate: SharedPreferences.Editor
) : SharedPreferences.Editor {
    override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
        delegate.putString(key, value)
    }

    override fun putStringSet(
        key: String?,
        values: MutableSet<String>?
    ): SharedPreferences.Editor = apply {
        delegate.putStringSet(key, values)
    }

    override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
        delegate.putInt(key, value)
    }

    override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
        delegate.putLong(key, value)
    }

    override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
        delegate.putFloat(key, value)
    }

    override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
        delegate.putBoolean(key, value)
    }

    override fun remove(key: String?): SharedPreferences.Editor = apply {
        delegate.remove(key)
    }

    override fun clear(): SharedPreferences.Editor = apply {
        delegate.clear()
    }

    override fun commit(): Boolean = delegate.commit()

    override fun apply() {
        check(delegate.commit()) { "Unable to commit isolated test preferences durably" }
    }
}

internal fun deleteTestPreferencesDurably(context: Context, preferenceName: String) {
    check(
        context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    ) {
        "Unable to quiesce isolated preference fixture $preferenceName"
    }
    val deleted = context.deleteSharedPreferences(preferenceName)
    val preferencesDirectory = File(context.applicationInfo.dataDir, "shared_prefs")
    val ownedFiles = listOf(
        File(preferencesDirectory, "$preferenceName.xml"),
        File(preferencesDirectory, "$preferenceName.xml.bak")
    )
    ownedFiles.forEach { file ->
        check(!file.exists() || file.delete()) {
            "Unable to delete isolated preference fixture ${file.absolutePath}"
        }
    }
    check(deleted || ownedFiles.none(File::exists)) {
        "Unable to delete isolated preference fixture $preferenceName"
    }
    check(ownedFiles.none(File::exists)) {
        "Isolated preference fixture survived teardown: $preferenceName"
    }
}

internal fun databaseFixtureFiles(context: Context, databaseName: String): List<File> {
    val database = context.getDatabasePath(databaseName)
    return database.parentFile
        ?.listFiles()
        .orEmpty()
        .filter { file ->
            file.name == database.name ||
                file.name.startsWith("${database.name}-") ||
                file.name.startsWith("${database.name}.")
        }
}

internal object StandardInstrumentationResidue {
    private const val SOURCE_AUTHORITY_DATABASE = "current-authority-fixture"
    private val preferencePrefixes = listOf("stemwise-test-", "golden-qa-", "mvsep-read-failure-")

    fun assertKnownFixturesAbsent() {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val authorityResidue = databaseFixtureFiles(context, SOURCE_AUTHORITY_DATABASE)
        val preferenceResidue = File(context.applicationInfo.dataDir, "shared_prefs")
            .listFiles()
            .orEmpty()
            .filter { file -> preferencePrefixes.any(file.name::startsWith) }
        check(authorityResidue.isEmpty() && preferenceResidue.isEmpty()) {
            "Known standard-test residue survived in ${context.packageName}: " +
                (authorityResidue + preferenceResidue)
                    .map(File::getAbsolutePath)
                    .sorted()
                    .joinToString()
        }
    }
}
