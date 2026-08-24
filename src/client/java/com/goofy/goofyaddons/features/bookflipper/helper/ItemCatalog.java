package com.goofy.goofyaddons.features.bookflipper.helper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Büyü kitabı ID kataloğu - kitap formundaki aramayı besler.
 *
 * NEDEN YENİDEN YAZILDI: Önceki sürüm listeyi yalnızca Hypixel'in
 * resources/skyblock/items ucundan çekiyordu. O istek gecikirse ya da
 * başarısız olursa arama HİÇBİR SONUÇ döndürmüyordu - yaşadığın sorun tam
 * olarak buydu.
 *
 * Artık asıl kaynak BAZAAR'IN KENDİSİ: BazaarLookup zaten tüm ürün listesini
 * çekiyor ve bu makronun her yerinde çalıştığı kanıtlanmış durumda. Katalog o
 * listeden türetiliyor:
 *
 *   ENCHANTMENT_ULTIMATE_WISE_1  ->  baseId: ENCHANTMENT_ULTIMATE_WISE
 *                                    ad:     "Ultimate Wise"
 *
 * items ucu yalnızca İSİMLERİ GÜZELLEŞTİRMEK için, en iyi çaba prensibiyle
 * kullanılıyor; gelmezse arama yine de tam çalışır.
 */
public final class ItemCatalog {

    /** Taban (level 1) büyü kitabı girdisi. */
    public record Entry(String baseId, String displayName) {
    }

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    /** items ucundan gelen resmi adlar: baseId -> "Ultimate Wise". */
    private static volatile Map<String, String> officialNames = new HashMap<>();
    private static volatile boolean namesLoading = false;
    private static volatile boolean namesLoaded = false;

    /** Bazaar'dan türetilmiş liste; bazaar yenilendiğinde yeniden kurulur. */
    private static volatile List<Entry> entries = new ArrayList<>();
    private static volatile int builtFrom = -1;

    private ItemCatalog() {
    }

    public static void init() {
        loadOfficialNames();
    }

    /** Arayüzün gösterdiği durum satırı. */
    public static String status() {
        if (!BazaarLookup.isReady()) return "loading bazaar...";
        int count = rebuildIfNeeded().size();
        if (count == 0) return "no enchanted books found";
        return count + " books" + (namesLoaded ? "" : " (names loading)");
    }

    public static boolean isReady() {
        return BazaarLookup.isReady() && !rebuildIfNeeded().isEmpty();
    }

    /** Kitap formundaki "refresh" düğmesi. */
    public static void refresh() {
        builtFrom = -1;
        namesLoaded = false;
        BazaarLookup.refreshIfStale();
        loadOfficialNames();
    }

    // ---------------------------------------------------------------- katalog

    /**
     * Bazaar ürün listesi değiştiyse katalogu yeniden kurar.
     *
     * Ürün sayısı bir "sürüm numarası" gibi kullanılıyor: bazaar cevabı
     * yenilendiğinde sayı pratikte aynı kalır, o yüzden isimler yüklendiğinde de
     * yeniden kurmayı tetikliyoruz (builtFrom = -1).
     */
    private static List<Entry> rebuildIfNeeded() {
        if (!BazaarLookup.isReady()) return entries;

        int size = BazaarLookup.productIds().size();
        if (builtFrom == size) return entries;

        List<Entry> fresh = new ArrayList<>();
        Map<String, String> names = officialNames;

        for (String productId : BazaarLookup.productIds()) {
            if (!productId.startsWith("ENCHANTMENT_")) continue;
            // Sadece seviye 1 girdisini alıyoruz; taban ID buradan cikiyor,
            // seviye eklemesini Book.getLevel(i) zaten yapiyor.
            if (!productId.endsWith("_1")) continue;

            String baseId = productId.substring(0, productId.length() - 2);
            String name = names.get(baseId);
            if (name == null || name.isBlank()) name = prettify(baseId);
            fresh.add(new Entry(baseId, name));
        }

        fresh.sort(Comparator.comparing(Entry::displayName, String.CASE_INSENSITIVE_ORDER));
        entries = fresh;
        builtFrom = size;
        return fresh;
    }

