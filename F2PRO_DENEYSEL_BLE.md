# Ninebot F2 Pro — Deneysel BLE hız modu

Bu dal yalnızca deneysel test içindir. `main` ve önceki **salt-okunur tanılama** dalı değiştirilmemiştir.

## Gerçek cihazda gözlemlenenler

- Ninebot F2 Pro, NAGT seri öneki.
- Kontrolcü (DRV): 1.8.9 (`89 01`).
- Kontrolcü 0xF1 = 25, **Eco, Normal ve Spor modlarında değişmiyor**. Bu kayıt mod seçimi değil, ayrı bir üst sınır olabilir. F2 Pro'da yazılabilirliği **doğrulanmadı**.
- `0x75` kayıt yazma denemesinde mod değişmemiş. Mod göstergesi ise fiziksel düğmeyle değiştiğinde doğru güncelleniyor.

## Deneysel komut

- Kamuya açık topluluk kaynaklarında F2/F2 Plus/F2 Pro için `55 AB 46 32 53 63 6F 6F 74 65 72 5F 31` açma, `... 30` geri alma baytları raporlanmıştır.
- Komut, kullanıcı **ayrı onay verince**, kontrolcü hız değeri **0**, scooter güç durumu **Açık** ve BLE oturumu aktifken, **tek kez** NUS_TX üzerinden no-response yazar.
- `0xF1` önce/sonra okunur. Eşit kalması komutun başarısız olduğunu **kanıtlamaz**; farklı kalması da gerçek maksimum hızı kanıtlamaz.
- Komutun BLE kuyruğuna alınması scooter'ın kabul ettiği anlamına gelmez. İşlem sırasında da kesin başarı mesajı verilmez.
- **Seri numarası değişmez; bölge yazma yok; custom firmware/flash işlemi yok.**
- Bu yöntem DRV 1.8.9 için doğrulanmış değildir. Firmware engelleri nedeniyle etkisiz kalabilir.

Kaynaklar:
- https://www.ozbargain.com.au/node/846388
- https://miworld.vn/huong-dan/huong-dan-mo-toc-do-cho-xe-scooter-segway-ninebot-f2-f2plus-f2-pro-va-g65-max2--n23764.html
- https://www.reddit.com/r/ElectricScooters/comments/1d4n80z/segway_ninebot_f2_pro_speed_unlock_problem/
- SHFW F2 Pro ST-Link şartı: https://lekrsu.github.io/shfw-walkthrough/

## Test adımları

1. Son Actions **F2 Pro deneysel BLE APK** başarılı çalışmasının **Artifacts** kısmından APK ZIP'ini indir.
2. Scooter'u sabitle, tekerlerini yerden kaldır; gaz veya sürüşle maksimum hızı test etme. Fren ve kesme düzeneği hazır olmalı.
3. ScooterLink'e bağlan, **OTURUM AÇILDI** mesajını bekle.
4. Panelde hız 0 ve güç açıkken, **Deneysel hız açma komutu** düğmesine dokun.
5. Açık onay metnini inceleyip karar ver. Komut kendiliğinden çalışmaz.
6. **Logu kopyala** ile işlem sonucunu incele. Bu sadece BLE aktarımı ve kayıt okumasıdır; hız artışını onaylamaz.
7. Deneysel komuttan sonra istenmeyen davranış varsa kullanmayı durdur. **Normal moda geri alma komutu** mevcut fakat firmware bu komutu da reddedebilir; geri dönüş garantisi değildir.

**Güvenlik:** Söz konusu kontrolcü, kullanıcı tarafından yapılacak dört tekerlekli bir araçta kullanılacak. Fren, mekanik güç kesme, motor akımı, kablo kesitleri, tekerlek çapı ve ısı limitleri doğrulanmadan araçla sürüş yapılmamalıdır. Cihazın kontrolcüsü kalıcı zarar görebilir.
