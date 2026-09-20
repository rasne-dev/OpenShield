package com.openshield.detection.rules

import javax.inject.Inject
import javax.inject.Singleton

data class RuleResult(
    val score: Float,
    val triggeredRules: List<String>
)

@Singleton
class RuleEngine @Inject constructor() {

    // ─── Beyaz liste kalıpları — erken çıkış ──────────────────────────────────

    // Banka OTP / doğrulama kodu
    private val otpPattern = Regex(
        """(\d{4,8})\s*(kod|code|otp|şifre|doğrulama|onay|pin)""",
        RegexOption.IGNORE_CASE
    )
    private val bankVerifyPattern = Regex(
        """(bankanız|hesabınız|kartınız|banka).{0,60}(kod|şifre|onay|doğrulama)""",
        RegexOption.IGNORE_CASE
    )

    // Fatura / abonelik bildirimi — meşru servisler
    private val invoicePattern = Regex(
        """(fatura(nız)?|son ödeme|aboneliğiniz|aboneliginiz).{0,80}(tl|tarih|ödeme|odeme)""",
        RegexOption.IGNORE_CASE
    )

    // Kargo / teslimat bildirimi
    private val cargoPattern = Regex(
        """(kargo(nuz)?|paket(iniz)?|teslimat|teslim|sipariş(iniz)?).{0,60}(teslim|takip|yola çıktı|bugün|bugun)""",
        RegexOption.IGNORE_CASE
    )

    // Banka bonus / kart bildirimi — meşru (kart numarası var, bonus yüklendi gibi)
    private val bankBonusPattern = Regex(
        """(kart(ınız|iniz)|biten kart).{0,80}(bonus(unuz)?|puan(ınız)?|yüklen|yuklen)""",
        RegexOption.IGNORE_CASE
    )

    // Sigorta / vade hatırlatma — şüpheli ama spam değil
    private val insuranceReminderPattern = Regex(
        """(kasko|sigorta|poliçe|police|vade).{0,80}(gün kaldı|gun kaldi|bitis|bitiş|hatırlatma|hatirlatma)""",
        RegexOption.IGNORE_CASE
    )

    // ─── Spam kalıpları ────────────────────────────────────────────────────────

    private val highWeightKeywords = mapOf(
        // Kumar / bahis
        "deneme bonusu" to 0.95f,
        "havale alt limit" to 0.98f,
        "deneme" to 0.55f,
        "bonus" to 0.55f,
        "havale" to 0.70f,
        "etap" to 0.50f,
        "yatırım" to 0.60f,
        "yatirim" to 0.60f,
        "%100 iade" to 0.90f,
        "ilk yatırımda" to 0.85f,
        "ilk yatirimda" to 0.85f,
        "papara" to 0.60f,
        "usdt" to 0.70f,
        "bitcoin" to 0.60f,
        // Kazanç / ödül
        "kazandınız" to 0.90f,
        "kazan" to 0.60f,
        "ödül" to 0.75f,
        "hediye çeki" to 0.80f,
        "çekilişte" to 0.80f,
        "cekiliste" to 0.80f,
        // Sahte bağış
        "allah rızası" to 0.75f,
        "bağışta bulunun" to 0.80f,
        "sma hastası" to 0.70f,
        // Siyasi
        "belediye başkanı" to 0.65f,
        "milletvekili" to 0.60f,
        "seçim" to 0.35f,
        // Sigorta REKLAMI (hatırlatma değil, satış)
        "tamamlayıcı sağlık" to 0.60f,
        "tamamlayici saglik" to 0.60f,
        "sağlık sigortası" to 0.55f,
        "saglik sigortasi" to 0.55f,
        "12 ay taksit" to 0.75f,
        "sıfır vade" to 0.70f,
        "sifir vade" to 0.70f,
        "büyük fırsat" to 0.70f,
        "buyuk firsat" to 0.70f,
        "hemen bilgi" to 0.55f,
        "hemen ara" to 0.55f,
        // İngilizce spam
        "won" to 0.75f,
        "winner" to 0.80f,
        "click here" to 0.85f,
        "prize" to 0.75f,
        "urgent" to 0.60f,
        "deposit" to 0.60f,
    )

    private val urlPatterns = listOf(
        Regex("""https?://\S+"""),
        Regex("""bit\.ly/\S+"""),
        Regex("""tinyurl\.com/\S+"""),
        Regex("""t2m\.io/\S+"""),
        Regex("""t\.me/\S+"""),
        Regex("""sgrtm\.net/\S+"""),   // sigorta reklam linki
        Regex("""\b\w{2,10}\.bet\b"""),
        Regex("""\b\w{2,10}\.casino\b"""),
    )

    // Meşru fatura/bildirim linkleri — URL olsa bile spam sayma
    private val trustedDomains = listOf(
        "milleni.com.tr", "garanti.com.tr", "akbank.com", "isbank.com.tr",
        "yapikredi.com.tr", "ziraatbank.com.tr", "halkbank.com.tr",
        "vakifbank.com.tr", "isbankasi.com.tr", "ptt.gov.tr",
        "turktelekom.com.tr", "turkcell.com.tr", "vodafone.com.tr"
    )

