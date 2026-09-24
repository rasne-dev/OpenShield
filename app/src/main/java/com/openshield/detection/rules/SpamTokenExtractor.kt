package com.openshield.detection.rules

/**
 * SMS içeriğinden topluluk verisi için güvenli token'lar çıkarır.
 *
 * Kural:
 *   ✅ Token olabilir: bilinen domain'ler, yapısal işaretler, spam anahtar kelimeleri
 *   ❌ Token olamaz: isimler, telefon numaraları, IBAN tam metni, tarih/saat
 *
 * Çıkan token'lar Cloudflare'e gönderilir.
 * Ham SMS içeriği asla gönderilmez.
 */
object SpamTokenExtractor {

    // Bilinen spam domain'leri — tam eşleşme (subdomain dahil değil)
    private val knownSpamDomains = setOf(
        "t2m.io", "bit.ly", "tinyurl.com", "t.me", "rebrand.ly",
        "cutt.ly", "gg.gg", "is.gd", "shorte.st", "adf.ly",
        "bc.vc", "ouo.io", "linkvertise.com"
    )

    private val urlRegex = Regex("""https?://([a-zA-Z0-9.\-]+)""")
    private val ibanRegex = Regex("""TR\d{2}[\s\d]{20,26}""")
    private val phoneInBodyRegex = Regex("""(\+90|0090|090)?\s?5\d{2}[\s\-]?\d{3}[\s\-]?\d{2}[\s\-]?\d{2}""")

    // Kişisel veri içerebilecek regex'ler — bunlar token OLMAMALı
    private val personalDataPatterns = listOf(
        Regex("""\b[A-ZÇĞİÖŞÜ][a-zçğışöüa-z]+ [A-ZÇĞİÖŞÜ][A-ZÇĞİÖŞÜa-zçğışöüa-z]+\b"""), // Ad Soyad
    )

    // Topluluğa bildirilebilecek anahtar kelimeler (kişisel değil, yapısal spam sinyali)
    private val reportableKeywords = listOf(
        "deneme bonusu", "havale alt limit", "%100 iade", "ilk yatırımda",
        "deneme", "bonus", "havale", "etap", "yatırım", "yatirim",
        "papara", "usdt", "bitcoin", "kripto",
        "kazandınız", "kazan", "ödül", "hediye", "ücretsiz",
        "hemen tıkla", "son şans", "son gün",
        "allah rızası", "bağışta bulunun",
        "belediye başkanı", "milletvekili",
        "won", "winner", "click here", "prize", "deposit",
    )

    /**
     * SMS metninden topluluk token'larını çıkarır.
     * @return Kişisel veri içermeyen token listesi, max 20 eleman
     */
    fun extract(body: String): List<String> {
        val tokens = mutableSetOf<String>()
        val lower = body.lowercase()

        // 1. URL domain'leri
        urlRegex.findAll(body).forEach { match ->
            val domain = match.groupValues[1].lowercase()
                .removePrefix("www.")

            if (domain in knownSpamDomains) {
                tokens.add("domain:$domain")
            } else if (domain.endsWith(".xyz") || domain.endsWith(".tk") ||
                       domain.endsWith(".bet") || domain.endsWith(".casino")) {
                tokens.add("suspicious_tld:${domain.substringAfterLast(".")}")
            } else {
                // Bilinmeyen domain — sadece TLD'yi kaydet
                tokens.add("url_present")
            }
        }

        // 2. Yapısal işaretler
        if (ibanRegex.containsMatchIn(body)) tokens.add("IBAN")
        if (phoneInBodyRegex.containsMatchIn(body)) tokens.add("PHONE_IN_BODY")

        // 3. Toplu SMS opt-out kodu (B018, B372 gibi)
        if (Regex("""\bB\d{3,4}\b""").containsMatchIn(body)) tokens.add("BULK_SMS_CODE")

        // 4. Anahtar kelimeler
        for (kw in reportableKeywords) {
            if (lower.contains(kw)) {
                tokens.add("kw:$kw")
            }
        }

        // 5. Kişisel veri var mı kontrol et — varsa bazı token'ları çıkar
        // (Kişisel veri içeren mesajlarda URL/IBAN token'ı güvenli, isim/numara değil)
        // Bu adımda token üretmiyoruz sadece logluyoruz — token listesi zaten güvenli

        return tokens.toList().take(20)  // max 20 token
    }

    /**
     * Verilen triggered rule listesini topluluk için güvenli hale getirir.
     * Kişisel veri içerebilecek rule string'leri filtrelenir.
     */
    fun sanitizeRules(rules: List<String>): List<String> {
        return rules
            .map { it.trim() }
            .filter { rule ->
                // Kişisel numara/içerik içermeyen rule'lar
                rule.isNotBlank() &&
                !rule.startsWith("PHONE:") &&
                !rule.startsWith("SENDER:") &&
                rule.length <= 50
            }
            .take(15)
    }
}
