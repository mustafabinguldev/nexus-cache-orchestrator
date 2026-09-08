# Canlı regresyon testleri

Bu testler gerçek Redis/MongoDB servisleri ve paketlenmiş uygulama üzerinde çalışır. Yalnızca test için ayrılmış localhost portlarını kullanın; üretim veritabanlarına yönlendirmeyin.

## Sonuçlar — 8 Eylül 2026

İlk incelemede dört hata yeniden üretilmişti. Düzeltmeden sonraki kontroller:

| Kontrol | Sonuç |
| --- | --- |
| Maven verify / boot JAR paketleme | Başarılı |
| RuntimeAudit | 27 kontrol başarılı |
| WebSmokeAudit | 8 HTTP kontrolü başarılı |
| Anahtarsız varsayılan politika | İmzasız mesaj reddedildi |
| Açık geçiş seçeneği | İmzasız mesaj yalnızca opt-in ile kabul edildi |

RuntimeAudit; L1 açık/kapalı okuma ve yazmayı, cache süresi dolduktan sonra flush işlemini, aynı anahtarın kilitlerini, asenkron işlemden önce ACK verilmemesini, başarısız mesajın yeniden işlenmesini, imza ve nonce kontrollerini doğrular. L1 açıkken ve kapalıyken ayrı ayrı 50 eşzamanlı INCREMENT_DATA isteği, Redis ve MongoDB'de tam 50 sonucunu verir. Mongo okuma hatasında kayıt varsayılan değerlerle ezilmez; tamamlanan handler'ın kalıcı yazması yeniden denendiğinde artırma iki kez uygulanmaz. GET_DATA, RANKING ve RANK_FINDER Pub/Sub yanıtları ile REMOVE_DATA da kontrol edilir.

WebSmokeAudit boot JAR'ını 127.0.0.1:18088 üzerinde geçici rastgele parola ile başlatır. Health, HTML, anonim erişimin reddi, yanlış/doğru parola, oturumla stats, logout ve oturumun geçersizleşmesi kontrol edilir. Uygulama test sonunda kapatılır ve geçici config.json silinir.

## Ortam ve kapsam

- Windows 11, Temurin Java 25.0.3, kaynak/hedef seviyesi 21; gerçek Java 21 runtime testi yapılmadı.
- Maven 3.9.16; taşınabilir yerel kurulum `target/tools/apache-maven-3.9.16`. Resmî Apache arşivinin SHA-512 doğrulaması yapıldı. Sistem PATH'i değiştirilmedi.
- Ayrı `nexus-audit-redis` ve `nexus-audit-mongo` konteynerleri: localhost 16379 ve 17017. Mevcut servisler/veriler testlerde kullanılmaz.
- Maven Surefire için test sınıfı yoktur; bu Java testleri Maven'den sonra ayrıca çalıştırılır.
- Mongo hata testleri gerçek bağlantıyı kesmek yerine uygulamanın circuit breaker'ını kontrollü açar. Flush zamanlayıcısı ve yeniden teslimin enqueue yolu reflection ile tetiklenir; 15/30 saniyelik periyotların kendisi ölçülmez.
- Çok düğümlü eşzamanlılık, zorla süreç kapatma sonrası tam bir kez işleme, uzun süreli yük ve tarayıcı JavaScript testleri kapsam dışıdır. Mevcut teslim garantisi at least once olarak kalır.
- Addon'un kendi bağımsız thread/executor işleri otomatik izlenmez; `processTask` / `processMongoTask` kullanılması ve hataların dışarı aktarılması gerekir.

## Yeniden çalıştırma (PowerShell)

Docker Desktop açık olmalı. İlk kez kuruyorsanız yalnızca test konteynerlerini oluşturun:

```powershell
docker run -d --name nexus-audit-redis -p 127.0.0.1:16379:6379 redis:7-alpine
docker run -d --name nexus-audit-mongo -p 127.0.0.1:17017:27017 mongo:7
```

Ardından proje kökünden:

```powershell
./diagnostics/run.ps1
```

Windows script çalıştırmayı engelliyorsa, sistem ilkesini kalıcı değiştirmeden yalnızca test alt süreci için:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./diagnostics/run.ps1
```

Script sistemdeki Maven'i veya mevcut taşınabilir kurulumu kullanır, derlemeyi doğrular, testleri çalıştırır ve yalnızca test konteynerlerini sonunda durdurur. İmza anahtarı test için rastgele oluşturulur; mevcut ortam değişkenleri `finally` içinde geri yüklenir. Her başarısız kontrol sıfırdan farklı çıkış kodu üretir.

`mvn clean`, target içindeki taşınabilir Maven'i ve logları da sileceğinden bu kurulumda kullanılmamalıdır. Testler benzersiz `audit_...` veritabanları oluşturur; test konteynerlerindeki bu veriler korunur.

## Loglar ve Git

- `target/build-fix.log`
- `target/audit/runtime-regression.log`
- `target/audit/web-regression.log`
- `target/audit/web/application.log`
- `target/audit/signature-default.log`
- `target/audit/signature-compatibility.log`

`diagnostics/` kaynakları ve bu README paylaşılabilir. Ham logları, config.json dosyalarını, gerçek anahtarları ve veritabanı verilerini commit etmeyin. `target/` zaten .gitignore kapsamındadır.

## Geçiş ayarı

Core ve istemcilerde aynı `NEXUS_SIGNING_KEY` tanımlanmalıdır. Anahtarsız kurulum artık gelen mesajları varsayılan olarak reddeder. `NEXUS_ALLOW_UNSIGNED_MESSAGES=true` yalnızca izole geliştirme/geçiş ortamları için açık bir uyumluluk seçeneğidir; üretimde kullanılmamalıdır.
