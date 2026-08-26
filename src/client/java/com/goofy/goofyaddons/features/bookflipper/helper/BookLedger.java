package com.goofy.goofyaddons.features.bookflipper.helper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DEFTER - hangi kitabın fiziksel olarak NEREDE olduğunu tutar.
 *
 * ESKİ SİSTEM SAYI TUTUYORDU: her hattın "envanterde 6 tane var, depoda 10 tane
 * var" diyen sayaçları vardı. Kitaplar birbirinin tıpatıp aynısı olduğu için o
 * sayaçlar bir KURGUYDU - iki hat aynı fiziksel yığını paylaşıyor, sayaçlardan
 * biri kayınca diğeri de kayıyor ve öksüz kitap doğuyordu. Sayacın kaydığını
 * anlamanın da bir yolu yoktu.
 *
 * BU SİSTEM ADRES TUTAR: "1to5 hattının kitabı depo 1'in 44. slotunda, seviye 1".
 * Kitaplar aynı olsa da ADRESLERİ farklı, yani sahiplik artık kurgu değil.
 * Bir hat tarama yaparken başka hattın adreslerine dokunmaz.
 *
 * BİRİM MATEMATİĞİ: her seviye bir öncekinden 2 tane ister. Bir kaydın taban
 * seviye cinsinden değeri 2^(kaydın seviyesi - hattın taban seviyesi):
 *   taban 1 için  Wisdom 1 = 1,  Wisdom 2 = 2,  Wisdom 3 = 4,  Wisdom 4 = 8
 * Böylece "kitap kayboldu" ile "kitap yarı yolda kaldı" aynı hesaba iner:
 *   eksik = hedef - elimdeki birim - siparişteki
 * Kitap DEFTERE HER ZAMAN GERÇEK SEVİYESİYLE yazılır; birim yalnızca bu eksik
 * hesabı yapılırken kullanılan bir çarpımdır.
 *
 * SLOT NUMARASI HANGİSİ: ekrandaki tıklama id'si ekrandan ekrana değişir
 * (sandıkta 54-89, çekiçte 3-38 gibi), o yüzden defterde ASLA tıklama id'si
 * tutulmaz. Envanter için kitabın envanterdeki kendi adresi (0-35), depo için
 * o sayfadaki sandık slotu tutulur; ikisi de ekran değişince aynı kalır.
 * Tıklama id'si her seferinde o anki ekrandan yeniden bulunur.
 */
public final class BookLedger {

    /** Kitabın durduğu yer. Depo sayfaları config'teki firstPage / secondPage. */
    public enum Place {
        INVENTORY,
        STORAGE_1,
        STORAGE_2
    }

    /**
     * Tek bir fiziksel kitap.
     *
     * @param place nerede
     * @param slot  ekrandan bağımsız adres (envanterde 0-35, depoda sandık slotu)
     * @param level kitabın GERÇEK seviyesi (Wisdom 3 ise 3)
     */
    public record Holding(Place place, int slot, int level) {
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Path PATH =
            FabricLoader.getInstance().getConfigDir().resolve("goofyaddons_ledger.json");

    /** Diske yazılan biçim. Sarmalayıcı sınıf, ileride alan eklemek kolay olsun. */
    private static class Store {
        Map<String, List<Holding>> lines = new LinkedHashMap<>();
    }

    private static Store store = new Store();
    private static boolean dirty = false;

    private BookLedger() {
    }

    /** Hat anahtarı: aynı isimden level 1 ve level 2 hatları AYRI defterlerdir. */
    private static String key(Book line) {
        return line.id() + "|" + line.level();
    }

    // =====================================================================
    // Disk
    // =====================================================================

    public static synchronized void load() {
        try {
            if (!Files.exists(PATH)) {
                store = new Store();
                return;
            }
            Store loaded = GSON.fromJson(Files.readString(PATH), Store.class);
            store = loaded == null ? new Store() : loaded;
            if (store.lines == null) store.lines = new LinkedHashMap<>();
        } catch (Exception e) {
            e.printStackTrace();
            store = new Store();
        }
    }

    /** BazaarFlipper her tick çağırır; yalnızca değişiklik varsa yazar. */
    public static synchronized void onTick() {
        if (!dirty) return;
        dirty = false;
        save();
    }

