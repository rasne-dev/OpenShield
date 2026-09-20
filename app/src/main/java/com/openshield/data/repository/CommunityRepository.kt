package com.openshield.data.repository

import android.util.Log
import com.openshield.data.db.PendingReportEntity
import com.openshield.data.db.SpamDatabase
import com.openshield.data.util.PhoneNumberNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.pow

@Singleton
class CommunityRepository @Inject constructor(
    private val db: SpamDatabase,
    private val consentManager: ConsentManager
) {
    companion object {
        private const val BASE_URL             = "https://openshield-api.ensarkaralii.workers.dev"
        private const val TAG                  = "CommunityRepo"
        private const val MIN_SYNC_MS          = 6 * 60 * 60 * 1000L   // 6 saat
        private const val MAX_RETRY            = 5
        private const val BASE_BACKOFF_MS      = 30_000L                // 30 sn
        private const val MAX_BACKOFF_MS       = 60 * 60 * 1000L        // 1 saat
    }

    // ── Spam Oyu ──────────────────────────────────────────────────────────────

    suspend fun reportSpam(
        number: String,
        triggeredRules: List<String>,
        isWifiConnected: Boolean
    ) = vote(number, triggeredRules, "spam", isWifiConnected)

    // ── Spam Değil Oyu ────────────────────────────────────────────────────────

    suspend fun reportNotSpam(
        number: String,
        isWifiConnected: Boolean
    ) = vote(number, emptyList(), "not_spam", isWifiConnected)

    // ── Ortak Oy Mantığı ─────────────────────────────────────────────────────

    private suspend fun vote(
        number: String,
        triggeredRules: List<String>,
        voteType: String,
        isWifiConnected: Boolean
    ) = withContext(Dispatchers.IO) {
        if (!consentManager.communityConsent) return@withContext

        val normalNumber = PhoneNumberNormalizer.normalize(number)
        val hash         = PhoneNumberNormalizer.sha256(number)
        val rulesJson    = JSONArray(triggeredRules).toString()

        if (isWifiConnected) {
            val sent = sendVote(hash, normalNumber, triggeredRules, voteType)
            if (!sent) enqueue(hash, normalNumber, rulesJson, voteType)
        } else {
            enqueue(hash, normalNumber, rulesJson, voteType)
        }
    }

    // ── Kuyruktaki Raporları Gönder (Wi-Fi'ye bağlanınca) ────────────────────

    suspend fun flushPendingReports() = withContext(Dispatchers.IO) {
        if (!consentManager.communityConsent) return@withContext

        val due = db.pendingReportDao().getDue()
        if (due.isEmpty()) return@withContext
        Log.d(TAG, "flush: ${due.size} bekleyen rapor")

        for (report in due) {
            val rules = try {
                val arr = JSONArray(report.triggeredRules)
                (0 until arr.length()).map { arr.getString(it) }
            } catch (_: Exception) { emptyList() }

            val sent = sendVote(report.numberHash, report.number.ifBlank { report.numberHash }, rules, report.voteType)

            if (sent) {
                db.pendingReportDao().deleteById(report.id)
                Log.d(TAG, "flush OK: ${report.numberHash.take(12)}")
            } else {
                val newRetry = report.retryCount + 1
                if (newRetry >= MAX_RETRY) {
                    // Maksimum deneme — bırak
                    db.pendingReportDao().deleteById(report.id)
                    Log.w(TAG, "max retry aşıldı, silindi: ${report.numberHash.take(12)}")
                } else {
                    // Exponential backoff: 30s, 60s, 120s, 240s, 480s…
                    val backoff = min(
                        BASE_BACKOFF_MS * (2.0.pow(newRetry)).toLong(),
                        MAX_BACKOFF_MS
                    )
                    db.pendingReportDao().update(
                        report.copy(
                            retryCount  = newRetry,
                            nextRetryAt = System.currentTimeMillis() + backoff
                        )
                    )
                    Log.d(TAG, "retry $newRetry — ${backoff / 1000}s sonra tekrar")
                }
            }
        }
    }

    // ── Community List Sync (6 saatte bir) ───────────────────────────────────

    suspend fun syncCommunityList() = withContext(Dispatchers.IO) {
        if (!consentManager.communityConsent) return@withContext

        val now = System.currentTimeMillis()
        if (now - consentManager.lastSyncTime < MIN_SYNC_MS) {
            Log.d(TAG, "sync skipped — çok erken")
            return@withContext
        }

        try {
            val since = consentManager.lastSyncTime
            val conn  = openGet("$BASE_URL/community-list?since=$since")

            if (conn.responseCode != 200) { conn.disconnect(); return@withContext }

            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val arr   = JSONArray(body)
            var added = 0
            for (i in 0 until arr.length()) {
                val hash = arr.getJSONObject(i).getString("hash")
                db.spamNumberDao().insertCommunityHash(hash)
                added++
            }

            consentManager.lastSyncTime = now
            Log.d(TAG, "sync tamamlandı — $added kayıt")
        } catch (e: Exception) {
            Log.w(TAG, "sync başarısız: ${e.message}")
        }
    }

    // ── İç Yardımcılar ────────────────────────────────────────────────────────

    private suspend fun sendVote(
        hash: String,
        number: String,
        rules: List<String>,
        voteType: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("numberHash", hash)
                put("number", number)       // admin panelinde görünmesi için
                put("voteType", voteType)
                put("triggeredRules", JSONArray(rules))
            }.toString()

            val conn = openPost("$BASE_URL/report", body)
            val code = conn.responseCode
            conn.disconnect()
            (code == 200).also {
                Log.d(TAG, "vote[$voteType] ${if (it) "OK" else "FAIL"} → $code")
            }
        } catch (e: Exception) {
            Log.w(TAG, "sendVote exception: ${e.message}")
            false
        }
    }

    private suspend fun enqueue(hash: String, number: String, rulesJson: String, voteType: String) {
        db.pendingReportDao().insert(
            PendingReportEntity(
                numberHash     = hash,
                number         = number,
                triggeredRules = rulesJson,
                voteType       = voteType
            )
        )
        Log.d(TAG, "kuyruğa eklendi [$voteType]: ${hash.take(12)}")
    }

    private fun openGet(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod  = "GET"
            connectTimeout = 8000
            readTimeout    = 8000
        }

    private fun openPost(url: String, body: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod  = "POST"
            doOutput       = true
            connectTimeout = 5000
            readTimeout    = 5000
            setRequestProperty("Content-Type", "application/json")
            outputStream.use { it.write(body.toByteArray()) }
        }
}
