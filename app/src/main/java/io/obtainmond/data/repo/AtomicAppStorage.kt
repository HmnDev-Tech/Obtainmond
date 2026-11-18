package io.obtainmond.data.repo

import io.obtainmond.data.model.TrackedApp
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class AtomicAppStorage(
    private val appsDir: File,
    private val legacyAppsFile: File? = null,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }
) {
    private val appLocks = ConcurrentHashMap<String, Any>()
    private val indexedFiles = ConcurrentHashMap<String, TrackedApp>()

    private fun lockFor(appId: String): Any = appLocks.computeIfAbsent(appId) { Any() }

    fun fileForAppId(appId: String): File {
        val cleanPrefix = appId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .trim('.')
            .take(48)
            .ifBlank { "app" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(appId.toByteArray(StandardCharsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it) }
        return File(appsDir, "${cleanPrefix}_${digest}.json")
    }

    fun loadAll(): List<TrackedApp> {
        appsDir.mkdirs()
        cleanupTempFiles()

        val perAppFiles = appsDir.listFiles { file ->
            file.isFile && file.name.endsWith(".json", ignoreCase = true)
        }?.toList().orEmpty()

        val loaded = linkedMapOf<String, TrackedApp>()
        for (file in perAppFiles) {
            runCatching {
                val app = json.decodeFromString<TrackedApp>(file.readText())
                loaded[app.id] = app
                indexedFiles[app.id] = app
            }
        }

        val legacy = legacyAppsFile
        if (legacy != null && legacy.exists() && !File(appsDir, ".initialized").exists()) {
            val migrated = runCatching {
                json.decodeFromString<List<TrackedApp>>(legacy.readText())
            }.getOrDefault(emptyList())
            if (migrated.isNotEmpty()) {
                val missing = migrated.distinctBy { it.id }.filter { it.id !in loaded }
                val wroteAll = runCatching { missing.forEach { saveApp(it) } }.isSuccess
                missing.forEach { loaded[it.id] = it }
                val backupLegacy = File(legacy.parentFile, "${legacy.name}.migrated")
                if (wroteAll) {
                    runCatching {
                        Files.move(legacy.toPath(), backupLegacy.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
        }

        return loaded.values.toList()
    }

    fun saveApp(app: TrackedApp, previousId: String? = null) {
        appsDir.mkdirs()
        synchronized(lockFor(app.id)) {
            val target = fileForAppId(app.id)
            writeAtomically(target, json.encodeToString(app))
            indexedFiles[app.id] = app
        }
        if (previousId != null && previousId != app.id) {
            deleteApp(previousId)
        }
    }

    fun deleteApp(appId: String) {
        synchronized(lockFor(appId)) {
            indexedFiles.remove(appId)
            val target = fileForAppId(appId)
            if (target.exists()) {
                runCatching { target.delete() }
            }
            val tmp = File(target.parentFile, "${target.name}.tmp")
            if (tmp.exists()) {
                runCatching { tmp.delete() }
            }
        }
    }

    fun syncList(apps: List<TrackedApp>) {
        appsDir.mkdirs()
        val desiredIds = apps.map { it.id }.toSet()
        for (app in apps) {
            if (indexedFiles[app.id] != app || !fileForAppId(app.id).exists()) {
                saveApp(app)
            }
        }

        val staleIds = indexedFiles.keys.filter { it !in desiredIds }
        for (staleId in staleIds) {
            deleteApp(staleId)
        }
        val expectedFileNames = apps.map { fileForAppId(it.id).name }.toSet()
        appsDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.filter { it.name !in expectedFileNames }
            ?.forEach { runCatching { it.delete() } }
    }

    private fun cleanupTempFiles() {
        appsDir.listFiles { file ->
            file.isFile && file.name.endsWith(".tmp", ignoreCase = true)
        }?.forEach { runCatching { it.delete() } }
    }

    private fun writeAtomically(target: File, content: String) {
        val parent = target.parentFile ?: return
        parent.mkdirs()
        val tmp = File(parent, "${target.name}.tmp")
        FileOutputStream(tmp).use { fos ->
            fos.write(content.toByteArray(StandardCharsets.UTF_8))
            fos.flush()
            fos.fd.sync()
        }
        try {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }
}
