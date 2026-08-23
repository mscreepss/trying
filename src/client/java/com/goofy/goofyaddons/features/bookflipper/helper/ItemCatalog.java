package com.goofy.goofyaddons.features.bookflipper.helper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Büyü kitabı ID kataloğu - kitap formundaki arama kutusunu besler.
 *
 * SORUN: Kullanıcı "wisdom" yazdığında hangi ID'yi kastettiği belirsizdir;
 * "Ultimate Wisdom" ile "Wisdom" ayrı ürünlerdir ve ID'lerinden başka farkları
 * yoktur. Tahmin etmek yanlış hatta yol açar.
 *
 * ÇÖZÜM: Hypixel'in resources/skyblock/items ucu her eşyanın HEM id'sini HEM
 * görünen adını verir (API key istemez). Arama sonuçlarında ikisi de yan yana,
 * üstüne anlık fiyatla birlikte gösterilir - kullanıcı doğru olanı gözüyle
 * seçer. Seçim de doğrudan uygulanmaz, ayrıca ONAY sorulur.
 *
 * Liste diske cache'lenir; her açılışta yeniden indirilmez.
 */
public final class ItemCatalog {

    /** Taban (level 1) büyü kitabı girdisi. */
    public record Entry(String baseId, String displayName) {
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CACHE =
            FabricLoader.getInstance().getConfigDir().resolve("goofyaddons_items.json");

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static volatile List<Entry> entries = new ArrayList<>();
    private static volatile boolean loading = false;
    private static volatile String status = "yuklenmedi";

    private ItemCatalog() {
    }

    public static void init() {
        loadCache();
        if (entries.isEmpty()) refresh();
    }

    public static String status() {
        if (loading) return "indiriliyor...";
        if (entries.isEmpty()) return status;
        return entries.size() + " kitap yuklendi";
    }

    public static boolean isReady() {
        return !entries.isEmpty();
    }

    /** İnternetten yeniden indirir (form'daki "Yenile" butonu). */
    public static void refresh() {
        if (loading) return;
        loading = true;
        status = "indiriliyor...";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.hypixel.net/v2/resources/skyblock/items"))
                .GET()
                .build();

        CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body)
                .thenApply(body -> JsonParser.parseString(body).getAsJsonObject())
                .thenAccept(ItemCatalog::parse)
                .exceptionally(t -> {
                    loading = false;
                    status = "indirilemedi (internet?)";
                    return null;
                });
    }

    private static void parse(JsonObject root) {
        try {
            JsonArray items = root.getAsJsonArray("items");
            List<Entry> fresh = new ArrayList<>();

            for (JsonElement element : items) {
                JsonObject item = element.getAsJsonObject();
                if (!item.has("id") || !item.has("name")) continue;

                String id = item.get("id").getAsString();
                if (!id.startsWith("ENCHANTMENT_")) continue;
                // Sadece level 1 girdilerini alıyoruz; ID'nin tabanı buradan çıkar
                // ve seviye eklemesini Book.getLevel(i) zaten yapıyor.
                if (!id.endsWith("_1")) continue;

                String baseId = id.substring(0, id.length() - 2);
                String display = stripTrailingRoman(item.get("name").getAsString().trim());
                if (display.isEmpty()) continue;

                fresh.add(new Entry(baseId, display));
            }

            fresh.sort(Comparator.comparing(Entry::displayName, String.CASE_INSENSITIVE_ORDER));
            entries = fresh;
            status = fresh.size() + " kitap yuklendi";
            saveCache(fresh);
        } catch (Exception e) {
            status = "cevap okunamadi";
        } finally {
            loading = false;
        }
    }

    /**
     * Arama: hem görünen adda hem ID'de geçenler döner. Tam başlangıç eşleşmeleri
     * üste alınır ki "wisdom" yazınca "Wisdom" en üstte, "Ultimate Wisdom" hemen
     * altında çıksın - ikisi de görünür, seçim kullanıcının.
     */
    public static List<Entry> search(String query, int limit) {
        List<Entry> result = new ArrayList<>();
        if (query == null) return result;

        String q = query.trim().toLowerCase();
        if (q.isEmpty()) return result;
        String qId = q.replace(' ', '_');

        for (Entry entry : entries) {
            String name = entry.displayName().toLowerCase();
            String id = entry.baseId().toLowerCase();
            if (name.contains(q) || id.contains(qId)) result.add(entry);
        }

        result.sort(Comparator
                .comparingInt((Entry e) -> e.displayName().toLowerCase().startsWith(q) ? 0 : 1)
                .thenComparing(e -> e.displayName().length())
                .thenComparing(Entry::displayName, String.CASE_INSENSITIVE_ORDER));

        if (result.size() > limit) return new ArrayList<>(result.subList(0, limit));
        return result;
    }

    /** Verilen taban ID katalogda var mı? (katalog yüklü değilse "bilinmiyor" = true) */
    public static boolean knows(String baseId) {
        if (entries.isEmpty()) return true;
        for (Entry entry : entries) {
            if (entry.baseId().equalsIgnoreCase(baseId)) return true;
        }
        return false;
    }

    public static String displayNameOf(String baseId) {
        for (Entry entry : entries) {
            if (entry.baseId().equalsIgnoreCase(baseId)) return entry.displayName();
        }
        return null;
    }

    private static String stripTrailingRoman(String name) {
        int space = name.lastIndexOf(' ');
        if (space <= 0) return name;
        String tail = name.substring(space + 1);
        if (TradeHistory.romanLevel("x " + tail) > 0) return name.substring(0, space);
        return name;
    }

    // ---------------------------------------------------------------- disk cache

    private static void loadCache() {
        try {
            if (!Files.exists(CACHE)) return;
            List<Entry> cached = GSON.fromJson(Files.readString(CACHE),
                    new TypeToken<List<Entry>>() {
                    }.getType());
            if (cached == null) return;
            entries = cached;
            status = cached.size() + " kitap (onbellek)";
        } catch (Exception ignored) {
        }
    }

    private static void saveCache(List<Entry> list) {
        try {
            Files.writeString(CACHE, GSON.toJson(list));
        } catch (Exception ignored) {
        }
    }
}
