# F2 Pro kontrolcü tanılama (deneysel)

Bu dal, çalışan ScooterLink uygulamasına **salt okunur** kontrolcü tanılama ekranı ekler. `main` değiştirilmez.

## Ne eklendi?

- Bluetooth ile bağlandıktan sonra **Kontrolcüyü analiz et** düğmesi.
- Okuma istekleriyle şu bilgileri sorgulama:
  - Motor kontrolcüsü (DRV) firmware sürümü: `CTRL 0x1A`
  - Kontrolcü seri numarası: `CTRL 0x10` (14 byte)
  - Motor hata kodu: `CTRL 0x1B`
  - Kontrolcü durum bayrakları: `CTRL 0x1D`
  - BLE ve BMS firmware sürümü: `BLE/BMS 0x1A` (yanıt gelmeyebilir)
- Tanılama öncesi normal aralıklarla veri çekme durdurulur, sonunda yeniden başlatılır.
- Bulgular uygulama günlüğüne yazılır; kullanıcı **Logu kopyala** ile dışa aktarabilir.
- **Hiçbir bölge değiştirme, seri numarası yazma veya flash/firmware değiştirme komutu gönderilmez.**

## Kullanım

1. Bu dalın GitHub Actions APK derle çalışmasını aç; **artifact** bölümündeki `ScooterLink-F2-Pro-tanilama` APK dosyasını indir.
2. Android telefona kurup scooter'a bağlan; eşleştirme gerekebilir.
3. Scooter açıkken ve tekerleklere güç uygulanmazken **Kontrolcüyü analiz et** düğmesine bas.
4. Sonuçları ve varsa `Yanıt yok` mesajlarını kaydet; seri numarasını paylaşmadan önce maskele.

## Neden doğrudan bölge/hız değiştirme yok?

F2 Pro'nun bazı stok firmware sürümleri seri numarası/bölge değişikliğini reddeder. Eski Ninebot modellerinin belgelerindeki register adresleri F2 Pro'nun bütün sürümleri için yazılabilir kabul edilemez. Desteklenmeyen adrese rastgele yazmak kontrolcüyü çalışmaz hâle getirebilir.

- XiaoDash'in F2 ailesi için özel firmware kurulum rehberi fiziksel ST-Link işlemi tarif eder: https://www.xiaodash.app/f2
- ScooterHacking SHFW rehberinde F2 Pro için ST-Link kilit açma şartı belirtilir: https://lekrsu.github.io/shfw-walkthrough/
- Legacy Ninebot protokol kayıtları: https://github.com/ownbee/ninebot-ble/blob/main/ninebot_ble/register.py

## Sonraki aşama

Gerçek cihazdan alınan DRV/BLE sürümlerini ve seri önekini (tam numarayı değil) değerlendir. Firmware'in **doğrulanmış** bölge yazma yöntemi varsa bunu ayrı bir dalda, açık onay ekranı, işlem öncesi durum kaydı ve geri dönüş planıyla uygula. Bluetooth üzerinden destek yoksa ST-Link/uyumlu açık firmware veya uygun kontrolcü alternatifini incele.

> 4 tekerlekli özel araçta ağırlık, aktarma oranı, fren sistemi ve termal korumalar scooter ile farklıdır. Yük altında denemeden önce tekerlerin yerden kesildiği, mekanik acil kesme anahtarlı bir test düzeneği kullan.


## Aday hız kayıtları taraması (2026-11-10)

Kullanıcının cihaz logu: şifreli Bluetooth oturumu başarılı, DRV kaydı 0x1A = `89 01` (sürüm `1.8.9` olarak yorumlanıyor), kontrolcü hata kaydı 0. BMS firmware sürümü alanının formatı teyit edilmedi.

Yeni **Hız kayıtlarını tara (salt okunur)** düğmesi, ESC 0x20 üzerindeki `0x48`, `0x67`, `0x68`, `0x93` kayıtlarına yalnızca READ (0x01) gönderir; yanıtları ham hex ve little-endian tamsayı olarak günlüğe yazar.

**Uyarı:** Bu kayıt adresleri farklı üçüncü taraf Ninebot dökümanlarından gelen *adaylardır*. Aynı adres farklı kontrolcü / firmware üzerinde farklı anlam taşıyabilir. Herhangi bir değer yazılmamalıdır. Kayıtlar yanıt verse bile hız sınırını değiştirme yetkisini kanıtlamaz. Yeni sürümde WRITE (0x02) ve bölge/seri numarası yazımı uygulanmamıştır.

Yazma testi ancak kontrolcünün **tam firmware sürümü ve hedef kayıt adresi** güvenilir biçimde doğrulandıktan, orijinal değeri saklama/geri alma akışı oluşturulduktan sonra gündeme gelmeli.

- ScooterHacking Utility F2 desteğini ve spor hız ayarlarını duyurur: https://t.me/s/scooterhackingupdates/83
- XiaoDash F2 ailesinde özel firmware için ST-Link gerekliliğini belirtir: https://www.xiaodash.app/stlink