    /** ENCHANTMENT_ULTIMATE_WISE -> "Ultimate Wise" */
    private static String prettify(String baseId) {
        String raw = baseId.startsWith("ENCHANTMENT_") ? baseId.substring("ENCHANTMENT_".length()) : baseId;
        String[] parts = raw.split("_");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(part.charAt(0))
                    .append(part.substring(1).toLowerCase(Locale.US));
        }
        return out.toString();
    }

    /**
     * Arama: hem görünen adda hem ID'de geçenler döner. Baştan eşleşenler üste
     * alınır ki "wisdom" yazınca "Wisdom" en üstte, "Ultimate Wisdom" hemen
     * altında çıksın - ikisi de görünür, seçim kullanıcının.
     */
    public static List<Entry> search(String query, int limit) {
        List<Entry> all = rebuildIfNeeded();
        List<Entry> result = new ArrayList<>();
        if (query == null) return result;

        String q = query.trim().toLowerCase(Locale.US);
        if (q.isEmpty()) {
            // Bos aramada da bir seyler gorunsun - liste bos hissettirmesin.
            for (int i = 0; i < Math.min(limit, all.size()); i++) result.add(all.get(i));
            return result;
        }

        String qId = q.replace(' ', '_');
        for (Entry entry : all) {
            String name = entry.displayName().toLowerCase(Locale.US);
            String id = entry.baseId().toLowerCase(Locale.US);
            if (name.contains(q) || id.contains(qId)) result.add(entry);
        }

        result.sort(Comparator
                .comparingInt((Entry e) -> e.displayName().toLowerCase(Locale.US).startsWith(q) ? 0 : 1)
                .thenComparingInt(e -> e.displayName().length())
                .thenComparing(Entry::displayName, String.CASE_INSENSITIVE_ORDER));

        if (result.size() > limit) return new ArrayList<>(result.subList(0, limit));
        return result;
    }

    public static String displayNameOf(String baseId) {
        if (baseId == null) return null;
        for (Entry entry : rebuildIfNeeded()) {
            if (entry.baseId().equalsIgnoreCase(baseId)) return entry.displayName();
        }
        return null;
    }

    // ---------------------------------------------------------------- resmi adlar

    /**
     * items ucu API key istemez ve her eşyanın görünen adını verir. Gelmezse
     * hiçbir şey bozulmaz: adlar ID'den türetilmiş hâlde kalır.
     */
    private static void loadOfficialNames() {
        if (namesLoading || namesLoaded) return;
        namesLoading = true;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.hypixel.net/v2/resources/skyblock/items"))
                .GET()
                .build();

        CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body)
                .thenApply(body -> JsonParser.parseString(body).getAsJsonObject())
                .thenAccept(ItemCatalog::parseNames)
                .exceptionally(t -> {
                    namesLoading = false;
                    return null;
                });
    }

    private static void parseNames(JsonObject root) {
        try {
            JsonArray items = root.getAsJsonArray("items");
            Map<String, String> fresh = new HashMap<>();

            for (JsonElement element : items) {
                JsonObject item = element.getAsJsonObject();
                if (!item.has("id") || !item.has("name")) continue;

                String id = item.get("id").getAsString();
                if (!id.startsWith("ENCHANTMENT_") || !id.endsWith("_1")) continue;

                String baseId = id.substring(0, id.length() - 2);
                fresh.put(baseId, stripTrailingRoman(item.get("name").getAsString().trim()));
            }

            officialNames = fresh;
            namesLoaded = true;
            // Isimler geldi: katalog bir sonraki aramada yeniden kurulsun.
            builtFrom = -1;
        } catch (Exception ignored) {
        } finally {
            namesLoading = false;
        }
    }

    private static String stripTrailingRoman(String name) {
        int space = name.lastIndexOf(' ');
        if (space <= 0) return name;
        String tail = name.substring(space + 1);
        if (TradeHistory.romanLevel("x " + tail) > 0) return name.substring(0, space);
        return name;
    }
}