    public static synchronized void save() {
        try {
            Files.writeString(PATH, GSON.toJson(store));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // =====================================================================
    // Okuma
    // =====================================================================

    /** Bu hattın tüm kayıtları (kopya - gezinirken defter değişebilir). */
    public static synchronized List<Holding> of(Book line) {
        List<Holding> list = store.lines.get(key(line));
        return list == null ? new ArrayList<>() : new ArrayList<>(list);
    }

    /** Bu hattın belirli bir yerdeki kayıtları. */
    public static synchronized List<Holding> of(Book line, Place place) {
        List<Holding> out = new ArrayList<>();
        for (Holding h : of(line)) {
            if (h.place() == place) out.add(h);
        }
        return out;
    }

    /**
     * Hattın elindeki toplam BİRİM değeri.
     * Wisdom 4, taban seviyesi 1 olan bir hat için 8 birim eder.
     */
    public static synchronized int units(Book line) {
        int total = 0;
        for (Holding h : of(line)) total += unitsOf(line, h);
        return total;
    }

    public static synchronized int unitsIn(Book line, Place place) {
        int total = 0;
        for (Holding h : of(line, place)) total += unitsOf(line, h);
        return total;
    }

    /** Tek bir kaydın taban seviye cinsinden değeri. */
    public static int unitsOf(Book line, Holding holding) {
        int step = holding.level() - line.level();
        if (step < 0) return 0;      // taban seviyenin altinda kitap olamaz
        if (step > 20) return 0;     // saglik kontrolu - bozuk kayit tasmasin
        return 1 << step;
    }

    /** Bu adres HERHANGİ bir hatta kayıtlı mı? (sahipsiz kitap avı için) */
    public static synchronized boolean isOwned(Place place, int slot) {
        for (List<Holding> list : store.lines.values()) {
            for (Holding h : list) {
                if (h.place() == place && h.slot() == slot) return true;
            }
        }
        return false;
    }

    /** Bu adres BAŞKA bir hatta mı kayıtlı? (kendi kitabımı yabancı sanmayayım) */
    public static synchronized boolean ownedByOther(Book line, Place place, int slot) {
        String mine = key(line);
        for (Map.Entry<String, List<Holding>> entry : store.lines.entrySet()) {
            if (entry.getKey().equals(mine)) continue;
            for (Holding h : entry.getValue()) {
                if (h.place() == place && h.slot() == slot) return true;
            }
        }
        return false;
    }

    // =====================================================================
    // Yazma
    // =====================================================================

    public static synchronized void add(Book line, Place place, int slot, int level) {
        List<Holding> list = store.lines.computeIfAbsent(key(line), k -> new ArrayList<>());
        // Ayni adres iki kez yazilmasin - tarama tekrarlanirsa sayac sismesin.
        list.removeIf(h -> h.place() == place && h.slot() == slot);
        list.add(new Holding(place, slot, level));
        dirty = true;
    }

    public static synchronized void remove(Book line, Place place, int slot) {
        List<Holding> list = store.lines.get(key(line));
        if (list == null) return;
        if (list.removeIf(h -> h.place() == place && h.slot() == slot)) dirty = true;
    }

    /**
     * Bir yerin TÜM kayıtlarını siler.
     *
     * Doğrulama turunda kullanılır: sayfa açıkken önce o yerin defteri
     * boşaltılır, sonra ekranda GERÇEKTEN ne varsa yeniden yazılır. Böylece
     * defter fiziksel gerçeğe hizalanır, üstüne eklenmez.
     */
    public static synchronized void clearPlace(Book line, Place place) {
        List<Holding> list = store.lines.get(key(line));
        if (list == null) return;
        if (list.removeIf(h -> h.place() == place)) dirty = true;
    }

    /** Hat kapandı (satıldı ya da bırakıldı). */
    public static synchronized void clearLine(Book line) {
        if (store.lines.remove(key(line)) != null) dirty = true;
    }

    /** Makro durdu: defter kalsın (disk), yalnızca bellek temizlenmesin. */
    public static synchronized void clearAll() {
        store.lines.clear();
        dirty = true;
    }

    /** Defterde hiç kayıt yok mu? */
    public static synchronized boolean isEmpty() {
        for (List<Holding> list : store.lines.values()) {
            if (!list.isEmpty()) return false;
        }
        return true;
    }

    /** Kısa özet - log ve HUD için. */
    public static String summary(Book line) {
        int inv = unitsIn(line, Place.INVENTORY);
        int s1 = unitsIn(line, Place.STORAGE_1);
        int s2 = unitsIn(line, Place.STORAGE_2);
        return "inv=" + inv + " s1=" + s1 + " s2=" + s2 + " toplam=" + (inv + s1 + s2);
    }
}
