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

    companion object {
        // Tanınan güvenilir kurumsal başlıklar (Bankalar, Operatörler, Kargo, Resmi Kurumlar)
        private val TRUSTED_INSTITUTIONAL_SENDERS = setOf(
            // Bankalar & Finans
            "GARANTI", "GARANTIBBVA", "ISBANK", "ISBANKASI", "AKBANK", "YAPIKREDI", "YKREDI",
            "ZIRAAT", "ZIRAATBANK", "HALKBANK", "VAKIFBANK", "DENIZBANK", "QNB", "QNBFB",
            "FINANSBANK", "ING", "INGBANK", "TEB", "KUVEYTTURK", "TURKIYEFINANS", "ALBARAKA",
            "ENPARA", "PAPARA", "HAYHAY", "TROY", "SEKERBANK", "ODEABANK", "FIBABANKA",
            // Operatörler & İletişim
            "TURKCELL", "TCELL", "VODAFONE", "TURKTELEKOM", "TTNET", "NETGSM", "MILLENICOM", "TURKNET",
            // Kargo & Lojistik
            "YURTICIKRG", "YURTICI", "ARASKARGO", "ARAS", "MNGKARGO", "MNG", "SURATKARGO", "SURAT",
            "PTT", "PTTKARGO", "KOLAYGELSIN", "HEPSIJET", "TRENDYOLEXP", "KARGOIST",
            // E-Ticaret & Hizmet
            "TRENDYOL", "HEPSIBURADA", "AMAZON", "GETIR", "YEMEKSEPETI", "MIGROS", "SOK", "BIM", "A101",
            // Kamu / Resmi
            "E-DEVLET", "EDEVLET", "GIB", "AFAD", "SAGLIKBAK", "SAGLIKBK", "BELEDIYE", "UYAP"
        )
    }

    suspend fun analyze(sender: String, body: String): SpamResult = withContext(Dispatchers.IO) {
        val cleanSender = sender.trim().uppercase()

        // 1. Beyaz listede ise direkt temiz
        if (repository.isWhitelisted(sender) || repository.isWhitelisted(cleanSender)) {
            return@withContext SpamResult(Classification.CLEAN, 0f, "Beyaz listede")
        }

        // 1b. Bilinen kurumsal / banka göndericisi kontrolü
        val isTrustedInstitution = TRUSTED_INSTITUTIONAL_SENDERS.any { cleanSender.contains(it) }
        val isAlphanumericSender = cleanSender.length >= 3 && cleanSender.all { it.isLetter() || it == ' ' || it == '-' }

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

        // Kurumsal gönderici ise ve kumar/dolandırıcılık gibi bariz bir combo tetiklenmediyse temiz kabul et
        val hasBlatantSpam = ruleResult.triggeredRules.any { it.contains("GAMBLING") || it.contains("COMBO:") }
        if (isTrustedInstitution && !hasBlatantSpam) {
            return@withContext SpamResult(Classification.CLEAN, 0.05f, "Güvenilir Kurumsal Gönderici ($cleanSender)")
        }

        // Alfanümerik (başlıklı) SMS için şüphe skorunu düşür (BTK onaylı başlıklı SMS'ler)
        var finalScore = ruleResult.score
        if (isAlphanumericSender && !hasBlatantSpam && finalScore < 0.60f) {
            finalScore = (finalScore - 0.15f).coerceAtLeast(0f)
        }

        val classification = when {
            finalScore >= 0.60f -> Classification.SPAM
            finalScore >= 0.45f -> Classification.SUSPICIOUS
            else -> Classification.CLEAN
        }

        SpamResult(
            classification = classification,
            score = finalScore,
            reason = if (rulesText.isEmpty()) "Temiz" else rulesText
        )
    }
}
