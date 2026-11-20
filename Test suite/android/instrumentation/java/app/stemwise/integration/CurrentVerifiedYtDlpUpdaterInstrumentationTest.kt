package app.stemwise.integration

import android.app.Application
import android.content.Intent
import app.stemwise.qa.GoldenQaDocumentsProvider
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.stemwise.data.LibraryDatabase
import app.stemwise.data.LibraryRootManager
import app.stemwise.data.PackageMutationCoordinator
import app.stemwise.data.CanonicalJson
import app.stemwise.data.SelectedLibraryContract
import app.stemwise.network.AndroidAppNetworkAccess
import app.stemwise.network.AppNetworkAccess
import app.stemwise.network.AppNetworkState
import app.stemwise.network.UnmeteredNetworkRequiredException
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Current signed updater recovery; methods are explicitly sequenced in a disposable target. */
@RunWith(AndroidJUnit4::class)
class CurrentVerifiedYtDlpUpdaterInstrumentationTest {
    @Test
    fun bundledExtractorBootstrapsWithoutGithubAndPassesOfflineChecks() = runBlocking {
        val context = targetContext()
        val roots = selectedRoots(context)
        YoutubeDlEngine(context, fixtureNetworkAccess, null).ensureReady().getOrThrow()
        val requests = AtomicInteger()
        val hashReads = AtomicInteger()
        val versionReads = AtomicInteger()
        val noGithub = OkHttpClient.Builder().addInterceptor {
            requests.incrementAndGet()
            error("Bundled admission attempted GitHub")
        }.build()
        val updater = VerifiedYtDlpUpdater(context, noGithub, fixtureNetworkAccess,
            SelectedRootYtDlpAuthority(roots), inspectionFactory = {
                YtDlpBinaryInspection(
                    hashBinary = { hashReads.incrementAndGet(); YtDlpReleaseSecurity.sha256(it) },
                    readVersion = { versionReads.incrementAndGet(); YtDlpReleaseSecurity.versionFromBinary(it) })
            }) { _, _, _ -> error("Bundled admission downloaded an update") }
        val admission = updater.admitExecutable()
        assertEquals(YT_DLP_BUNDLED_SHA256, admission.binary.sha256)
        admission.requireCurrentRoot()
        assertEquals(1, hashReads.get())
        assertEquals(1, versionReads.get())
        updater.admitExecutable().requireCurrentRoot()
        assertEquals("A new admission must rehash even unchanged bytes", 2, hashReads.get())
        assertEquals(2, versionReads.get())
        val offline = object : AppNetworkAccess {
            override val state = MutableStateFlow(AppNetworkState(false, false, false))
            override fun currentState() = state.value
        }
        val engine = YoutubeDlEngine(context, offline, roots)
        engine.ensureReady().getOrThrow()
        val request = playlistMetadataRequest("https://music.youtube.com/playlist?list=PL1234567890")
        val failure = captureFailure { engine.execute(request, "offline-sealed-metadata") }
        assertTrue(failure is UnmeteredNetworkRequiredException)
        val sealed = request.buildCommand()
        assertTrue("--skip-download" in sealed)
        assertTrue("--ignore-config" in sealed)
        assertTrue("--no-plugin-dirs" in sealed)
        requireMetadataOnlyYtDlpRequest(sealed)
        assertEquals(EngineState.READY, engine.status.value.state)

        smokeYtDlpCandidate(context, offline, executable(context), YT_DLP_BUNDLED_VERSION)
        assertEquals(0, requests.get())
    }

