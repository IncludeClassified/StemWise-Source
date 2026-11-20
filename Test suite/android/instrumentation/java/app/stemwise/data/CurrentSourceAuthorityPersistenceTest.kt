package app.stemwise.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.stemwise.qualification.StandardInstrumentationTargetGuard
import app.stemwise.qualification.databaseFixtureFiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

/** Current source authority survives projection rebuilds and a real database restart. */
@RunWith(AndroidJUnit4::class)
class CurrentSourceAuthorityPersistenceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun removeStaleAuthorityDatabase() {
        StandardInstrumentationTargetGuard.requireCurrentTarget()
        deleteAuthorityDatabase()
    }

    @After
    fun removeAuthorityDatabase() = deleteAuthorityDatabase()

    @Test fun changedDocumentLookupsUseBothUriIndexes() = runBlocking {
        val database = LibraryDatabase.buildFreshProjectionDatabase(context, DATABASE_NAME)
        try {
            val uri = "content://fixture/document/export"
            database.openHelper.writableDatabase.query(
                "EXPLAIN QUERY PLAN SELECT id AS packageId, packageDocumentUri AS documentUri FROM songs " +
                    "WHERE canonicalExternal = 1 AND packageDocumentUri IN (?) UNION SELECT packageId, documentUri " +
                    "FROM asset_observations WHERE documentUri IN (?)", arrayOf(uri, uri)
            ).use { cursor ->
                val details = buildString { while (cursor.moveToNext()) append(cursor.getString(3)).append('\n') }
                assertTrue(details, "index_songs_packageDocumentUri_canonicalExternal" in details)
                assertTrue(details, "index_asset_observations_documentUri" in details)
                assertTrue(details, !details.contains("SCAN songs") && !details.contains("SCAN asset_observations"))
            }
            assertTrue(database.libraryDao().canonicalPackagesForDocuments(listOf(uri)).isEmpty())
        } finally { database.close() }
    }

    @Test
    fun sourceAuthorityRetains8192RowsAcrossProjectionClearRestartAndNoOpRebuild() = runBlocking {
        var database = LibraryDatabase.buildFreshProjectionDatabase(context, DATABASE_NAME)
        val libraryId = "source-authority-scale"
        val timestamp = 1_725_000_000_000L
        try {
            val dao = database.libraryDao()
            val rows = (0 until 8_192).map { index ->
                val packageId = index.toString().padStart(11, '0')
                SourceBindingEntity(
                    libraryId = libraryId,
                    sourceId = "youtube-video:$packageId",
                    packageId = packageId,
                    state = if (index == 0) "DELETED" else "ACTIVE",
                    transactionId = "scale-$index",
                    updatedAtEpochMs = timestamp + index
                )
            }
            dao.upsertSourceBindings(rows)
            assertEquals(8_192, dao.sourceBindingCount(libraryId))
            database.openHelper.writableDatabase.query(
                "EXPLAIN QUERY PLAN SELECT sourceId FROM source_bindings " +
                    "WHERE libraryId = ? AND state = 'DELETED'",
                arrayOf(libraryId)
            ).use { cursor ->
                val plan = buildString {
                    while (cursor.moveToNext()) append(cursor.getString(3)).append('\n')
                }
                assertTrue(plan, "index_source_bindings_libraryId_state" in plan)
            }
            val sqlite = database.openHelper.writableDatabase
            sqlite.execSQL(
                "CREATE TRIGGER reject_source_binding_insert BEFORE INSERT ON source_bindings " +
                    "BEGIN SELECT RAISE(ABORT, 'unexpected source binding insert'); END"
            )
            sqlite.execSQL(
                "CREATE TRIGGER reject_source_binding_update BEFORE UPDATE ON source_bindings " +
                    "BEGIN SELECT RAISE(ABORT, 'unexpected source binding update'); END"
            )
            assertEquals(
                0,
                dao.rebuildVerifiedSourceBindings(
                    libraryId,
                    rows.map { it.copy(state = "ACTIVE", transactionId = "verified-rebuild") }
                )
            )
            assertEquals("DELETED", dao.sourceBinding(libraryId, rows.first().sourceId)?.state)
            dao.clearActiveLibraryProjection()
            assertEquals(8_192, dao.sourceBindingCount(libraryId))
        } finally {
            database.close()
        }

        database = LibraryDatabase.buildFreshProjectionDatabase(context, DATABASE_NAME)
        try {
            val dao = database.libraryDao()
            assertEquals(8_192, dao.sourceBindingCount(libraryId))
            assertEquals(1, dao.blockedSourceBindings(libraryId).size)
            assertEquals(
                8_192 - 1,
                dao.activeSourceBindings(libraryId).size
            )
        } finally {
            database.close()
        }
    }

    private fun deleteAuthorityDatabase() {
        context.deleteDatabase(DATABASE_NAME)
        databaseFixtureFiles(context, DATABASE_NAME).forEach { file ->
            check(!file.exists() || file.delete()) {
                "Unable to delete authority fixture ${file.absolutePath}"
            }
        }
        check(databaseFixtureFiles(context, DATABASE_NAME).isEmpty()) {
            "Authority fixture sidecars survived teardown for $DATABASE_NAME"
        }
    }

    private companion object {
        const val DATABASE_NAME = "current-authority-fixture"
    }
}
