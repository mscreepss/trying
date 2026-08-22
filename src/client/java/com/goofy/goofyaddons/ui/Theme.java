package com.goofy.goofyaddons.ui;

/**
 * Tek merkezden renk paleti. Koyu, düz (flat) ve az kontrastlı bir tema:
 * vanilla Minecraft'ın taş dokulu/bevel'li görünümü yerine modern bir panel dili.
 * Renk değiştirmek istersen sadece burayı düzenle.
 */
public final class Theme {

    private Theme() {
    }

    // --- yüzeyler ---
    /** Ekranın tamamını karartan katman. */
    public static final int SCRIM = 0xCC07080B;
    /** Ana pencere zemini. */
    public static final int WINDOW = 0xFF15171C;
    /** Pencere içi kart / panel. */
    public static final int CARD = 0xFF1C1F26;
    /** Kart içi girinti (input, liste satırı). */
    public static final int INSET = 0xFF23262F;
    /** Fare üzerindeyken. */
    public static final int HOVER = 0xFF2E323D;
    /** Kenar çizgisi. */
    public static final int STROKE = 0xFF2B2F39;
    /** Seçili sekme zemini. */
    public static final int SELECTED = 0xFF262B36;

    // --- yazı ---
    public static final int TEXT = 0xFFE9ECF2;
    public static final int TEXT_DIM = 0xFF878D9C;
    public static final int TEXT_FAINT = 0xFF5A6070;

    // --- vurgu ---
    public static final int ACCENT = 0xFF8CE0FD;
    public static final int ACCENT_SOFT = 0x408CE0FD;
    public static final int GREEN = 0xFF5BD98A;
    public static final int GREEN_SOFT = 0x405BD98A;
    public static final int YELLOW = 0xFFFFC46B;
    public static final int YELLOW_SOFT = 0x40FFC46B;
    public static final int RED = 0xFFFF6B6B;
    public static final int RED_SOFT = 0x40FF6B6B;

    // --- HUD ---
    public static final int HUD_BG = 0xD9101217;
    public static final int HUD_STROKE = 0x40FFFFFF;
}