    @Test
    fun quarantinesHostileLegacyWithoutExecutingBundled() = runBlocking {
        val context = targetContext()
        selectedRoots(context)
        val directory = privateYtDlpDirectory(context)
        check(directory.mkdirs() || directory.isDirectory)
        check(directory.listFiles().orEmpty().all { !it.exists() || it.delete() })
        executable(context).writeBytes(HOSTILE_YT_DLP)
        assertArrayEquals(HOSTILE_YT_DLP, executable(context).readBytes())
        assertFalse(hostileMarker(context).exists())

        // Dependency extraction is initialization, not execution. The updater must move unknown
        // owner bytes away before the upstream package is allowed to restore its bundled zipapp.
        YoutubeDlEngine(context, fixtureNetworkAccess, null).ensureReady().getOrThrow()

        assertFalse(hostileMarker(context).exists())
        assertArrayEquals(HOSTILE_YT_DLP, quarantine(context).readBytes())
        assertEquals(YT_DLP_BUNDLED_SHA256, sha256(executable(context)))
        assertEquals(YT_DLP_BUNDLED_VERSION, YtDlpReleaseSecurity.versionFromBinary(
            executable(context)
        ).toString())
        assertFalse(initializationPreserved(context).exists())
        println("EXACT_YT_DLP_HOSTILE_QUARANTINE=PASS")
    }

    @Test
    fun bundledReleaseIsEligibleOfflineAndPreservesSignedAuthority() = runBlocking {
        val context = targetContext()
        val roots = selectedRoots(context)
        assertTrue(YtDlpDateVersion.parse(YT_DLP_BUNDLED_VERSION) >=
            YtDlpDateVersion.parse(YT_DLP_MINIMUM_SAFE_VERSION))
        assertEquals(YT_DLP_BUNDLED_SHA256, sha256(executable(context)))
        assertBundledOfflineAdmission(context, roots)

        val updater = VerifiedYtDlpUpdater(context, fixtureNetworkAccess, roots)
        val installed = updater.checkForUpdate()
        File(context.cacheDir, "current-updater-expected.receipt").writeBytes(checkNotNull(installed.receiptBytes))
        assertExactOfficial(installed)
        val admission = updater.admitExecutable()
        assertExactOfficial(admission.binary)
        admission.requireCurrentRoot()
        assertOfficialPrivateState(context)
        assertOfficialAuthorityExact(context)

        // Signed bundled admission stays separate from the explicit optional updater.
        // The public engine remains restricted to playlist metadata, including for signed bytes.
        val beforeRejectedVersion = executable(context).readBytes()
        val authorityBeforeRejectedVersion = listOf(PORTABLE_AUTHORITY, PORTABLE_AUTHORITY_BACKUP, PORTABLE_AUTHORITY_TEMP)
            .associateWith { name -> findRootDocument(context, name)?.let { readRootFile(context, name) } }
        val engine = YoutubeDlEngine(context, fixtureNetworkAccess, roots)
        val request = YoutubeDLRequest(emptyList<String>()).apply { addOption("--version") }
        val rejectedVersion = captureFailure { engine.execute(request, "current-ytdlp-version-refusal") }
        assertEquals("METADATA_REQUEST_REQUIRED", rejectedVersion.message)
        assertArrayEquals(beforeRejectedVersion, executable(context).readBytes())
        authorityBeforeRejectedVersion.forEach { (name, bytes) ->
            if (bytes == null) assertNull(findRootDocument(context, name))
            else assertArrayEquals(bytes, readRootFile(context, name))
        }
        assertOfficialPrivateState(context) // Includes active version readback from the actual zipapp.
        assertOfficialAuthorityExact(context)
        assertFalse(hostileMarker(context).exists())
        println("EXACT_YT_DLP_OFFICIAL_INSTALL_SMOKE_EXECUTE=PASS")
    }

