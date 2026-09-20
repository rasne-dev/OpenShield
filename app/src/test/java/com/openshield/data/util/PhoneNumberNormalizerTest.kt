package com.openshield.data.util

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneNumberNormalizerTest {

    @Test
    fun normalize_formatsCorrectly() {
        assertEquals("5301234567", PhoneNumberNormalizer.normalize("+905301234567"))
        assertEquals("5301234567", PhoneNumberNormalizer.normalize("05301234567"))
        assertEquals("5301234567", PhoneNumberNormalizer.normalize("5301234567"))
        assertEquals("5301234567", PhoneNumberNormalizer.normalize("00905301234567"))
        assertEquals("5301234567", PhoneNumberNormalizer.normalize("0 (530) 123 45 67"))
        assertEquals("5301234567", PhoneNumberNormalizer.normalize("+90 530 123-45-67"))
        assertEquals("8501234567", PhoneNumberNormalizer.normalize("0850 123 45 67"))
        assertEquals("TRENDYOL", PhoneNumberNormalizer.normalize("Trendyol"))
    }

    @Test
    fun sha256_hashesConsistently() {
        val hash1 = PhoneNumberNormalizer.sha256("+905301234567")
        val hash2 = PhoneNumberNormalizer.sha256("0530 123 45 67")
        assertEquals(hash1, hash2)
        assertEquals(64, hash1.length)
        assertEquals("", PhoneNumberNormalizer.sha256(""))
        assertEquals("", PhoneNumberNormalizer.sha256("   "))
    }
}
