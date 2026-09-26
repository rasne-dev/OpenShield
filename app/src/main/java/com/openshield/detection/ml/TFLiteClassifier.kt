package com.openshield.detection.ml

import javax.inject.Inject
import javax.inject.Singleton

/**
 * TensorFlow Lite spam sınıflandırıcısı.
 *
 * Şu an stub implementasyon — gerçek TFLite modeli entegre edilene kadar
 * RuleEngine skoruna dayalı basit bir tahmin döner.
 *
 * Gerçek entegrasyon için:
 *   1. assets/spam_model.tflite dosyasını ekle
 *   2. org.tensorflow:tensorflow-lite bağımlılığını build.gradle'a ekle
 *   3. Bu sınıfın içini gerçek inference kodu ile doldur
 */
@Singleton
class TFLiteClassifier @Inject constructor() {

    /**
     * Mesaj gövdesini analiz edip 0.0–1.0 arası spam skoru döner.
     * Şu an: model yüklenemediğinde 0.0 döner (nötr — RuleEngine belirleyici olur).
     */
    @Suppress("UNUSED_PARAMETER")
    fun classify(body: String): Float {
        // TODO: Gerçek TFLite modeli entegre edildiğinde burayı doldur
        return 0.0f
    }

    fun isModelLoaded(): Boolean = false
}