    @Test
    fun stagesCommittedVerifiedDownloadForProcessDeath() = runBlocking {
        val context = targetContext()
        assertOfficialPrivateState(context)
        assertOfficialAuthorityExact(context)

        copyAndSync(executable(context), verifiedDownload(context))
        copyAndSync(activeReceipt(context), pendingPortableReceipt(context))
        assertTrue(DocumentsContract.deleteDocument(
            context.contentResolver,
            checkNotNull(findRootDocument(context, PORTABLE_AUTHORITY))
        ))
        assertTrue(DocumentsContract.deleteDocument(
            context.contentResolver,
            checkNotNull(findRootDocument(context, PORTABLE_AUTHORITY_BACKUP))
        ))
        assertNull(findRootDocument(context, PORTABLE_AUTHORITY))
        assertNull(findRootDocument(context, PORTABLE_AUTHORITY_BACKUP))
        assertNull(findRootDocument(context, PORTABLE_AUTHORITY_TEMP))
        context.resources.openRawResource(com.yausername.youtubedl_android.R.raw.ytdlp)
            .use { bundled ->
                FileOutputStream(executable(context), false).use { output ->
                    bundled.copyTo(output)
                    output.flush()
                    output.fd.sync()
                }
            }
        assertTrue(activeReceipt(context).delete())
        assertTrue(pendingPortableReceipt(context).isFile)
        assertEquals(expectedRelease(context).sha256, sha256(verifiedDownload(context)))
        assertEquals(YT_DLP_BUNDLED_SHA256, sha256(executable(context)))
        assertTrue(pendingPortableReceipt(context).isFile)
        println("EXACT_YT_DLP_COMMITTED_DOWNLOAD_CRASH_STATE_STAGED=PASS")
    }

    @Test
    fun freshProcessPromotesCommittedVerifiedDownloadOffline() = runBlocking {
        val context = targetContext()
        assertEquals(expectedRelease(context).sha256, sha256(verifiedDownload(context)))
        assertEquals(YT_DLP_BUNDLED_SHA256, sha256(executable(context)))
        val requests = AtomicInteger()
        val smokes = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            throw AssertionError("Recovery unexpectedly requested ${chain.request().url}")
        }.build()
        val updater = VerifiedYtDlpUpdater(
            context,
            client,
            OfflineNetworkAccess,
            SelectedRootYtDlpAuthority(selectedRoots(context))
        ) { _, _, _ -> smokes.incrementAndGet() }

