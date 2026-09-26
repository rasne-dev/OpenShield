package com.openshield.data.util

import java.security.MessageDigest

/**
 * Telefon numaralarını standart formata dönüştüren ve tek tip SHA-256 hash üreten yardımcı sınıf.
 *
 * Normalizasyon Kuralları (Örn. TR):
 *   +90 555 123 45 67 -> 5551234567
 *   00905551234567    -> 5551234567
 *   0555 123 4567     -> 5551234567
 *   555-123-45-67     -> 5551234567
 *
 * Uluslararası numaralar için genel kural:
 *   Özel karakterler temizlenir, baştaki '+' veya '00' ülke kodu tutulur.
 */
object PhoneNumberNormalizer {

    /**
     * Numarayı arama ve karşılaştırma için temizler ve standartlaştırır.
     */
    fun normalize(rawNumber: String): String {
        val trimmed = rawNumber.trim()
        if (trimmed.isEmpty()) return ""

        // Harfli göndericiler (Örn: "GARANTI", "B018", "Trendyol") doğrudan temizlenip uppercase döner
        if (trimmed.any { it.isLetter() }) {
            return trimmed.uppercase()
        }

        // Yalnızca rakamları ve '+' işaretini koru
        val digitsOnly = trimmed.replace(Regex("[^0-9+]"), "")

        // TR numaraları normalizasyonu:
        // +905XXXXXXXXX (12 hane) -> 5XXXXXXXXX (10 hane)
        // 00905XXXXXXXXX (13 hane) -> 5XXXXXXXXX (10 hane)
        // 05XXXXXXXXX (11 hane) -> 5XXXXXXXXX (10 hane)
        return when {
            digitsOnly.startsWith("+90") && digitsOnly.length == 13 -> digitsOnly.substring(3)
            digitsOnly.startsWith("0090") && digitsOnly.length == 14 -> digitsOnly.substring(4)
            digitsOnly.startsWith("90") && digitsOnly.length == 12 -> digitsOnly.substring(2)
            digitsOnly.startsWith("0") && digitsOnly.length == 11 -> digitsOnly.substring(1)
            else -> digitsOnly
        }
    }

    /**
     * Normalize edilmiş numaranın SHA-256 hash'ini hesaplar.
     */
    fun sha256(rawNumber: String): String {
        val normalized = normalize(rawNumber)
        if (normalized.isBlank()) return ""
        return MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Numarayı kullanıcı arayüzünde okunabilir ve estetik formata dönüştürür.
     * Örn: 5551234567 -> 0 (555) 123 45 67
     *      8501234567 -> 0 (850) 123 45 67
     */
    fun formatForDisplay(rawNumber: String): String {
        val trimmed = rawNumber.trim()
        if (trimmed.any { it.isLetter() }) return trimmed
        val normalized = normalize(trimmed)
        if (normalized.length == 10 && normalized.all { it.isDigit() }) {
            val area = normalized.substring(0, 3)
            val p1 = normalized.substring(3, 6)
            val p2 = normalized.substring(6, 8)
            val p3 = normalized.substring(8, 10)
            return "0 ($area) $p1 $p2 $p3"
        }
        return rawNumber
    }
}
