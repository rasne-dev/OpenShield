package com.openshield.detection.engine

import com.openshield.data.repository.SpamRepository
import com.openshield.detection.rules.RuleEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class Classification { SPAM, SUSPICIOUS, CLEAN }

data class SpamResult(
    val classification: Classification,
    val score: Float,
    val reason: String
)

class SpamDetectionEngine(private val repository: SpamRepository) {

    private val ruleEngine = RuleEngine()

    suspend fun analyze(sender: String, body: String): SpamResult = withContext(Dispatchers.IO) {

        // 1. Beyaz listede ise direkt temiz
        if (repository.isWhitelisted(sender)) {
            return@withContext SpamResult(Classification.CLEAN, 0f, "Beyaz listede")
        }

        // 2. Kara listede ise direkt spam
        if (repository.isSpam(sender)) {
            return@withContext SpamResult(Classification.SPAM, 1f, "Kara listede")
        }

        // 3. Topluluk spam listesinde ise (hash bazlı)
        if (repository.isCommunitySpam(sender)) {
            return@withContext SpamResult(Classification.SPAM, 0.95f, "Topluluk spam listesi")
        }

        // 4. Kural motoru ile içerik analizi
        val ruleResult = ruleEngine.analyze(body)
        val rulesText = ruleResult.triggeredRules.joinToString(", ")

        val classification = when {
            ruleResult.score >= 0.60f -> Classification.SPAM
            ruleResult.score >= 0.35f -> Classification.SUSPICIOUS
            else -> Classification.CLEAN
        }

        SpamResult(
            classification = classification,
            score = ruleResult.score,
            reason = if (rulesText.isEmpty()) "Temiz" else rulesText
        )
    }
}
