package com.openshield.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.openshield.data.db.*
import com.openshield.data.model.SmsHistoryItem
import com.openshield.data.util.PhoneNumberNormalizer
import kotlinx.coroutines.flow.Flow
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpamNumberRepository @Inject constructor(
    private val db: SpamDatabase
) {
    companion object {
        const val COMMUNITY_THRESHOLD = 5
    }

    // ─── SpamDetectionEngine arayüzü ──────────────────────────────────────────

    suspend fun isInBlacklist(number: String): Boolean =
        db.spamNumberDao().findByNumber(cleanNumber(number)) != null

    suspend fun isInWhitelist(number: String): Boolean =
        db.whitelistDao().findByNumber(cleanNumber(number)) != null

    suspend fun isCommunitySpam(number: String): Boolean {
        val hash = PhoneNumberNormalizer.sha256(number)
        val entity = db.spamNumberDao().findByNumber(hash)
        return entity != null && !entity.isUserAdded
    }

    suspend fun getCommunityReportCount(number: String): Int {
        val hash = PhoneNumberNormalizer.sha256(number)
        return if (db.spamNumberDao().findByNumber(hash) != null) 3 else 0
    }

    // ─── UI Flow'ları ─────────────────────────────────────────────────────────

    val spamNumbers: Flow<List<SpamNumberEntity>>
        get() = db.spamNumberDao().getUserAddedFlow()

    val whitelist: Flow<List<WhitelistEntity>>
        get() = db.whitelistDao().getAllFlow()

    val blockedLog: Flow<List<BlockedLogEntity>>
        get() = db.blockLogDao().getRecentFlow()

    /** Şüpheli — kullanıcı kararı bekleniyor */
    val pendingReviews: Flow<List<PendingReviewEntity>>
        get() = db.pendingReviewDao().getAllFlow()

    // ─── Kara / Beyaz Liste ───────────────────────────────────────────────────

    suspend fun addSpam(number: String, label: String = "") {
        db.spamNumberDao().insert(
            SpamNumberEntity(number = cleanNumber(number), label = label, isUserAdded = true)
        )
    }

    suspend fun removeSpam(number: String) =
        db.spamNumberDao().deleteByNumber(cleanNumber(number))

    suspend fun addWhitelist(number: String, name: String = "") {
        db.whitelistDao().insert(
            WhitelistEntity(number = cleanNumber(number), name = name)
        )
    }

    suspend fun removeWhitelist(number: String) =
        db.whitelistDao().deleteByNumber(cleanNumber(number))

    // ─── Geçmiş / Log ─────────────────────────────────────────────────────────

    suspend fun logBlocked(sender: String, reason: String, score: Float, body: String = "") {
        db.blockLogDao().insert(
            BlockedLogEntity(sender = sender, reason = reason, score = score, body = body)
        )
    }

    suspend fun clearHistory() = db.blockLogDao().clearAll()

    suspend fun clearAllData() {
        db.spamNumberDao().deleteAllUserSpam()
        db.whitelistDao().clearAll()
        db.blockLogDao().clearAll()
    }

    suspend fun communityReportCount(): Int = db.spamNumberDao().communityCount()

    // ─── Şüpheli mesaj akışı ─────────────────────────────────────────────────

    /**
     * SmsReceiver şüpheli mesajı buraya yazar.
     * Bildirim gösterilmez; uygulama açılınca dialog çıkar.
     */
    suspend fun logSuspicious(sender: String, reason: String, score: Float, body: String = "") {
        db.pendingReviewDao().insert(
            PendingReviewEntity(sender = sender, reason = reason, score = score, body = body)
        )
    }

    /**
     * Kullanıcı kararı:
     *  - isSpam = true: Kara listeye ekle, blocked_log'a yaz, pending_review'dan sil
     *  - isSpam = false: Beyaz listeye otomatik ekle (böylece tekrar sorulmaz), pending_review'dan sil
     */
    suspend fun decideSuspicious(item: PendingReviewEntity, isSpam: Boolean) {
        if (isSpam) {
            addSpam(item.sender, label = "Kullanıcı onayladı")
            db.blockLogDao().insert(
                BlockedLogEntity(
                    sender = item.sender,
                    reason = item.reason,
                    score = item.score,
                    body = item.body,
                    blockedAt = item.receivedAt
                )
            )
        } else {
            addWhitelist(item.sender, name = "Kullanıcı onayladı")
        }
        db.pendingReviewDao().deleteById(item.id)
    }

    /**
     * Bekleyen tüm şüpheli mesajları topluca kapatır.
     * markAsSafe = true ise tüm göndericileri otomatik olarak güvenli beyaz listeye ekler.
     */
    suspend fun dismissAllPendingReviews(markAsSafe: Boolean = true) {
        val list = db.pendingReviewDao().getAll()
        if (markAsSafe) {
            list.forEach { item ->
                addWhitelist(item.sender, name = "Toplu onaylandı")
            }
        }
        db.pendingReviewDao().clearAll()
    }

    // ─── Yardımcılar ──────────────────────────────────────────────────────────

    suspend fun readSmsHistory(context: Context, limit: Int = 400): List<SmsHistoryItem> {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return emptyList()

        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE
        )

        val result = mutableListOf<SmsHistoryItem>()
        val cursor = context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            projection,
            null,
            null,
            "${Telephony.Sms.DATE} DESC"
        )

        cursor?.use {
            val idIdx = it.getColumnIndexOrThrow(Telephony.Sms._ID)
            val addressIdx = it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIdx = it.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIdx = it.getColumnIndexOrThrow(Telephony.Sms.DATE)

            while (it.moveToNext() && result.size < limit) {
                result.add(
                    SmsHistoryItem(
                        id = it.getLong(idIdx),
                        sender = it.getString(addressIdx).orEmpty(),
                        body = it.getString(bodyIdx).orEmpty(),
                        receivedAt = it.getLong(dateIdx)
                    )
                )
            }
        }

        return result
    }

    private fun cleanNumber(number: String): String =
        com.openshield.data.util.PhoneNumberNormalizer.normalize(number)

    fun hashNumber(number: String): String =
        com.openshield.data.util.PhoneNumberNormalizer.sha256(number)
}
