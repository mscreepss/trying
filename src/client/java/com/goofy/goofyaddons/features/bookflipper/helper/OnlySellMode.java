package com.goofy.goofyaddons.features.bookflipper.helper;

import com.goofy.goofyaddons.config.GoofyConfig;

/**
 * Only Sell modu.
 *
 * AÇIKKEN makro çalışmaya devam eder ama YENİ HAT AÇMAZ. Elde ne varsa onu
 * bitirir: mevcut görevler (1to5, 2to5 gibi) sonuna kadar işlenir, buy order
 * outbid yerse yeniden açılır - çünkü havuz tek sayıya düşerse birleştirme
 * zinciri yarıda kalır ve öksüz parça doğar. Yani "alımı durdur" demek, ortada
 * yarım kalmış hattı da bırakmak demek DEĞİLDİR.
 *
 * ÜÇ AŞAMA:
 *   OFF       - mod kapalı, her şey normal.
 *   FINISHING - mod açık, hâlâ alım fazında görev var (sipariş bekleniyor,
 *               depolanıyor, outbid işleniyor). Yeni hat açılmaz.
 *   SELL_ONLY - alım fazında hiçbir görev kalmadı. Artık sadece eldekiler
 *               birleştirilip satılıyor. Bu aşamada:
 *                 - sell order outbid takibi devreye girer,
 *                 - UPTIME SAYACI DURUR.
 *
 * UPTIME NEDEN DURUYOR: Süre, "ne kadar sürede ne kadar kâr" sorusunun paydası.
 * Alım bittikten sonra geçen süre kâr üretmiyor, sadece elde kalanı tasfiye
 * ediyor. Sayaç orada durursa clean profit / süre oranı gerçeği gösterir.
 */
public final class OnlySellMode {

    public enum Phase {
        OFF,
        FINISHING,
        SELL_ONLY
    }

    private static volatile Phase phase = Phase.OFF;

    private OnlySellMode() {
    }

    public static boolean isEnabled() {
        return GoofyConfig.INSTANCE != null && GoofyConfig.INSTANCE.onlySellMode;
    }

    public static void setEnabled(boolean enabled) {
        if (GoofyConfig.INSTANCE == null) return;
        GoofyConfig.INSTANCE.onlySellMode = enabled;
        if (!enabled) phase = Phase.OFF;
        GoofyConfig.save();
    }

    public static boolean toggle() {
        setEnabled(!isEnabled());
        return isEnabled();
    }

    public static Phase phase() {
        return phase;
    }

    /** BazaarFlipper her tick günceller. */
    public static void setPhase(Phase newPhase) {
        phase = newPhase;
    }

    /** Yeni hat (task) açılmasın mı? */
    public static boolean blocksNewOrders() {
        return isEnabled();
    }

    /** Uptime sayacı dursun mu? */
    public static boolean pauseUptime() {
        return phase == Phase.SELL_ONLY;
    }

    /** Sell order outbid'i işleyelim mi? */
    public static boolean sellOutbidActive() {
        return phase == Phase.SELL_ONLY;
    }

    /** Arayüzde gösterilecek kısa durum. */
    public static String statusLine() {
        if (!isEnabled()) return "off";
        return switch (phase) {
            case SELL_ONLY -> "selling only - uptime paused";
            case FINISHING -> "finishing open buy orders";
            case OFF -> "waiting for the macro to start";
        };
    }
}
