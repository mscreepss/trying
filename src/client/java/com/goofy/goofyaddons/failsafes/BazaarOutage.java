package com.goofy.goofyaddons.failsafes;

import com.goofy.goofyaddons.event.ChatHook;
import com.goofy.goofyaddons.features.FeatureManager;
import com.goofy.goofyaddons.utils.ActionLog;
import com.goofy.goofyaddons.utils.ChatUtils;
import net.minecraft.client.Minecraft;

/**
 * Bazaar acilamadiginda makroyu duraklatir.
 *
 * NEDEN VAR - gercek olay (log, 03:07):
 *
 *   03:07:18  The bazaar is temporarily unavailable!
 *   03:07:18  [SELL] sending command for tomato
 *   ...       saniyede ~2 komut, 10 saniye boyunca
 *   03:07:28  A kick occurred in your connection, so you were put in the
 *             SkyBlock lobby!
 *   03:07:28  disconnect.spam
 *   03:07:31  Unknown command. Type "help" for help. ('bz tomato')
 *
 * Hypixel'in bazaari 10 saniyeligine dustu. Makronun bunu anlayacak hicbir
 * mekanizmasi yoktu: ekran acilmadi diye ayni komutu saniyede iki kez gonderdi,
 * SPAM'den kick yedi, lobiye dustu ve orada /bz komutu olmadigi halde
 * 3.5 dakika daha komut gondermeye devam etti. Watchdog ancak 30 saniyede bir
 * mudahale ediyor - kick 10 saniyede geldigi icin onu goremedi.
 *
 * Yani birkac saniyelik gecici bir sunucu arizasi, 4 dakikalik tam cokuse
 * donustu. Bu failsafe tam olarak o donusumu engelliyor: mesaji gorunce
 * makroyu duraklatir, bekler, sonra kaldigi yerden devam ettirir.
 *
 * KAPSAM SINIRI - bilerek: yalnizca ASAGIDAKI mesajlar tetikler. Ekranin
 * acilmamasinin baska (mesaji olmayan) bir sebebi varsa bu failsafe devreye
 * girmez; o durumda hala watchdog'a kalinir.
 */
public class BazaarOutage implements Failsafe {

    /**
     * Bazaar dustuyse ne kadar beklenecek.
     *
     * Hypixel "birkac dakika icinde donecek" diyor. 1 dakika, hem geri gelmesine
     * yetecek kadar uzun hem de bosuna beklemeyecek kadar kisa. Her deneme
     * yalnizca birkac komuta mal oluyor.
     */
    private static final long BAZAAR_WAIT_MS = 60_000;

    /** Kick yedikten sonra adaya donmeden once beklenecek sure. */
    private static final long KICK_WAIT_MS = 15_000;

    /** Ada isinlanmasinin oturmasi icin beklenecek sure. */
    private static final long SETTLE_MS = 8_000;

    /**
     * Ust uste bu kadar denemeden sonra makro tamamen durur.
     *
     * SONSUZ DONGU EMNIYETI: /is de calismiyorsa (baska bir lobide, sunucu
     * sorunlu) duraklat-devam et dongusune girmek yerine pes edip kullaniciyi
     * uyarmak dogru. Makronun kendi kendine yarattigi bir dongu, cozmeye
     * calistigi sorundan daha kotu olur.
     */
    private static final int MAX_KICK_ATTEMPTS = 3;

    /**
     * Bazaar arizasi icin ust sinir daha yuksek.
     *
     * Bu iki durumun riski ayni degil: kick'ten sonra tekrar tekrar adaya
     * isinlanmak zararli bir dongudur, ama bazaar arizasinda her deneme
     * dakikada yalnizca birkac komuta mal oluyor - spam degil, ve ariza
     * gecince kendiliginden duzeliyor. Dakikada bir deneme ile bu, yaklasik
     * 10 dakikalik bir arizaya kadar beklemek demek.
     */
    private static final int MAX_BAZAAR_ATTEMPTS = 10;

    /** Bu kadar sure sorunsuz gectiyse deneme sayaci sifirlanir. */
    private static final long ATTEMPT_RESET_MS = 5 * 60_000;

    private enum Phase {
        OFF,
        /** Bazaar dustu; sure dolunca devam edilecek. */
        WAIT_BAZAAR,
        /** Lobiye dustuk; sure dolunca adaya isinlanilacak. */
        WAIT_KICK,
        /** Isinlandik; sure dolunca devam edilecek. */
        SETTLING
    }

    private Phase phase = Phase.OFF;
    private long actAtMs = 0;
    private int attempts = 0;
    private long lastActivationMs = 0;

