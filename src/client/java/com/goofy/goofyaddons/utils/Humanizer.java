package com.goofy.goofyaddons.utils;

import java.util.SplittableRandom;

/**
 * Tıklama gecikmelerini insan davranışına yaklaştırır.
 *
 * NEDEN: Eski randomizer düz (uniform) rastgele seçiyordu - 200ms ile 490ms
 * tamamen eşit sıklıkta çıkıyordu. İnsanda böyle olmaz: tıklamaların çoğu bir
 * ortalamanın etrafında toplanır, uçlar seyrektir. Burada aynı min-max aralığı
 * korunur (dışına ASLA taşılmaz, tüm ara değerler mümkündür), sadece dağılım
 * çan eğrisine çevrilir.
 *
 * İKİ EK DAVRANIŞ:
 *  - YORULMA: Kesintisiz çalışma süresi arttıkça ortalama gecikme çok hafif
 *    yavaşlar (en fazla %8). Molada sıfırlanır.
 *  - MİKRO MOLA: Her 80-500 AKSİYONDA bir (çan eğrisi) 2-8 saniyelik (yine çan
 *    eğrisi) bir duraklama.
 *
 * DİKKAT - SAYAÇ TIKLAMAYI SAYAR, TICK'İ DEĞİL:
 * State machine'deki debounce kalıbı (`clock.start(randomizer())`) gecikme
 * fonksiyonunu HER TICK çağırır; değer çoğu zaman kullanılmadan atılır. Mikro
 * mola sayacı gecikme hesabına bağlansaydı saniyede ~20 kez artar ve mola her
 * birkaç saniyede bir tetiklenirdi. Bu yüzden sayaç yalnızca GERÇEK bir tıklama
 * yapıldığında {@link #noteAction()} ile artar; mola da bir gecikme değeri olarak
 * değil, {@link #isResting()} ile birkaç tick boyunca "hiçbir şey yapma" olarak
 * uygulanır.
 */
public final class Humanizer {

    private static final SplittableRandom RANDOM = new SplittableRandom();

    // --- mikro mola ayarları (kullanıcı isteği: 80-500 aksiyon / 2-8 saniye) ---
    private static final int BREAK_EVERY_MIN = 80;
    private static final int BREAK_EVERY_MAX = 500;
    private static final int BREAK_LENGTH_MIN_MS = 2000;
    private static final int BREAK_LENGTH_MAX_MS = 8000;

    /** Yorulma tavanı: ortalama gecikme en fazla bu oranda yavaşlar. */
    private static final double FATIGUE_MAX = 0.08;
    /** Yorulmanın tavana ulaşma süresi. */
    private static final long FATIGUE_RAMP_MS = 45 * 60 * 1000L;

    private static int actionsSinceBreak = 0;
    private static int actionsUntilBreak = rollBreakInterval();
    private static long streakStartMs = 0;
    private static long restUntilMs = 0;

    private Humanizer() {
    }

    /**
     * Çan eğrisi ile min-max arasında bir tam sayı. Aralık dışına taşmaz;
     * aradaki HER değer çıkabilir, sadece ortaya yakın olanlar daha sıktır.
     */
    public static int gaussian(int min, int max) {
        if (max <= min) return Math.max(1, min);

        double mean = (min + max) / 2.0;
        // 6 sigma = tam aralık. Değerlerin ~%99.7'si zaten içeride doğar,
        // taşan nadir örnekler aşağıda kırpılır.
        double sigma = (max - min) / 6.0;

        double value = mean;
        for (int attempt = 0; attempt < 8; attempt++) {
            value = mean + RANDOM.nextGaussian() * sigma;
            if (value >= min && value <= max) break;
        }

        int result = (int) Math.round(value);
        if (result < min) result = min;
        if (result > max) result = max;
        return result;
    }

    /** Bir tıklama gecikmesi: çan eğrisi + yorulma. */
    public static int delay(int min, int max) {
        return applyFatigue(gaussian(min, max));
    }

    /** Speed Mode: sabit gecikme, ama küçük bir çan + yorulma yine uygulanır. */
    public static int fixedDelay(int ms) {
        int base = Math.max(20, ms);
        // Birebir aynı milisaniyenin binlerce kez tekrarlanması kendi başına bir
        // imzadır; sabit değerin etrafına ±%10'luk küçük bir çan ekliyoruz.
        int jitter = Math.max(1, base / 10);
        return applyFatigue(gaussian(base - jitter, base + jitter));
    }

    /**
     * GERÇEK bir tıklama yapıldığında çağrılır (BazaarFlipper.click).
     * Mikro mola sayacı yalnızca burada ilerler.
     */
    public static void noteAction() {
        long now = System.currentTimeMillis();
        if (streakStartMs == 0) streakStartMs = now;

        actionsSinceBreak++;
        if (actionsSinceBreak < actionsUntilBreak) return;

        actionsSinceBreak = 0;
        actionsUntilBreak = rollBreakInterval();

        int pause = gaussian(BREAK_LENGTH_MIN_MS, BREAK_LENGTH_MAX_MS);
        restUntilMs = now + pause;
        ActionLog.add(ActionLog.Tag.SYSTEM,
                "mikro mola: " + String.format("%.1f", pause / 1000.0) + " sn");
    }

    /**
     * Şu an mikro mola veriliyor mu? true ise makro o tick'te hiçbir şey yapmaz.
     * State ve sayaçlar olduğu gibi kalır - sadece tıklama yapılmaz.
     */
    public static boolean isResting() {
        return System.currentTimeMillis() < restUntilMs;
    }

    /** Makro durduğunda/başladığında sayaçları temizler. */
    public static void reset() {
        actionsSinceBreak = 0;
        actionsUntilBreak = rollBreakInterval();
        streakStartMs = 0;
        restUntilMs = 0;
    }

    /** Uzun mola bitip devam edilince yorulma sıfırlanır (SessionPlanner çağırır). */
    public static void restRecovered() {
        streakStartMs = System.currentTimeMillis();
        restUntilMs = 0;
    }

    // ---------------------------------------------------------------- iç işleyiş

    private static int applyFatigue(int base) {
        if (streakStartMs == 0) return base;
        long elapsed = System.currentTimeMillis() - streakStartMs;
        if (elapsed <= 0) return base;
        double ratio = Math.min(1.0, (double) elapsed / FATIGUE_RAMP_MS);
        return (int) Math.round(base * (1.0 + FATIGUE_MAX * ratio));
    }

    private static int rollBreakInterval() {
        return gaussian(BREAK_EVERY_MIN, BREAK_EVERY_MAX);
    }
}
