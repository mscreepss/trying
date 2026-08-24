package com.goofy.goofyaddons.features.bookflipper.helper;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

/**
 * Arayüzün (kitap formu canlı önizlemesi) kullandığı hafif bazaar sorgusu.
 *
 * FlipCalculator'dan AYRI olmasının sebebi: FlipCalculator yalnızca config'te
 * tanımlı kitapları yükler ve makro çalışırken tetiklenir. Form ise henüz
 * config'te olmayan bir ID'nin fiyatını sormak zorunda. Bu sınıf bazaar
 * cevabının tamamını hafızada tutar, en fazla 60 saniyede bir yeniler ve
 * makronun akışına hiç dokunmaz.
 */
public final class BazaarLookup {

    /**
     * Hypixel quick_status alanları.
     *
     * DİKKAT - İSİMLENDİRME TERSTİR: Hypixel'in "buy" / "sell" adlandırması
     * oyuncunun değil, PİYASANIN bakış açısıyladır.
     *  - sellPrice / sellMovingWeek : insta-SELL tarafı, yani senin buy order'ına
     *    insanların sattığı taraf. Alım hattın için bakman gereken hacim budur.
     *  - buyPrice  / buyMovingWeek  : insta-BUY tarafı, yani senin sell order'ından
     *    insanların satın aldığı taraf. Satış hattın için bakman gereken hacim budur.
     */
    public record Quick(double buyPrice, double sellPrice, long buyMovingWeek, long sellMovingWeek) {
    }

    private static final long REFRESH_MS = 60_000;

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static volatile Map<String, Quick> data = new HashMap<>();
    private static volatile boolean loading = false;
    private static volatile long lastLoadMs = 0;
    private static volatile boolean everLoaded = false;

    private BazaarLookup() {
    }

    /** Arayüz her çizimde çağırabilir: gerekiyorsa arka planda yeniler. */
    public static void refreshIfStale() {
        if (loading) return;
        long now = System.currentTimeMillis();
        if (everLoaded && now - lastLoadMs < REFRESH_MS) return;
        loading = true;
        lastLoadMs = now;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.hypixel.net/v2/skyblock/bazaar"))
                .GET()
                .build();

        CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body)
                .thenApply(body -> JsonParser.parseString(body).getAsJsonObject())
                .thenAccept(BazaarLookup::parse)
                .exceptionally(t -> {
                    loading = false;
                    return null;
                });
    }

    private static void parse(JsonObject root) {
        try {
            JsonObject products = root.getAsJsonObject("products");
            Map<String, Quick> fresh = new HashMap<>();

            for (String id : products.keySet()) {
                JsonObject product = products.getAsJsonObject(id);
                if (product == null) continue;
                JsonObject quick = product.getAsJsonObject("quick_status");
                if (quick == null) continue;

                fresh.put(id, new Quick(
                        readDouble(quick, "buyPrice"),
                        readDouble(quick, "sellPrice"),
                        readLong(quick, "buyMovingWeek"),
                        readLong(quick, "sellMovingWeek")
                ));
            }

            // Yerinde değiştirmek yerine referansı takas ediyoruz: arayüz thread'i
            // yarım dolmuş bir haritayı asla görmez.
            data = fresh;
            everLoaded = true;
        } catch (Exception ignored) {
        } finally {
            loading = false;
        }
    }

    private static double readDouble(JsonObject object, String key) {
        return object.has(key) ? object.get(key).getAsDouble() : 0;
    }

    private static long readLong(JsonObject object, String key) {
        return object.has(key) ? object.get(key).getAsLong() : 0;
    }

    /** Ürün yoksa null. */
    public static Quick get(String productId) {
        if (productId == null || productId.isBlank()) return null;
        return data.get(productId);
    }

    /** Bazaar'daki tum urun kimlikleri (ItemCatalog aramayi bunun uzerine kuruyor). */
    public static java.util.Set<String> productIds() {
        return data.keySet();
    }

    public static boolean exists(String productId) {
        return get(productId) != null;
    }

    public static boolean isReady() {
        return everLoaded;
    }
}
