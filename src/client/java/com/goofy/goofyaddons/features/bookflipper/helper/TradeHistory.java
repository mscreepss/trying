package com.goofy.goofyaddons.features.bookflipper.helper;

import com.goofy.goofyaddons.event.ChatHook;
import com.goofy.goofyaddons.utils.ActionLog;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * İşlem geçmişi + kitap bazında ekonomi istatistiği.
 *
 * İKİ AYRI ŞEY TUTAR:
 *  1. TradeRecord listesi - her tamamlanan hat için tek satır (İstatistik >
 *     Gecmis sekmesi).
 *  2. Kitap bazında toplamlar (İstatistik > Kitap sekmesi): kaç adet alındı,
 *     kaç adet satıldı, toplam spend / profit / clean profit. Hem SESSION
 *     (oyun kapanınca sıfırlanır) hem TOTAL (diske yazılır) olarak.
 *
 * PARA NEREDEN GELİYOR: Rakamlar tahmin değil, Hypixel'in kendi claim
 * mesajlarından ayrıştırılıyor:
 *   [Bazaar] Claimed 2x Wisdom I worth 279,780 coins bought for 139,890 each!
 *   [Bazaar] Claimed 381,453 coins from selling 1x Rejuvenate V at 385,793 each!
 * Satış mesajındaki "Claimed N coins" zaten VERGİ SONRASI tutardır; "at Y each"
 * ise vergi öncesi liste fiyatıdır. İkisini birden tuttuğumuz için hem kaba kâr
 * hem temiz kâr gerçek veriyle hesaplanır.
 */
public final class TradeHistory {

    /** Diskte tutulan en fazla kayıt - dosya sonsuza kadar büyümesin. */
    private static final int MAX_RECORDS = 200;
    private static final long SAVE_INTERVAL_MS = 5000;

    private static final Pattern BUY_CLAIM = Pattern.compile(
            "Claimed (\\d+)x (.+?) worth ([\\d,]+(?:\\.\\d+)?) coins bought for [\\d,]+(?:\\.\\d+)? each!");

    private static final Pattern SELL_CLAIM = Pattern.compile(
            "Claimed ([\\d,]+(?:\\.\\d+)?) coins from selling (\\d+)x (.+?) at ([\\d,]+(?:\\.\\d+)?) each!");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH =
            FabricLoader.getInstance().getConfigDir().resolve("goofyaddons_history.json");

    /** Kitap bazında toplamlar. */
    public static class Stats {
        public int bought = 0;
        public int sold = 0;
        public double spend = 0;
        public double revenueGross = 0;
        public double revenueNet = 0;

        public double profit() {
            return revenueGross - spend;
        }

        public double cleanProfit() {
            return revenueNet - spend;
        }
    }

    /** Diske yazılan kap. */
    private static class Store {
        List<TradeRecord> records = new ArrayList<>();
        Map<String, Stats> stats = new LinkedHashMap<>();
    }

    private static Store store = new Store();
    private static final Map<String, Stats> sessionStats = new LinkedHashMap<>();

    /** Hâlâ açık olan hatlar: anahtar = isim + "|" + level. */
    private static final Map<String, TradeRecord> active = new LinkedHashMap<>();

    private static boolean dirty = false;
    private static long lastSaveMs = 0;
    private static boolean registered = false;

    private TradeHistory() {
    }

    public static void register() {
        if (registered) return;
        registered = true;
        load();
        ChatHook.onMessage("Claimed", TradeHistory::onClaim);
    }

    public static void onTick() {
        if (!dirty) return;
        long now = System.currentTimeMillis();
        if (now - lastSaveMs < SAVE_INTERVAL_MS) return;
        lastSaveMs = now;
        dirty = false;
        save();
    }

    // ------------------------------------------------------------ makro kancaları

    /** Bir hat için ilk sipariş açıldığında (görev oluşturulduğunda). */
    public static void begin(Book book) {
        String key = key(book);
        if (active.containsKey(key)) return;
        active.put(key, new TradeRecord(book.name(), book.level(), book.sellLevel(),
                System.currentTimeMillis()));
    }

    /** Bu hat outbid yedi. */
    public static void outbid(Book book) {
        TradeRecord record = active.get(key(book));
        if (record == null) return;
        record.outbidCount++;
    }

    /** Sell order açıldı - süre ölçümü burada biter. */
    public static void sellOrderPlaced(Book book) {
        TradeRecord record = active.remove(key(book));
        if (record == null) return;
        record.sellOrderMs = System.currentTimeMillis();
        record.revenuePending = true;

        store.records.addFirst(record);
        while (store.records.size() > MAX_RECORDS) store.records.removeLast();
        dirty = true;
    }

    /** Görev bırakıldı (fiziksel eksik, stop vb.) - kayıt çöpe gitmesin diye kapatılır. */
    public static void abandon(Book book) {
        active.remove(key(book));
    }

    /** Makro durdurulduğunda açık hatlar temizlenir. */
    public static void clearActive() {
        active.clear();
    }

    // ---------------------------------------------------------------- okuma ucları

