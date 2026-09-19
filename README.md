# 🛡️ OpenShield — SMS Spam Engelleyici

<p align="center">
  <img src="docs/banner.png" alt="OpenShield Banner" width="600"/>
</p>

<p align="center">
  <a href="https://github.com/RRasne/OpenShield/releases"><img src="https://img.shields.io/github/v/release/RRasne/OpenShield?style=flat-square&color=2563EB" alt="Release"/></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-green?style=flat-square" alt="License"/></a>
  <img src="https://img.shields.io/badge/android-8.0%2B-brightgreen?style=flat-square" alt="Android 8+"/>
  <img src="https://img.shields.io/badge/tespit-cihaz%20üzerinde-blue?style=flat-square" alt="On-device"/>
  <img src="https://img.shields.io/badge/tracking-sıfır-red?style=flat-square" alt="No tracking"/>
  <img src="https://img.shields.io/badge/reklam-yok-orange?style=flat-square" alt="No ads"/>
</p>

> Android için gizlilik öncelikli, açık kaynak SMS spam engelleyici.  
> Spam tespiti tamamen cihazınızda gerçekleşir. SMS içeriği hiçbir zaman cihazınızdan çıkmaz.

---

## ✨ Özellikler

- 🔒 **Çevrimdışı Tespit** — Spam analizi tamamen cihazda, internet gerektirmez
- 🧠 **Katmanlı Analiz** — Numara kontrolü + Türkçe/İngilizce kural motoru + ağırlıklı skor
- 🇹🇷 **Türkçe Optimize** — Kumar siteleri, sahte bağış, siyasi toplu mesaj, yatırım dolandırıcılığı kalıpları
- 🟡 **Şüpheli Mesaj Yönetimi** — Emin olunmayan mesajlar sessizce bekletilir; siz karar verirsiniz
- 👥 **Topluluk Listesi** — İsteğe bağlı, anonim oylama sistemi (SHA-256 hash)
- 📋 **Kara / Beyaz Liste** — Kendi filtrelerinizi tam kontrol edin
- 🔕 **Sıfır Bildirim Gürültüsü** — Spam gelince ses/titreşim yok, sadece sessiz kayıt
- 🚫 **Sıfır Analitik** — Reklam yok, izleme kodu yok, üçüncü taraf SDK yok
- ⚡ **Hafif** — Pil ve RAM dostu, arka planda minimal etki

---

## 📱 Nasıl Çalışır?

```
Gelen SMS
    │
    ├─[1]─ Numara Kontrolü
    │       Kara liste · Beyaz liste · Topluluk spam listesi (hash bazlı)
    │
    ├─[2]─ İçerik Analizi (RuleEngine)
    │       Türkçe/İngilizce anahtar kelimeler
    │       URL · IBAN · Kumar markası · Toplu SMS kodu
    │       Combo kurallar: IBAN+dini söylem, havale+link, deneme+bonus vb.
    │
    └─[3]─ Ağırlıklı Skor
                numara × 0.40 + içerik × 0.35 + ML × 0.25
                │
                ├─ > 0.60    →  🔴 SPAM     — sessiz log, geçmişe kaydet
                ├─ 0.40–0.60 →  🟡 ŞÜPHELİ — beklet, uygulamada sor
                └─ < 0.40    →  🟢 TEMİZ    — dokunma
```

### SPAM Akışı

Spam tespit edildiğinde bildirim çıkmaz, ses/titreşim olmaz. Mesaj sessizce engelleme geçmişine kaydedilir. Topluluk veri paylaşımı açıksa ve Wi-Fi bağlıysa anonim hash raporu gönderilir; Wi-Fi yoksa kuyrukta bekler, bağlanınca gönderilir.

### Şüpheli Mesaj Akışı

Şüpheli sınıflandırılan mesajda hiçbir bildirim gösterilmez. Gönderici numarası, skor ve tetiklenen kurallar `pending_review` tablosuna kaydedilir — SMS içeriği **asla** kaydedilmez. Bir sonraki uygulama açılışında size "Spam mı, değil mi?" diye sorulur:

- **Spam** → kara listeye eklenir, geçmişe yazılır, topluluk'a spam oyu gönderilir
- **Değil** → kayıt silinir, topluluk'a "spam değil" oyu gönderilir (oran dengelenir)

---

## 🔐 Gizlilik

| Konu | Durum |
|---|---|
| SMS içeriği kaydedilir mi? | ❌ Asla |
| SMS içeriği sunucuya gider mi? | ❌ Asla |
| Numara düz metin saklanır mı? | Kara/beyaz liste: ✅ kullanıcı girişi · Topluluk raporları: ❌ SHA-256 hash |
| İnternet kullanılır mı? | Yalnızca topluluk onayı varsa, yalnızca Wi-Fi'de |
| Mobil veri kullanılır mı? | ❌ Asla |
| Reklam / analitik / üçüncü taraf SDK | ❌ Hiçbiri |
| Veri satışı | ❌ Asla |

### İnternet İzni Hakkında Dürüst Açıklama

OpenShield `INTERNET` iznine sahiptir. Bu izin **yalnızca şu iki durumda** kullanılır:

