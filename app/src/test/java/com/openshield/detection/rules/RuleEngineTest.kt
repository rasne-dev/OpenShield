package com.openshield.detection.rules

import org.junit.Assert.assertTrue
import org.junit.Test

class RuleEngineTest {

    private val engine = RuleEngine()

    @Test
    fun bankBonusMessage_shouldStayClean() {
        val sms = "xxyy ile biten kartinizi 04.11.2026 tarihine kadar acik birakma sozunuze karsilik 600,00TL bonusunuz 1 gun icinde yuklenecektir. Bonus son kullanim: 24.03.2026. B002"

        val result = engine.analyze(sms)

        assertTrue(result.score < 0.35f)
    }

    @Test
    fun invoiceMessageWithTrustedDomain_shouldStayClean() {
        val sms = "Degerli Millenicom'lu, Faturanizi https://m.milleni.com.tr/xxxxxxxxxxx linkine tiklayarak hicbir sifre girmeden ve cagri merkezimizi aramaya gerek kalmadan tum detaylariyla inceleyebilirsiniz. yyyyyy numarali aboneliginize ait 13.03.2026 son odeme tarihli faturaniz 461.67 TL'dir. Faturanizi simdi odemek icin: https://m.milleni.com.tr/r/fatura Odeme yaptiysaniz lutfen bu mesaji dikkate almayiniz. B002"

        val result = engine.analyze(sms)

        assertTrue(result.score < 0.35f)
    }

    @Test
    fun insurancePromotionSpam_shouldBeSpam() {
        val sms = "Ayin son gunu fiyatlar degismeden Tamamlayici Saglik Sigortasi ile sagligini guvenceye al! Hemen bilgi almak icin arayin: 0216 250 76 47 SMSRET:SNET RET 7889 MS:0770015174700010 http://sgrtm.net Bilgi 4442400 B016"

        val result = engine.analyze(sms)

        assertTrue(result.score >= 0.60f)
    }

    @Test
    fun installmentInsuranceSpam_shouldBeSpam() {
        val sms = "Saglik Sigortasinda Buyuk FIRSAT! 12 Ay Taksit ve Sifir Vade Farkiyla butce dostu secenekler Sigortam.net te. Bilgi icin 02162507647 yi ara, teklif icin hemen tikla! http://sgrtm.net/7W2Y4995810UR SMSRET:SNET RET 7889 MS:0770015174700010 Bilgi 4442400 B016"

        val result = engine.analyze(sms)

        assertTrue(result.score >= 0.60f)
    }

    @Test
    fun turkishOtpVerification_shouldBeClean() {
        val sms1 = "Google dogrulama kodunuz: 849201. Kimseyle paylasmayin."
        val result1 = engine.analyze(sms1)
        assertTrue(result1.score <= 0.10f)
        assertTrue(result1.triggeredRules.contains("OTP_WHITELIST"))

        val sms2 = "Trendyol onay kodu: 491028. Bu kodu guvenliginiz icin kimseyle paylasmayiniz."
        val result2 = engine.analyze(sms2)
        assertTrue(result2.score <= 0.10f)
        assertTrue(result2.triggeredRules.contains("OTP_WHITELIST"))

        val sms3 = "Giris sifreniz: 123456"
        val result3 = engine.analyze(sms3)
        assertTrue(result3.score <= 0.10f)
        assertTrue(result3.triggeredRules.contains("OTP_WHITELIST"))
    }

    @Test
    fun cargoNotification_shouldBeClean() {
        val sms = "Kargonuz dagitima cikarilmistir. Bugun teslim edilecektir. Kurye takip: https://kargo.com"
        val result = engine.analyze(sms)
        assertTrue(result.score <= 0.15f)
        assertTrue(result.triggeredRules.contains("CARGO_WHITELIST"))
    }

    @Test
    fun gamblingWithFreespinAndBrand_shouldBeSpam() {
        val sms = "Matbet ile kazanma zamani! 50 Freespin ve cevrimsiz bonus hesabina tanimlandi. Hemen tikla: https://matbet654.bet"
        val result = engine.analyze(sms)
        assertTrue(result.score >= 0.70f)
        assertTrue(result.triggeredRules.any { it.contains("GAMBLING") })
    }
}
