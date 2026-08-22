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
    }

    public static void save() {
        try {
            Files.writeString(CONFIG_PATH, GSON.toJson(INSTANCE));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