    public BazaarOutage() {
        // ChatHook renk kodlarini temizleyip PARCA eslesmesi yapiyor, o yuzden
        // mesajin tamamini yazmaya gerek yok.
        ChatHook.onMessage("bazaar is temporarily unavailable", this::onBazaarDown);
        ChatHook.onMessage("A kick occurred in your connection", this::onKicked);
        ChatHook.onMessage("Unknown command", this::onUnknownCommand);
    }

    @Override
    public String name() {
        return "BazaarOutage";
    }

    @Override
    public void onTick() {
        if (phase == Phase.OFF) return;
        if (System.currentTimeMillis() < actAtMs) return;

        switch (phase) {
            case WAIT_BAZAAR -> {
                phase = Phase.OFF;
                ChatUtils.clientMessage("Bazaar tekrar deneniyor.");
                FeatureManager.INSTANCE.resume();
            }

            case WAIT_KICK -> {
                Minecraft minecraft = Minecraft.getInstance();
                if (minecraft.player == null) return;   // dunya henuz yuklenmedi
                minecraft.player.connection.sendCommand("is");
                phase = Phase.SETTLING;
                actAtMs = System.currentTimeMillis() + SETTLE_MS;
            }

            case SETTLING -> {
                phase = Phase.OFF;
                ChatUtils.clientMessage("Adaya donuldu, makro devam ediyor.");
                FeatureManager.INSTANCE.resume();
            }

            default -> {
            }
        }
    }

    private void onBazaarDown(String message) {
        activate(Phase.WAIT_BAZAAR, BAZAAR_WAIT_MS, MAX_BAZAAR_ATTEMPTS,
                "Bazaar su an kapali. Makro 1 dakika bekleyip devam edecek.",
                "bazaar unavailable - pausing for 60s");
    }

    private void onKicked(String message) {
        activate(Phase.WAIT_KICK, KICK_WAIT_MS, MAX_KICK_ATTEMPTS,
                "Sunucudan lobiye atildin. Makro bekleyip adaya donecek.",
                "kicked to lobby - warping back to the island");
    }

    /**
     * "Unknown command ... ('bz tomato')" - komutumuz burada gecerli degil,
     * yani Skyblock adasinda degiliz.
     *
     * SADECE KENDI KOMUTUMUZ ICIN: mesajda 'bz' gecmiyorsa kullanici baska bir
     * seyi yanlis yazmistir, makroyu duraklatmanin anlami yok.
     */
    private void onUnknownCommand(String message) {
        if (!message.contains("bz")) return;
        activate(Phase.WAIT_KICK, KICK_WAIT_MS, MAX_KICK_ATTEMPTS,
                "Bazaar komutu burada calismiyor - adada degilsin. Makro adaya donecek.",
                "bazaar command rejected - not on the island, warping back");
    }

    /**
     * Duraklat ve sonrasini planla.
     *
     * AYNI OLAYIN TEKRARI YENI DENEME SAYILMAZ: "bazaar kapali" mesaji
     * gonderilen her komut icin bir kez geliyor, yani tek bir arizada onlarca
     * kez tetiklenebilir. Ayni fazdaysak sadece bekleme suresi tazelenir -
     * boylece SON mesajdan itibaren tam sure beklenir ve deneme sayaci
     * bosuna sismez.
     */
    private void activate(Phase next, long waitMs, int maxAttempts, String userMessage, String logMessage) {
        // Makro calismiyorken tetiklenirse (kullanici elle /bz yazmistir) faz
        // acik kalir, onTick ise FailsafeManager tarafindan cagrilmaz ve
        // makro sonra baslatildiginda bu bayat faz devreye girerdi.
        if (!FeatureManager.INSTANCE.isMacroRunning()) return;

        long now = System.currentTimeMillis();

        if (phase == next) {
            actAtMs = now + waitMs;
            return;
        }

        if (now - lastActivationMs > ATTEMPT_RESET_MS) attempts = 0;
        lastActivationMs = now;

        attempts++;
        if (attempts > maxAttempts) {
            phase = Phase.OFF;
            ChatUtils.clientMessage("Bazaar ust uste " + maxAttempts
                    + " kez acilamadi. Makro guvenlik icin durduruldu.");
            ActionLog.add(ActionLog.Tag.RECOVERY,
                    "bazaar unreachable " + maxAttempts + " times - stopping the macro");
            FeatureManager.INSTANCE.stop();
            return;
        }

        FeatureManager.INSTANCE.pause();
        phase = next;
        actAtMs = now + waitMs;

        ChatUtils.clientMessage(userMessage);
        ActionLog.add(ActionLog.Tag.RECOVERY, logMessage);
    }
}