        val admission = updater.admitExecutable()
        assertExactOfficial(admission.binary)
        admission.requireCurrentRoot()
        assertEquals(0, requests.get())
        assertEquals(0, smokes.get())
        assertFalse(verifiedDownload(context).exists())
        assertFalse(pendingPortableReceipt(context).exists())
        assertOfficialPrivateState(context)
        assertOfficialAuthorityExact(context)
        println("EXACT_YT_DLP_COMMITTED_DOWNLOAD_PROCESS_DEATH_RECOVERY=PASS")
    }

    @Test
    fun stagesOfficialInitializationPreservationForProcessDeath() = runBlocking {
        val context = targetContext()
        val updater = VerifiedYtDlpUpdater(
            context,
            fixtureNetworkAccess,
            selectedRoots(context)
        )
        val preservation = checkNotNull(updater.preserveForPackageInitialization())
        assertEquals(expectedRelease(context).sha256, preservation.sha256)
        assertFalse(executable(context).exists())
        assertEquals(expectedRelease(context).sha256, sha256(initializationPreserved(context)))
        assertFalse(hostileMarker(context).exists())
        // Intentionally do not restore. Run-Tester force-stops this process before the next phase.
        println("EXACT_YT_DLP_PROCESS_DEATH_STATE_STAGED=PASS")
    }

    @Test
    fun freshProcessRecoversInitializationPreservation() = runBlocking {
        val context = targetContext()
        assertEquals(expectedRelease(context).sha256, sha256(initializationPreserved(context)))
        assertFalse(executable(context).exists())
        val updater = VerifiedYtDlpUpdater(
            context,
            fixtureNetworkAccess,
            selectedRoots(context)
        )
        val preservation = checkNotNull(updater.preserveForPackageInitialization())
        assertEquals(expectedRelease(context).sha256, preservation.sha256)
        updater.restoreAfterPackageInitialization(preservation)
        val admission = updater.admitExecutable()
        assertExactOfficial(admission.binary)
        admission.requireCurrentRoot()
        assertOfficialPrivateState(context)
        assertOfficialAuthorityExact(context)
        println("EXACT_YT_DLP_PROCESS_DEATH_RECOVERY=PASS")
    }

    @Test
    fun futurePortableSchemaFailsClosedWithoutRewritingAnyBytes() = runBlocking {
        val context = targetContext()
        val roots = selectedRoots(context)
        val canonicalBefore = readRootFile(context, PORTABLE_AUTHORITY)
        val backupBefore = readRootFile(context, PORTABLE_AUTHORITY_BACKUP)
        assertArrayEquals(canonicalBefore, backupBefore)
        assertPortableSelectionVectors(canonicalBefore, publicKey(context))
        assertNull(findRootDocument(context, PORTABLE_AUTHORITY_TEMP))
        val temp = requireNotNull(DocumentsContract.createDocument(
            context.contentResolver,
            selectedRootDocument(context),
            "application/json",
            PORTABLE_AUTHORITY_TEMP
        ))
        writeDocument(context, temp, FUTURE_AUTHORITY_BYTES)
        try {
            val failure = captureFailure {
                SelectedRootYtDlpAuthority(roots).captureSession().read(publicKey(context))
            }
            assertEquals(PORTABLE_YT_DLP_AUTHORITY_RECOVERY_REQUIRED, failure.message)
            assertArrayEquals(FUTURE_AUTHORITY_BYTES, readRootFile(context, PORTABLE_AUTHORITY_TEMP))
            assertArrayEquals(canonicalBefore, readRootFile(context, PORTABLE_AUTHORITY))
            assertArrayEquals(backupBefore, readRootFile(context, PORTABLE_AUTHORITY_BACKUP))
        } finally {
            assertTrue(DocumentsContract.deleteDocument(context.contentResolver, temp))
        }
        assertNull(findRootDocument(context, PORTABLE_AUTHORITY_TEMP))
        assertOfficialAuthorityExact(context)
        println("EXACT_YT_DLP_FUTURE_SCHEMA_PRESERVED_FAIL_CLOSED=PASS")
    }

    private suspend fun assertPortableSelectionVectors(v1: ByteArray, key: ByteArray) {
        val libraryId = JSONObject(v1.toString(Charsets.UTF_8)).getString("libraryId")
        val parsed = parsePortableYtDlpAuthority(v1, libraryId, key)
        assertExactOfficial(VerifiedYtDlpBinary(
            parsed.version,
            parsed.sha256,
            parsed.receiptBytes
        ))
        assertArrayEquals(v1, parsed.toBytes())
        val durable = mapOf(
            SelectedLibraryContract.YT_DLP_AUTHORITY to v1,
            SelectedLibraryContract.YT_DLP_AUTHORITY_BACKUP to v1
        )
        assertNull(selectPortableYtDlpAuthority(emptyMap(), libraryId, key))
        assertTrue(parsed.sameRelease(checkNotNull(
            selectPortableYtDlpAuthority(emptyMap(), libraryId, key, parsed)
        )))
        assertEquals(
            PORTABLE_YT_DLP_AUTHORITY_RECOVERY_REQUIRED,
            captureFailure {
                selectPortableYtDlpAuthority(
                    mapOf(SelectedLibraryContract.YT_DLP_AUTHORITY_TEMP to byteArrayOf()),
                    libraryId,
                    key
                )
            }.message
        )
        assertTrue(parsed.sameRelease(checkNotNull(
            selectPortableYtDlpAuthority(
                mapOf(SelectedLibraryContract.YT_DLP_AUTHORITY_TEMP to byteArrayOf()),
                libraryId,
                key,
                parsed
            )
        )))
        val truncated = v1.copyOf(v1.size / 2)
        listOf(
            mapOf(
                SelectedLibraryContract.YT_DLP_AUTHORITY to truncated,
                SelectedLibraryContract.YT_DLP_AUTHORITY_TEMP to v1
            ),
            mapOf(
                SelectedLibraryContract.YT_DLP_AUTHORITY to v1,
                SelectedLibraryContract.YT_DLP_AUTHORITY_TEMP to truncated
            )
        ).forEach { interruptedWrite ->
            val recovered = selectPortableYtDlpAuthority(
                interruptedWrite,
                libraryId,
                key
            )
            assertNotNull(recovered)
            assertTrue(parsed.sameRelease(checkNotNull(recovered)))
        }
        assertEquals(
            PORTABLE_YT_DLP_AUTHORITY_RECOVERY_REQUIRED,
            captureFailure {
                selectPortableYtDlpAuthority(
                    mapOf(SelectedLibraryContract.YT_DLP_AUTHORITY_TEMP to truncated),
                    libraryId,
                    key
                )
            }.message
        )
        assertTrue(parsed.sameRelease(checkNotNull(
            selectPortableYtDlpAuthority(
                mapOf(SelectedLibraryContract.YT_DLP_AUTHORITY_TEMP to truncated),
                libraryId,
                key,
                parsed
            )
        )))
        assertEquals(
            PORTABLE_YT_DLP_AUTHORITY_RECOVERY_REQUIRED,
            captureFailure {
                selectPortableYtDlpAuthority(
                    durable +
                        (SelectedLibraryContract.YT_DLP_AUTHORITY_TEMP to byteArrayOf(0, 1, 2)),
                    libraryId,
                    key
                )
            }.message
        )

        val futureJson = JSONObject(v1.toString(Charsets.UTF_8)).apply {
            remove("integritySha256")
            put("schemaVersion", 2L)
            put("futureOwnerField", "must survive byte-for-byte")
            put("integritySha256", CanonicalJson.sha256(this))
        }
        val future = CanonicalJson.encode(futureJson)
        val futureBefore = future.copyOf()
        val futureTempFailure = captureFailure {
            selectPortableYtDlpAuthority(
                durable + (SelectedLibraryContract.YT_DLP_AUTHORITY_TEMP to future),
                libraryId,
                key
            )
        }
        assertEquals(PORTABLE_YT_DLP_AUTHORITY_RECOVERY_REQUIRED, futureTempFailure.message)
        assertArrayEquals(futureBefore, future)
        val futureCanonicalFailure = captureFailure {
            selectPortableYtDlpAuthority(
                mapOf(
                    SelectedLibraryContract.YT_DLP_AUTHORITY to future,
                    SelectedLibraryContract.YT_DLP_AUTHORITY_BACKUP to v1
                ),
                libraryId,
                key
            )
        }
        assertEquals(PORTABLE_YT_DLP_AUTHORITY_RECOVERY_REQUIRED, futureCanonicalFailure.message)
        assertArrayEquals(futureBefore, future)

        val foreignJson = JSONObject(v1.toString(Charsets.UTF_8)).apply {
            remove("integritySha256")
            put("kind", "future-owner.yt-dlp-authority")
            put("integritySha256", CanonicalJson.sha256(this))
        }
        val foreign = CanonicalJson.encode(foreignJson)
        val foreignBefore = foreign.copyOf()
        val foreignFailure = captureFailure {
            selectPortableYtDlpAuthority(
                durable + (SelectedLibraryContract.YT_DLP_AUTHORITY_TEMP to foreign),
                libraryId,
                key
            )
        }
        assertEquals(PORTABLE_YT_DLP_AUTHORITY_RECOVERY_REQUIRED, foreignFailure.message)
        assertArrayEquals(foreignBefore, foreign)
    }

    @Test
    fun reacquiresSameOfficialAfterPrivateLossWithoutPortableRollback() = runBlocking {
        val context = targetContext()
        // Lose only this disposable fixture's private extractor state; portable authority stays.
        check(privateYtDlpDirectory(context).deleteRecursively())
        val receipts = File(context.noBackupFilesDir, "stemwise-verified-ytdlp")
        check(!receipts.exists() || receipts.deleteRecursively())
        YoutubeDlEngine(context, fixtureNetworkAccess, null).ensureReady().getOrThrow()
        val roots = selectedRoots(context)
        assertOfficialAuthorityExact(context)
        if (expectedRelease(context).version > YtDlpDateVersion.parse(YT_DLP_BUNDLED_VERSION)) {
            val canonicalBefore = readRootFile(context, PORTABLE_AUTHORITY)
            val backupBefore = readRootFile(context, PORTABLE_AUTHORITY_BACKUP)
            val requests = AtomicInteger()
            val smokes = AtomicInteger()
            val client = OkHttpClient.Builder().addInterceptor {
                requests.incrementAndGet()
                throw AssertionError("Offline admission unexpectedly requested the network")
            }.build()
            val offline = VerifiedYtDlpUpdater(
                context,
                client,
                OfflineNetworkAccess,
                SelectedRootYtDlpAuthority(roots)
            ) { _, _, _ -> smokes.incrementAndGet() }
            val failure = captureFailure { offline.requireExecutableEligible() }
            assertEquals("YT_DLP_SECURITY_UPDATE_REQUIRED", failure.message)
            assertEquals(0, requests.get())
            assertEquals(0, smokes.get())
            assertEquals(YT_DLP_BUNDLED_SHA256, sha256(executable(context)))
            assertArrayEquals(canonicalBefore, readRootFile(context, PORTABLE_AUTHORITY))
            assertArrayEquals(backupBefore, readRootFile(context, PORTABLE_AUTHORITY_BACKUP))
            assertNull(findRootDocument(context, PORTABLE_AUTHORITY_TEMP))
            assertFalse(hostileMarker(context).exists())
        } else {
            assertBundledOfflineAdmission(context, roots)
        }

        val updater = VerifiedYtDlpUpdater(context, fixtureNetworkAccess, roots)
        val installed = updater.checkForUpdate()
        assertExactOfficial(installed)
        val admission = updater.admitExecutable()
        assertExactOfficial(admission.binary)
        admission.requireCurrentRoot()
        assertOfficialPrivateState(context)
        assertOfficialAuthorityExact(context)
        println("EXACT_YT_DLP_PRIVATE_LOSS_REACQUISITION=PASS")
    }

    private suspend fun assertBundledOfflineAdmission(
        context: Context,
        roots: LibraryRootManager
    ) {
        val requests = AtomicInteger()
        val smokes = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            throw AssertionError("Offline qualification unexpectedly requested ${chain.request().url}")
        }.build()
        val updater = VerifiedYtDlpUpdater(
            context,
            client,
            OfflineNetworkAccess,
            SelectedRootYtDlpAuthority(roots)
        ) { _, _, _ -> smokes.incrementAndGet() }

        val admitted = updater.requireExecutableEligible()
        assertEquals(YT_DLP_BUNDLED_SHA256, admitted.sha256)
        assertNotNull(admitted.receiptBytes)
        val receiptFile = File(context.noBackupFilesDir, "stemwise-verified-ytdlp/active.receipt")
        val timestamp = receiptFile.lastModified()
        updater.requireExecutableEligible()
        assertEquals(timestamp, receiptFile.lastModified())
        val updateFailure = captureFailure {
            updater.checkForUpdate()
        }
        assertTrue(updateFailure is UnmeteredNetworkRequiredException)
        assertFalse((updateFailure as UnmeteredNetworkRequiredException).operationStarted)
        assertEquals(0, requests.get())
        assertEquals(0, smokes.get())
        assertEquals(YT_DLP_BUNDLED_SHA256, sha256(executable(context)))
        assertFalse(hostileMarker(context).exists())
    }

    private suspend fun selectedRoots(context: Context): LibraryRootManager {
        // The provider process owns a production startup writer even under this isolated runner.
        checkNotNull(context.contentResolver.call(Uri.parse("content://${GoldenQaDocumentsProvider.AUTHORITY}"),
            GoldenQaDocumentsProvider.METHOD_AWAIT_APPLICATION_STARTUP, null, null))
        val roots = LibraryRootManager(
            context,
            PackageMutationCoordinator(context),
            LibraryDatabase.get(context).libraryDao()
        )
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        context.grantUriPermission(context.packageName, GoldenQaDocumentsProvider.treeUri, flags)
        val tree = Uri.parse(CURRENT_TREE_URI)
        context.grantUriPermission(context.packageName, tree, flags)
        val state = roots.initialize().let { initial ->
            if (initial.ready) initial else {
                val folder = File(context.noBackupFilesDir, "golden-enrichment-document-provider/current-updater")
                check(folder.mkdirs() || folder.isDirectory)
                roots.commitValidatedCandidateForDevelopment(roots.validateCandidate(tree).getOrThrow()).getOrThrow()
            }
        }
        assertTrue("Selected root unavailable: ${state.errorCode}", state.ready)
        assertEquals(CURRENT_TREE_URI, state.treeUri.toString())
        return roots
    }

    private fun expectedRelease(context: Context): YtDlpVerifiedReceipt {
        val receipt = File(context.cacheDir, "current-updater-expected.receipt")
        check(receipt.length() in 1L..(128L * 1024L))
        return decodeYtDlpReceipt(receipt.readBytes()).also {
            validateYtDlpReceipt(it, publicKey(context))
            check(it.version >= YtDlpDateVersion.parse(YT_DLP_MINIMUM_SAFE_VERSION))
        }
    }

    private fun assertExactOfficial(binary: VerifiedYtDlpBinary) {
        val expected = expectedRelease(targetContext())
        assertEquals(expected.version, binary.version)
        assertEquals(expected.sha256, binary.sha256)
        assertNotNull(binary.receiptBytes)
    }

    private fun assertOfficialPrivateState(context: Context) {
        assertEquals(expectedRelease(context).sha256, sha256(executable(context)))
        assertEquals(
            expectedRelease(context).version.toString(),
            YtDlpReleaseSecurity.versionFromBinary(executable(context)).toString()
        )
        assertFalse(initializationPreserved(context).exists())
        assertFalse(quarantine(context).exists())
        assertFalse(hostileMarker(context).exists())
    }

    private suspend fun assertOfficialAuthorityExact(context: Context) {
        val canonical = readRootFile(context, PORTABLE_AUTHORITY)
        val backup = readRootFile(context, PORTABLE_AUTHORITY_BACKUP)
        assertArrayEquals(canonical, backup)
        val libraryId = checkNotNull(LibraryDatabase.get(context).libraryDao().libraryRootIdentity()).libraryId
        val authority = parsePortableYtDlpAuthority(canonical, libraryId, publicKey(context))
        assertEquals(expectedRelease(context).version.toString(), authority.version.toString())
        assertEquals(expectedRelease(context).sha256, authority.sha256)
        assertNull(findRootDocument(context, PORTABLE_AUTHORITY_TEMP))
    }

    private suspend fun selectedRootDocument(context: Context): Uri {
        val treeText = checkNotNull(LibraryDatabase.get(context).libraryDao().libraryRootIdentity()).treeUri
        assertEquals(CURRENT_TREE_URI, treeText)
        val tree = Uri.parse(requireNotNull(treeText))
        return DocumentsContract.buildDocumentUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree)
        )
    }

    private suspend fun findRootDocument(context: Context, name: String): Uri? {
        val root = selectedRootDocument(context)
        val tree = DocumentsContract.buildTreeDocumentUri(root.authority, DocumentsContract.getTreeDocumentId(root))
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree)
        )
        var found: Uri? = null
        context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            ),
            null,
            null,
            null
        ).use { cursor: Cursor? ->
            checkNotNull(cursor)
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == name) {
                    check(found == null) { "Duplicate selected-root file: $name" }
                    found = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
                }
            }
        }
        return found
    }

    private suspend fun readRootFile(context: Context, name: String): ByteArray {
        val document = requireNotNull(findRootDocument(context, name)) {
            "Missing selected-root file: $name"
        }
        return context.contentResolver.openInputStream(document).use { input ->
            checkNotNull(input)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                check(output.size() <= 256 * 1024) { "Portable authority too large" }
            }
            output.toByteArray()
        }
    }

    private suspend fun writeDocument(context: Context, document: Uri, bytes: ByteArray) {
        context.contentResolver.openFileDescriptor(document, "wt").use { descriptor ->
            checkNotNull(descriptor)
            FileOutputStream(descriptor.fileDescriptor).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
        }
        assertArrayEquals(bytes, readRootFile(context, PORTABLE_AUTHORITY_TEMP))
    }

    private fun publicKey(context: Context): ByteArray = context.resources.openRawResource(
        app.stemwise.R.raw.yt_dlp_release_signing_public_key
    ).use { it.readBytes() }

    private suspend fun captureFailure(block: suspend () -> Unit): Throwable {
        var failure: Throwable? = null
        try {
            block()
        } catch (error: Throwable) {
            failure = error
        }
        return requireNotNull(failure) { "Expected operation to fail closed" }
    }

    private fun targetContext(): Context =
        InstrumentationRegistry.getInstrumentation().targetContext.also { context ->
            assertEquals("app.stemwise.development", context.packageName)
            assertEquals(Application::class.java, context.applicationContext.javaClass)
            assertEquals("${context.packageName}:updater_qualification", Application.getProcessName())
        }

    private fun executable(context: Context): File = File(privateYtDlpDirectory(context), "yt-dlp")
    private fun verifiedDownload(context: Context): File =
        File(privateYtDlpDirectory(context), ".yt-dlp.verified-download")
    private fun activeReceipt(context: Context): File =
        File(File(context.noBackupFilesDir, "stemwise-verified-ytdlp"), "active.receipt")
    private fun pendingPortableReceipt(context: Context): File =
        File(
            File(context.noBackupFilesDir, "stemwise-verified-ytdlp"),
            "pending-portable.receipt"
        )
    private fun quarantine(context: Context): File =
        File(privateYtDlpDirectory(context), ".yt-dlp.untrusted-legacy")
    private fun initializationPreserved(context: Context): File =
        File(privateYtDlpDirectory(context), ".yt-dlp.initialization-preserved")
    private fun hostileMarker(context: Context): File =
        File(privateYtDlpDirectory(context), ".yt-dlp.hostile-executed")
    private fun privateYtDlpDirectory(context: Context): File =
        File(File(context.noBackupFilesDir, "youtubedl-android"), "yt-dlp")

    private fun copyAndSync(source: File, target: File) {
        source.inputStream().use { input ->
            FileOutputStream(target, false).use { output ->
                input.copyTo(output)
                output.flush()
                output.fd.sync()
            }
        }
    }

    private fun sha256(file: File): String {
        assertTrue("Missing exact file ${file.absolutePath}", file.isFile)
        return file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private object OfflineNetworkAccess : AppNetworkAccess {
        private val snapshot = AppNetworkState(
            hasActiveNetwork = false,
            activeNetworkValidated = false,
            activeNetworkMetered = true,
        )
        override val state: StateFlow<AppNetworkState> = MutableStateFlow(snapshot)
        override fun currentState(): AppNetworkState = snapshot
    }

    private companion object {
        val fixtureNetworkAccess by lazy {
            AndroidAppNetworkAccess(InstrumentationRegistry.getInstrumentation().targetContext)
        }
        const val CURRENT_TREE_URI =
            "content://app.stemwise.development.golden.qa.documents/tree/root%2Fcurrent-updater"
        const val PORTABLE_AUTHORITY = ".stemwise-ytdlp-authority.json"
        const val PORTABLE_AUTHORITY_BACKUP = ".stemwise-ytdlp-authority.json.bak"
        const val PORTABLE_AUTHORITY_TEMP = ".stemwise-ytdlp-authority.json.tmp"
        val HOSTILE_YT_DLP = (
            "from pathlib import Path\n" +
                "Path(__file__).with_name('.yt-dlp.hostile-executed').write_text(" +
                "'HOSTILE_EXECUTED', encoding='utf-8')\n" +
                "raise SystemExit(0)\n"
            ).toByteArray(Charsets.UTF_8)
        val FUTURE_AUTHORITY_BYTES = (
            "{\"schemaVersion\":2,\"kind\":\"stemwise.yt-dlp-authority\"," +
                "\"opaque\":{\"must\":\"survive-byte-for-byte\"},\"future\":true}"
            ).toByteArray(Charsets.UTF_8)
    }
}
