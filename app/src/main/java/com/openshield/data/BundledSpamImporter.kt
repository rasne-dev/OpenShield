package com.openshield.data

import android.content.Context
import android.content.SharedPreferences
import com.openshield.data.db.SpamDatabase
import com.openshield.data.db.SpamNumberEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object BundledSpamImporter {

    private const val PREFS_NAME = "openshield_prefs"
    private const val KEY_BUNDLED_VERSION = "bundled_version"
    private const val CURRENT_BUNDLED_VERSION = 1

    suspend fun importIfNeeded(context: Context) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val installedVersion = prefs.getInt(KEY_BUNDLED_VERSION, 0)
        if (installedVersion >= CURRENT_BUNDLED_VERSION) return@withContext
        importFromAssets(context, prefs)
    }

    suspend fun importFromRemoteCsv(context: Context, csv: String) = withContext(Dispatchers.IO) {
        val db = SpamDatabase.getInstance(context)
        val dao = db.spamNumberDao()
        val entities = parseCsv(csv)
        if (entities.isEmpty()) return@withContext

        dao.deleteAllBundled()
        dao.insertAll(entities)
    }

    private suspend fun importFromAssets(context: Context, prefs: SharedPreferences) {
        val db = SpamDatabase.getInstance(context)
        val dao = db.spamNumberDao()
        try {
            val entities = context.assets.open("community_spam.csv").bufferedReader().use { reader ->
                parseCsv(reader.readText())
            }
            dao.deleteAllBundled()
            dao.insertAll(entities)
            prefs.edit().putInt(KEY_BUNDLED_VERSION, CURRENT_BUNDLED_VERSION).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun parseCsv(csv: String): List<SpamNumberEntity> {
        return csv.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull(::parseLine)
            .toList()
    }

    private fun parseLine(line: String): SpamNumberEntity? {
        val parts = line.trim().split(",")
        if (parts.size < 3) return null
        val number = com.openshield.data.util.PhoneNumberNormalizer.normalize(parts[0].trim())
        val category = parts[1].trim()
        val reportCount = parts[2].trim().toIntOrNull() ?: 1
        if (number.isBlank()) return null
        return SpamNumberEntity(
            number = number,
            label = category,
            isUserAdded = false,
            reportCount = reportCount
        )
    }
}
