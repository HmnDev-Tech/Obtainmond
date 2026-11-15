package io.obtainmond.data.repo

import io.obtainmond.data.model.TrackedApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AtomicAppStorageTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun saveAndReloadPerAppFilesLeavesNoTempFiles() {
        val appsDir = File(tempFolder.root, "apps")
        val storage = AtomicAppStorage(appsDir = appsDir)

        val alpha = TrackedApp(
            id = "org.example.alpha",
            name = "Alpha",
            author = "Example",
            url = "https://github.com/example/alpha",
            installedVersion = "1.0.0",
            latestVersion = "1.2.0"
        )
        val beta = TrackedApp(
            id = "org.example.beta",
            name = "Beta",
            author = "Example",
            url = "https://f-droid.org/packages/org.example.beta/",
            folders = listOf("FOSS")
        )

        storage.saveApp(alpha)
        storage.saveApp(beta)

        val leftoverTmp = appsDir.listFiles { f -> f.name.endsWith(".tmp") }?.toList().orEmpty()
        assertTrue("No .tmp files should remain after atomic writes", leftoverTmp.isEmpty())

        val freshStorage = AtomicAppStorage(appsDir = appsDir)
        val reloaded = freshStorage.loadAll().associateBy { it.id }
        assertEquals(2, reloaded.size)
        assertEquals("1.2.0", reloaded["org.example.alpha"]?.latestVersion)
        assertEquals(listOf("FOSS"), reloaded["org.example.beta"]?.folders)
    }

    @Test
    fun migratesLegacyTrackedAppsJsonOnceAndMarksMigrated() {
        val legacyFile = File(tempFolder.root, "tracked_apps.json")
        legacyFile.writeText(
            """
            [
              {
                "id": "com.legacy.app",
                "name": "Legacy App",
                "url": "https://github.com/legacy/app",
                "sourceType": "GITHUB",
                "installedVersion": "2.0.0"
              }
            ]
            """.trimIndent()
        )
        val appsDir = File(tempFolder.root, "apps")
        val storage = AtomicAppStorage(appsDir = appsDir, legacyAppsFile = legacyFile)

        val loaded = storage.loadAll()
        assertEquals(1, loaded.size)
        assertEquals("com.legacy.app", loaded.first().id)
        assertFalse("Legacy file should be moved after migration", legacyFile.exists())
        assertTrue(
            "Migrated backup marker file must exist",
            File(tempFolder.root, "tracked_apps.json.migrated").exists()
        )
        assertTrue(storage.fileForAppId("com.legacy.app").exists())
    }

    @Test
    fun concurrentWritesDoNotCorruptStore() = runBlocking {
        val appsDir = File(tempFolder.root, "apps_concurrent")
        val storage = AtomicAppStorage(appsDir = appsDir)

        val tasks = (1..20).map { i ->
            async(Dispatchers.IO) {
                storage.saveApp(
                    TrackedApp(
                        id = "io.concurrent.app$i",
                        name = "App $i",
                        author = "Concurrent",
                        url = "https://github.com/org/app$i",
                        latestVersion = "1.0.$i"
                    )
                )
            }
        }
        tasks.awaitAll()

        val reloaded = AtomicAppStorage(appsDir = appsDir).loadAll()
        assertEquals(20, reloaded.size)
    }
}