    public static List<TradeRecord> records() {
        return new ArrayList<>(store.records);
    }

    public static Map<String, Stats> totalStats() {
        return new LinkedHashMap<>(store.stats);
    }

    public static Map<String, Stats> sessionStats() {
        return new LinkedHashMap<>(sessionStats);
    }

    public static void resetAll() {
        store = new Store();
        sessionStats.clear();
        active.clear();
        dirty = true;
        save();
    }

    // ---------------------------------------------------------------- chat ayrıştırma

    private static void onClaim(String message) {
        Matcher buy = BUY_CLAIM.matcher(message);
        if (buy.find()) {
            int qty = parseInt(buy.group(1));
            String itemName = buy.group(2).trim();
            double worth = parseAmount(buy.group(3));

            String name = stripRoman(itemName);
            int level = romanLevel(itemName);

            stats(name, false).bought += qty;
            stats(name, false).spend += worth;
            stats(name, true).bought += qty;
            stats(name, true).spend += worth;

            TradeRecord record = active.get(name + "|" + level);
            if (record != null) record.spend += worth;

            dirty = true;
            return;
        }

        Matcher sell = SELL_CLAIM.matcher(message);
        if (!sell.find()) return;

        double claimed = parseAmount(sell.group(1));
        int qty = parseInt(sell.group(2));
        String itemName = sell.group(3).trim();
        double unit = parseAmount(sell.group(4));
        double gross = unit * qty;

        String name = stripRoman(itemName);

        stats(name, false).sold += qty;
        stats(name, false).revenueGross += gross;
        stats(name, false).revenueNet += claimed;
        stats(name, true).sold += qty;
        stats(name, true).revenueGross += gross;
        stats(name, true).revenueNet += claimed;

        fillPendingRecords(name, gross, claimed);
        dirty = true;
    }

    /**
     * Satış geliri, o isim için gelir bekleyen kayıtlara dağıtılır.
     *
     * Neden dağıtım: aynı isim için level-1 ve level-2 hatları paralel çalışıyor
     * ve ikisi de aynı satış emrinde birleşebiliyor. Tek satış claim'i birden
     * fazla hattı kapatabilir. Harcamaya orantılı bölmek en dürüst yaklaşım:
     * çok harcayan hat gelirin çoğunu alır.
     */
    private static void fillPendingRecords(String name, double gross, double net) {
        List<TradeRecord> pending = new ArrayList<>();
        for (TradeRecord record : store.records) {
            if (!record.revenuePending) continue;
            if (!record.name.equals(name)) continue;
            pending.add(record);
        }
        if (pending.isEmpty()) return;

        double totalSpend = 0;
        for (TradeRecord record : pending) totalSpend += record.spend;

        for (TradeRecord record : pending) {
            double share = totalSpend > 0 ? record.spend / totalSpend : 1.0 / pending.size();
            record.revenueGross += gross * share;
            record.revenueNet += net * share;
            record.revenuePending = false;
        }
    }

    // ---------------------------------------------------------------- yardımcılar

    private static Stats stats(String name, boolean session) {
        Map<String, Stats> map = session ? sessionStats : store.stats;
        return map.computeIfAbsent(name, k -> new Stats());
    }

    private static String key(Book book) {
        return book.name() + "|" + book.level();
    }

    /** "Wisdom I" -> "Wisdom" */
    public static String stripRoman(String itemName) {
        int space = itemName.lastIndexOf(' ');
        if (space <= 0) return itemName;
        if (romanValue(itemName.substring(space + 1)) <= 0) return itemName;
        return itemName.substring(0, space);
    }

    /** "Wisdom I" -> 1 */
    public static int romanLevel(String itemName) {
        int space = itemName.lastIndexOf(' ');
        if (space <= 0) return 0;
        return romanValue(itemName.substring(space + 1));
    }

    private static int romanValue(String roman) {
        return switch (roman) {
            case "I" -> 1;
            case "II" -> 2;
            case "III" -> 3;
            case "IV" -> 4;
            case "V" -> 5;
            case "VI" -> 6;
            case "VII" -> 7;
            case "VIII" -> 8;
            case "IX" -> 9;
            case "X" -> 10;
            default -> 0;
        };
    }

    private static double parseAmount(String raw) {
        try {
            return Double.parseDouble(raw.replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int parseInt(String raw) {
        try {
            return Integer.parseInt(raw.replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- disk

    private static void load() {
        try {
            if (!Files.exists(PATH)) return;
            Store loaded = GSON.fromJson(Files.readString(PATH), Store.class);
            if (loaded == null) return;
            if (loaded.records == null) loaded.records = new ArrayList<>();
            if (loaded.stats == null) loaded.stats = new LinkedHashMap<>();
            store = loaded;
        } catch (Exception e) {
            ActionLog.add(ActionLog.Tag.SYSTEM, "history file could not be read - starting fresh");
        }
    }

    private static void save() {
        try {
            Files.writeString(PATH, GSON.toJson(store));
        } catch (Exception ignored) {
        }
    }
}