1. **Topluluk spam listesini çekmek** — Wi-Fi'ye bağlandığında, son sync'ten 6+ saat geçmişse ve onay verildiyse
2. **Anonim oy göndermek** — SPAM veya "değil" kararı verildiğinde, onay verildiyse, Wi-Fi'de

Her iki işlem de **onay olmadan hiçbir zaman gerçekleşmez.** Kaynak kodu açık olduğundan bunu kendiniz doğrulayabilirsiniz.

### Topluluk Bildirimi — Ne Gönderilir?

| Gönderilen | Gönderilmeyen |
|---|---|
| Numaranın SHA-256 hash'i | Numaranın kendisi |
| Tetiklenen kural isimleri (örn. `GAMBLING_BRAND`) | SMS içeriği |
| Oy türü: `spam` veya `not_spam` | Cihaz kimliği |
| — | Konum veya IP |

Hash tek yönlüdür — orijinal numara geri elde **edilemez.**

### Topluluk Oylama Sistemi

Her numara için `spam_votes` ve `not_spam_votes` sayılır. Karar kuralı:

- `spam_votes / toplam_oy > %50` → topluluk listesine girer
- `not_spam_votes / toplam_oy > %50` → listeden çıkar / girmez
- Minimum oy şartı yoktur — 1 oy bile etkilidir
- Yanlış kayıtlar admin tarafından manuel olarak düzeltilebilir

---

## 🚀 Kurulum

### Play Store
*(Yakında)*

### Manuel APK
[Releases](https://github.com/RRasne/OpenShield/releases) sayfasından en son APK'yı indirin.

### Kaynak Koddan Derleme
```bash
git clone https://github.com/RRasne/OpenShield.git
cd OpenShield
./gradlew assembleRelease
```

---

## 🏗️ Mimari

```
app/src/main/java/com/openshield/
├── data/
│   ├── db/
│   │   └── SpamDatabase.kt          # Room v3 — 5 tablo, migration'lı
│   └── repository/
│       ├── SpamRepository.kt        # Kara/beyaz liste, log, pending_review
│       ├── CommunityRepository.kt   # Topluluk raporu gönderme + sync
│       └── ConsentManager.kt        # Onay durumu ve sync zamanı
├── detection/
│   ├── engine/
│   │   └── SpamDetectionEngine.kt   # 3 katman skor birleştirme
│   └── rules/
│       └── RuleEngine.kt            # TR/EN kural seti, combo kurallar
├── service/
│   └── SmsReceiver.kt               # SMS alımı, analiz, log/pending_review
├── worker/
│   └── WifiSyncManager.kt           # NetworkCallback — Wi-Fi'de flush + sync
├── ui/
│   ├── MainActivity.kt              # 4 sekme: Ana Sayfa, Kara/Beyaz Liste, Geçmiş
│   ├── MainViewModel.kt             # StateFlow tabanlı UI state
│   └── SuspiciousReviewDialog.kt    # Şüpheli mesaj karar dialogu
└── OpenShieldApp.kt                 # WifiSyncManager başlatma
```

### DB Tabloları (v3)

| Tablo | İçerik |
|---|---|
| `spam_numbers` | Kullanıcı kara listesi (düz numara) + topluluk hash'leri |
| `whitelist` | Kullanıcı beyaz listesi (düz numara) |
| `blocked_log` | Engelleme geçmişi (hash, skor, sebep, zaman) |
| `pending_review` | Şüpheli mesajlar — kullanıcı kararı bekliyor |
| `pending_reports` | Wi-Fi yokken biriken topluluk raporları — kuyruğa alınır |

**Teknolojiler:** Jetpack Compose · Room · Hilt · Kotlin Coroutines · Cloudflare Workers + KV

---

## 👥 Topluluk Spam Listesi

Topluluk sistemi Cloudflare Workers + KV üzerinde çalışır. Kaynak kodu [`cloudflare/worker.js`](cloudflare/worker.js) dosyasında açıktır.

**Nasıl çalışır:**

1. Kullanıcı onayı verirse ve Wi-Fi bağlıysa SPAM/DEĞIL kararları anonim hash olarak gönderilir
2. Wi-Fi bağlantısı yokken kararlar cihazda kuyrukta bekler, bağlanınca otomatik gönderilir
3. Her 6 saatte bir `community-list` endpoint'inden güncel liste çekilir
4. Başarısız gönderimler exponential backoff ile tekrar denenir (30s → 1s → 2dk → 4dk → 8dk, max 5 deneme)

**Oylama hesabı:**
- Her numara için `spam_votes` ve `not_spam_votes` ayrı tutulur
- `spam_votes / toplam > %50` ise o numara topluluk listesinde yer alır
- Oran `%50`'nin altına düşerse listeden çıkar

---

## 🤝 Katkı

Her türlü katkıya açığız:

- 🇹🇷 Türkçe spam anahtar kelime listesi genişletme
- 🌍 Başka dil desteği
- 🐛 Hata bildirimi
- 📊 Anonimize edilmiş spam SMS dataset katkısı

Lütfen önce [AGENTS.md](AGENTS.md) dosyasını okuyun, ardından PR açın.

---

## 📄 Lisans

[GNU General Public License v3.0](LICENSE)

---

<p align="center">
  Gizliliğiniz bir özellik değil, bir haktır.
</p>
