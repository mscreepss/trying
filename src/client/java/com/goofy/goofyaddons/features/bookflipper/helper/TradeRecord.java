package com.goofy.goofyaddons.features.bookflipper.helper;

/**
 * Tamamlanmış bir "hat" kaydı: bir kitabın taban seviyeden satış seviyesine
 * kadar olan tüm yolculuğu.
 *
 * SÜRE TANIMI (kullanıcı isteği): ilk buy order açıldığı an -> sell order
 * açıldığı an. Bitiş noktası satışın DOLMASI değil, satışa ÇIKARILMASI.
 *
 * Gelir alanları sell order açıldığında HENÜZ bilinmez (satış saatler sonra
 * dolabilir). Bu yüzden kayıt "revenuePending = true" olarak kapanır ve ilgili
 * "Claimed ... from selling" mesajı geldiğinde geriye dönük doldurulur.
 *
 * Gson ile diske yazıldığı için record değil, düz alanlı sınıf.
 */
public class TradeRecord {

    public String name = "";
    public int level = 1;
    public int sellLevel = 5;

    /** Bu hat hedefe ulaşana kadar kaç kez outbid yendi. */
    public int outbidCount = 0;

    public long startMs = 0;
    public long sellOrderMs = 0;

    /** Bu hat için gerçekten harcanan (buy claim mesajlarından). */
    public double spend = 0;

    /** Satış listeleme tutarı (vergi öncesi) - satış dolduğunda dolar. */
    public double revenueGross = 0;

    /** Cebe gerçekten giren tutar (vergi sonrası) - satış dolduğunda dolar. */
    public double revenueNet = 0;

    /** Satış henüz claim edilmedi mi? true ise kâr alanları "..." gösterilir. */
    public boolean revenuePending = true;

    public TradeRecord() {
    }

    public TradeRecord(String name, int level, int sellLevel, long startMs) {
        this.name = name;
        this.level = level;
        this.sellLevel = sellLevel;
        this.startMs = startMs;
    }

    public long durationMs() {
        if (sellOrderMs <= 0 || startMs <= 0) return 0;
        return Math.max(0, sellOrderMs - startMs);
    }

    /** Vergi ve kayıplar düşülmüş gerçek kâr. */
    public double cleanProfit() {
        return revenueNet - spend;
    }

    /** Vergi öncesi kaba fark. */
    public double grossProfit() {
        return revenueGross - spend;
    }
}
