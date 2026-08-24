package com.goofy.goofyaddons.keybinds;

import com.goofy.goofyaddons.config.GoofyConfig;
import org.lwjgl.glfw.GLFW;

/**
 * Makronun tuş atanabilir eylemleri.
 *
 * Vanilla KeyMapping sistemi KULLANILMIYOR: tuşlar config'te GLFW kodu olarak
 * tutuluyor ve her tick GLFW'den doğrudan okunuyor (GoofyKeybinds). Böylece
 * atamalar tamamen kendi arayüzümüzden yönetilebiliyor ve sürüm sürüm değişen
 * KeyMapping API'sine bağımlı kalmıyoruz.
 *
 * MENU dışında hiçbir eylemin varsayılan tuşu YOKTUR (kod -1 = atanmamış).
 */
public enum KeyAction {

    MENU("Menu", "Open / close the GoofyAddons menu", GLFW.GLFW_KEY_M),
    START("Start", "Start the macro from scratch", -1),
    PAUSE_RESUME("Pause / Resume", "Freeze in place, continue from the same point", -1),
    STOP("Stop", "Stop the macro and clear all tasks", -1),
    RELOAD_CONFIG("Reload Config", "Re-read the config file from disk", -1),
    HUD_MODE("HUD Mode", "Switch the Profit HUD between All-time / Session", GLFW.GLFW_KEY_V),
    ONLY_SELL("Only Sell", "Stop opening new lines, finish and sell what you have", -1);

    private final String title;
    private final String description;
    private final int defaultKey;

    KeyAction(String title, String description, int defaultKey) {
        this.title = title;
        this.description = description;
        this.defaultKey = defaultKey;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public int defaultKey() {
        return defaultKey;
    }

    public int getKey() {
        GoofyConfig.Keys k = keys();
        return switch (this) {
            case MENU -> k.menu;
            case START -> k.start;
            case PAUSE_RESUME -> k.pauseResume;
            case STOP -> k.stop;
            case RELOAD_CONFIG -> k.reloadConfig;
            case HUD_MODE -> k.hudMode;
            case ONLY_SELL -> k.onlySell;
        };
    }

    public void setKey(int code) {
        GoofyConfig.Keys k = keys();
        switch (this) {
            case MENU -> k.menu = code;
            case START -> k.start = code;
            case PAUSE_RESUME -> k.pauseResume = code;
            case STOP -> k.stop = code;
            case RELOAD_CONFIG -> k.reloadConfig = code;
            case HUD_MODE -> k.hudMode = code;
            case ONLY_SELL -> k.onlySell = code;
        }
        GoofyConfig.save();
    }

    private static GoofyConfig.Keys keys() {
        if (GoofyConfig.INSTANCE == null) return new GoofyConfig.Keys();
        if (GoofyConfig.INSTANCE.keys == null) GoofyConfig.INSTANCE.keys = new GoofyConfig.Keys();
        return GoofyConfig.INSTANCE.keys;
    }

    /** Tuşun ekranda görünecek adı. Atanmamışsa "None". */
    public String keyName() {
        return keyName(getKey());
    }

    public static String keyName(int code) {
        if (code < 0) return "None";
        if (code == GLFW.GLFW_KEY_SPACE) return "SPACE";

        String glfwName = GLFW.glfwGetKeyName(code, 0);
        if (glfwName != null && !glfwName.isBlank()) return glfwName.toUpperCase();

        return switch (code) {
            case GLFW.GLFW_KEY_ESCAPE -> "ESC";
            case GLFW.GLFW_KEY_ENTER -> "ENTER";
            case GLFW.GLFW_KEY_TAB -> "TAB";
            case GLFW.GLFW_KEY_BACKSPACE -> "BACKSPACE";
            case GLFW.GLFW_KEY_LEFT_SHIFT -> "L-SHIFT";
            case GLFW.GLFW_KEY_RIGHT_SHIFT -> "R-SHIFT";
            case GLFW.GLFW_KEY_LEFT_CONTROL -> "L-CTRL";
            case GLFW.GLFW_KEY_RIGHT_CONTROL -> "R-CTRL";
            case GLFW.GLFW_KEY_LEFT_ALT -> "L-ALT";
            case GLFW.GLFW_KEY_RIGHT_ALT -> "R-ALT";
            case GLFW.GLFW_KEY_UP -> "UP";
            case GLFW.GLFW_KEY_DOWN -> "DOWN";
            case GLFW.GLFW_KEY_LEFT -> "LEFT";
            case GLFW.GLFW_KEY_RIGHT -> "RIGHT";
            case GLFW.GLFW_KEY_INSERT -> "INSERT";
            case GLFW.GLFW_KEY_DELETE -> "DELETE";
            case GLFW.GLFW_KEY_HOME -> "HOME";
            case GLFW.GLFW_KEY_END -> "END";
            case GLFW.GLFW_KEY_PAGE_UP -> "PG UP";
            case GLFW.GLFW_KEY_PAGE_DOWN -> "PG DN";
            default -> {
                if (code >= GLFW.GLFW_KEY_F1 && code <= GLFW.GLFW_KEY_F25) {
                    yield "F" + (code - GLFW.GLFW_KEY_F1 + 1);
                }
                yield "KEY " + code;
            }
        };
    }
}
