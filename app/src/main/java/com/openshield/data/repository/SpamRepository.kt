package com.openshield.data.repository

import com.openshield.data.db.*
import kotlinx.coroutines.flow.Flow

class SpamRepository(
    private val db: SpamDatabase,
    private val communityRepository: CommunityRepository? = null,
    private val isWifiConnected: () -> Boolean = { false }
) {

    // ─── Spam Numaraları ──────────────────────────────────────────────────────

    val allSpamNumbers: Flow<List<SpamNumberEntity>> = db.spamNumberDao().getAllFlow()

    suspend fun isSpam(number: String): Boolean =
        db.spamNumberDao().findByNumber(cleanNumber(number)) != null

    suspend fun isCommunitySpam(number: String): Boolean {
        val hash = com.openshield.data.util.PhoneNumberNormalizer.sha256(number)
        val entity = db.spamNumberDao().findByNumber(hash)
        return entity != null && !entity.isUserAdded
    }

    suspend fun addSpam(number: String, label: String = "") {
        db.spamNumberDao().insert(SpamNumberEntity(number = cleanNumber(number), label = label))
    }

    suspend fun removeSpam(number: String) =
        db.spamNumberDao().deleteByNumber(cleanNumber(number))

    suspend fun spamCount(): Int = db.spamNumberDao().count()

    suspend fun clearAllData() {
        db.spamNumberDao().deleteAllUserSpam()
        db.whitelistDao().clearAll()
        db.blockLogDao().clearAll()
    }

    // ─── Beyaz Liste ──────────────────────────────────────────────────────────

    val allWhitelist: Flow<List<WhitelistEntity>> = db.whitelistDao().getAllFlow()

    suspend fun isWhitelisted(number: String): Boolean =
        db.whitelistDao().findByNumber(cleanNumber(number)) != null

    suspend fun addWhitelist(number: String, name: String = "") {
        db.whitelistDao().insert(WhitelistEntity(number = cleanNumber(number), name = name))
    }

    suspend fun removeWhitelist(number: String) =
        db.whitelistDao().deleteByNumber(cleanNumber(number))

    // ─── Engelleme Geçmişi ────────────────────────────────────────────────────

    val recentBlocked: Flow<List<BlockedLogEntity>> = db.blockLogDao().getRecentFlow()

    suspend fun logBlocked(sender: String, reason: String, score: Float) {
        db.blockLogDao().insert(BlockedLogEntity(sender = sender, reason = reason, score = score))
    }

    suspend fun totalBlocked(): Int = db.blockLogDao().totalCount()

    suspend fun clearHistory() = db.blockLogDao().clearAll()

    // ─── Bekleyen İncelemeler ─────────────────────────────────────────────────

    suspend fun addPendingReview(sender: String, reason: String, score: Float) {
        db.pendingReviewDao().insert(
            PendingReviewEntity(sender = sender, reason = reason, score = score)
        )
    }

    suspend fun getPendingReviews(): List<PendingReviewEntity> =
        db.pendingReviewDao().getAll()

    suspend fun pendingReviewCount(): Int = db.pendingReviewDao().count()

    /**
     * Kullanıcı kararı:
     * isSpam = true  → kara listeye ekle + log + topluluk'a spam oyu
     * isSpam = false → topluluk'a not_spam oyu gönder (yerel bir şey yapma)
     */
    suspend fun resolvePendingReview(entity: PendingReviewEntity, isSpam: Boolean) {
        if (isSpam) {
            addSpam(entity.sender, label = "Şüpheli onaylandı")
            logBlocked(sender = entity.sender, reason = entity.reason, score = entity.score)
            communityRepository?.reportSpam(
                number          = entity.sender,
                triggeredRules  = entity.reason.split(", "),
                isWifiConnected = isWifiConnected()
            )
        } else {
            // Spam değil — topluluk'a negatif oy gönder
            communityRepository?.reportNotSpam(
                number          = entity.sender,
                isWifiConnected = isWifiConnected()
            )
        }
        db.pendingReviewDao().deleteById(entity.id)
    }

    // ─── Yardımcı ─────────────────────────────────────────────────────────────

    private fun cleanNumber(number: String) =
        com.openshield.data.util.PhoneNumberNormalizer.normalize(number)
}
