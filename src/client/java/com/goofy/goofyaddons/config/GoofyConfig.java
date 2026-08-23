package com.goofy.goofyaddons.config;

import com.goofy.goofyaddons.features.bookflipper.helper.Book;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class GoofyConfig {

    public List<Book> books = new ArrayList<>();

    public GoofyConfig() {
        books.add(new Book("ENCHANTMENT_ULTIMATE_WISE", 1, 5, "Ultimate Wise"));
        books.add(new Book("ENCHANTMENT_ULTIMATE_WISE", 2, 5, "Ultimate Wise"));
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("goofyaddons.json");

    public static GoofyConfig INSTANCE;

    public boolean speedMode = false;
    public int speedModeDelay = 250;
    public int minActionDelay = 300;
    public int maxActionDelay = 600;
    public String firstPage = "ec";
    public String secondPage = "ec 2";

    // --- Session planlayici (BETA) ---
    /** Kapali gelir: beta oldugu icin kullanici acikca acmadan calismaz. */
    public boolean sessionPlannerEnabled = false;
    public int workMinMinutes = 20;
    public int workMaxMinutes = 40;
    public int breakMinMinutes = 3;
    public int breakMaxMinutes = 10;

    /**
     * Kapatilmis kitap hatlari: "ID|level" bicminde.
     *
     * NEDEN AYRI LISTE: Book bir record ve mevcut config dosyalarinda "enabled"
     * alani yok. Record'a alan eklemek eski JSON'lardaki tum kitaplari KAPALI
     * yapardi (bos boolean = false). Ayri bir liste tutunca varsayilan her zaman
     * "acik" olur ve kimsenin config'i bozulmaz.
     */
    public List<String> disabledBooks = new ArrayList<>();

    // Kalıcı bazaar ekonomi istatistikleri (EconomyTracker)
    public double totalSpend = 0;
    public double totalEarn = 0;
    public long totalUptimeMs = 0;

    // --- HUD ---
    /** HUD'un sol üst köşesinin ekran koordinatı (Move HUD ekranından sürüklenerek ayarlanır). */
    public int hudX = 8;
    public int hudY = 8;
    public boolean hudVisible = true;

    // --- Tuş atamaları ---
    public Keys keys = new Keys();

    /**
     * GLFW tuş kodları. -1 = atanmamış. Yalnızca menu tuşunun varsayılanı vardır;
     * diğerlerini kullanıcı arayüzden atar.
     */
    public static class Keys {
        public int menu = GLFW.GLFW_KEY_M;
        public int start = -1;
        public int pauseResume = -1;
        public int stop = -1;
        public int reloadConfig = -1;
        public int moveHud = -1;
        /** HUD All-time <-> Session gecisi. Eski surumdeki V tusu korundu. */
        public int hudMode = GLFW.GLFW_KEY_V;
    }

    // =====================================================================
    // Kitap listesi düzenleme
    //
    // ÖNEMLİ: liste yerinde değiştirilmez, her seferinde YENİ bir liste ile
    // değiştirilir. FlipCalculator bu listeyi HTTP cevabı geldiğinde başka bir
    // thread'de dolaşıyor; arayüzden yerinde ekleme/silme yapılsaydı
    // ConcurrentModificationException riski olurdu.
    // =====================================================================

    public static synchronized void addBook(Book book) {
        if (INSTANCE == null) return;
        List<Book> copy = new ArrayList<>(INSTANCE.books);
        copy.add(book);
        INSTANCE.books = copy;
        save();
    }

    public static synchronized void replaceBook(int index, Book book) {
        if (INSTANCE == null) return;
        if (index < 0 || index >= INSTANCE.books.size()) return;
        List<Book> copy = new ArrayList<>(INSTANCE.books);
        copy.set(index, book);
        INSTANCE.books = copy;
        save();
    }

    public static synchronized void removeBook(int index) {
        if (INSTANCE == null) return;
        if (index < 0 || index >= INSTANCE.books.size()) return;
        List<Book> copy = new ArrayList<>(INSTANCE.books);
        copy.remove(index);
        INSTANCE.books = copy;
        save();
    }

    // =====================================================================
    // Kitap acik/kapali
    // =====================================================================

    private static String bookKey(Book book) {
        return book.id() + "|" + book.level();
    }

    public static boolean isBookEnabled(Book book) {
        if (INSTANCE == null || INSTANCE.disabledBooks == null) return true;
        return !INSTANCE.disabledBooks.contains(bookKey(book));
    }

    public static synchronized void setBookEnabled(Book book, boolean enabled) {
        if (INSTANCE == null) return;
        if (INSTANCE.disabledBooks == null) INSTANCE.disabledBooks = new ArrayList<>();
        List<String> copy = new ArrayList<>(INSTANCE.disabledBooks);
        String key = bookKey(book);
        copy.remove(key);
        if (!enabled) copy.add(key);
        INSTANCE.disabledBooks = copy;
        save();
    }

    /** Aynı id + level ikilisi zaten var mı? (aynı hattın iki kez tanımlanması hataya yol açar) */
    public static boolean hasBook(String id, int level, int ignoreIndex) {
        if (INSTANCE == null) return false;
        for (int i = 0; i < INSTANCE.books.size(); i++) {
            if (i == ignoreIndex) continue;
            Book b = INSTANCE.books.get(i);
            if (b.id().equalsIgnoreCase(id) && b.level() == level) return true;
        }
        return false;
    }

    // =====================================================================

    public static void load() {
        try {
            if (Files.exists(CONFIG_PATH)) {
                INSTANCE = GSON.fromJson(Files.readString(CONFIG_PATH), GoofyConfig.class);
            } else {
                INSTANCE = new GoofyConfig();
                save();
            }
        } catch (Exception e) {
            e.printStackTrace();
            INSTANCE = new GoofyConfig();
        }
        if (INSTANCE == null) INSTANCE = new GoofyConfig();
        // Eski config dosyalarında bu alanlar yok - null gelmesinler.
        if (INSTANCE.books == null) INSTANCE.books = new ArrayList<>();
        if (INSTANCE.keys == null) INSTANCE.keys = new Keys();
        if (INSTANCE.disabledBooks == null) INSTANCE.disabledBooks = new ArrayList<>();
        // Eski config'lerde bu alanlar 0 gelir; 0 dakika = sonsuz dongu demek.
        if (INSTANCE.workMinMinutes <= 0) INSTANCE.workMinMinutes = 20;
        if (INSTANCE.workMaxMinutes <= INSTANCE.workMinMinutes) INSTANCE.workMaxMinutes = INSTANCE.workMinMinutes + 20;
        if (INSTANCE.breakMinMinutes <= 0) INSTANCE.breakMinMinutes = 3;
        if (INSTANCE.breakMaxMinutes <= INSTANCE.breakMinMinutes) INSTANCE.breakMaxMinutes = INSTANCE.breakMinMinutes + 7;
    }

    public static void save() {
        try {
            Files.writeString(CONFIG_PATH, GSON.toJson(INSTANCE));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