    private val ibanPattern = Regex("""TR\d{2}\s?\d{4}\s?\d{4}\s?\d{4}\s?\d{4}\s?\d{4}\s?\d{2}""")
    private val bulkSmsCodePattern = Regex("""\bB\d{3,4}\b""")
    private val gamblingBrandPattern = Regex(
        """\b(savoy|betist|bets10|betturkey|casinomaxi|mobilbahis|superbetin|betboo|casino|bahis|poker|slot)\b""",
        RegexOption.IGNORE_CASE
    )

    // ─── Ana Skorlama ──────────────────────────────────────────────────────────

    fun score(body: String): Float = analyze(body).score

    fun analyze(body: String): RuleResult {
        val triggered = mutableListOf<String>()
        val lower = body.lowercase()
        var total = 0f

        // ── Beyaz liste kontrolü — erken çıkış ────────────────────────────────

        // OTP / banka doğrulama
        if (otpPattern.containsMatchIn(body) || bankVerifyPattern.containsMatchIn(body)) {
            triggered.add("OTP_WHITELIST")
            return RuleResult(0.05f, triggered)
        }

        // Meşru fatura bildirimi
        if (invoicePattern.containsMatchIn(body)) {
            triggered.add("INVOICE_WHITELIST")
            // Güvenilir domain varsa tamamen temiz
            if (trustedDomains.any { lower.contains(it) }) return RuleResult(0.05f, triggered)
            // Domain tanıdık değilse biraz skor ver ama spam sayma
            return RuleResult(0.20f, triggered)
        }

        // Kargo bildirimi
        if (cargoPattern.containsMatchIn(body)) {
            triggered.add("CARGO_WHITELIST")
            return RuleResult(0.08f, triggered)
        }

        // Banka bonus/kart bildirimi (kart numarası sonu + bonus yüklendi)
        if (bankBonusPattern.containsMatchIn(body)) {
            triggered.add("BANK_BONUS_WHITELIST")
            return RuleResult(0.10f, triggered)
        }

        // Sigorta/kasko HATIRLATMA (plaka var, vade bitişi var) — şüpheli ama düşük skor
        if (insuranceReminderPattern.containsMatchIn(body)) {
            triggered.add("INSURANCE_REMINDER")
            // Reklam içeriyorsa skoru yükselt
            val hasAdKeyword = lower.contains("indirim") || lower.contains("fırsat") ||
                lower.contains("firsat") || lower.contains("taksit") || lower.contains("kampanya")
            val score = if (hasAdKeyword) 0.55f else 0.25f
            return RuleResult(score, triggered)
        }

        // ── Spam skorlaması ────────────────────────────────────────────────────

        // Anahtar kelimeler
        var kwScore = 0f
        for ((kw, w) in highWeightKeywords) {
            if (lower.contains(kw)) {
                kwScore = minOf(kwScore + w * 0.3f, 1f)
                triggered.add("KW:$kw")
            }
        }
        total += kwScore * 0.5f

        // URL kontrolü — güvenilir domain değilse say
        val hasUrl = urlPatterns.any { it.containsMatchIn(body) }
        val hasTrustedDomain = trustedDomains.any { lower.contains(it) }
        if (hasUrl && !hasTrustedDomain) {
            total += 0.30f
            triggered.add("SUSPICIOUS_URL")
        }

        // IBAN
        val hasIban = ibanPattern.containsMatchIn(body)
        if (hasIban) { total += 0.40f; triggered.add("CONTAINS_IBAN") }

        // Kumar markası
        if (gamblingBrandPattern.containsMatchIn(body)) {
            total += 0.50f; triggered.add("GAMBLING_BRAND")
        }

        // Toplu SMS kodu — tek başına düşük skor, kombinasyonla yükselir
        val hasBulkCode = bulkSmsCodePattern.containsMatchIn(body)
        if (hasBulkCode) { total += 0.20f; triggered.add("BULK_SMS_CODE") }

        // ── Combo bonuslar ─────────────────────────────────────────────────────
        if (hasIban && (lower.contains("allah") || lower.contains("bağış") || lower.contains("bagis"))) {
            total += 0.25f; triggered.add("COMBO:IBAN+DINI")
        }
        if (hasIban && (lower.contains("instagram") || lower.contains("twitter") || lower.contains("tiktok"))) {
            total += 0.20f; triggered.add("COMBO:IBAN+SOSYAL")
        }
        if (lower.contains("havale") && hasUrl && !hasTrustedDomain) {
            total += 0.25f; triggered.add("COMBO:HAVALE+URL")
        }
        if (lower.contains("deneme") && lower.contains("bonus")) {
            total += 0.30f; triggered.add("COMBO:DENEME+BONUS")
        }
        // Sigorta REKLAMI: URL + reklam kelimesi + bulk kod
        if (hasUrl && hasBulkCode && (lower.contains("sigorta") || lower.contains("saglik") || lower.contains("sağlık"))) {
            total += 0.25f; triggered.add("COMBO:SIGORTA_REKLAM")
        }

        // Büyük harf
        val upperRatio = body.count { it.isUpperCase() }.toFloat() / body.length.coerceAtLeast(1)
        if (upperRatio > 0.5f) { total += 0.15f; triggered.add("ALL_CAPS") }

        return RuleResult(total.coerceIn(0f, 1f), triggered)
    }

    fun getLastTriggeredRules(): List<String> = emptyList()
}
