package com.goofy.goofyaddons.keybinds;

import com.goofy.goofyaddons.config.GoofyConfig;
import com.goofy.goofyaddons.features.FeatureManager;
import com.goofy.goofyaddons.features.bookflipper.helper.OnlySellMode;
import com.goofy.goofyaddons.features.economy.EconomyTracker;
import com.goofy.goofyaddons.ui.BaseScreen;
import com.goofy.goofyaddons.ui.GoofyScreen;
import com.goofy.goofyaddons.utils.ActionLog;
import com.goofy.goofyaddons.utils.ChatUtils;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;

import java.util.EnumSet;
import java.util.Set;

/**
 * Tuşları her client tick'inde GLFW'den okuyup kenar (edge) tespiti yapar:
 * eylem yalnızca tuş BASILDIĞI an bir kez tetiklenir, basılı tutulurken tekrarlamaz.
 *
 * Kural: eylemler yalnızca hiçbir ekran açık değilken çalışır. Böylece sohbete
 * yazarken ya da arayüzde tuş atarken yanlışlıkla makro başlatılmaz.
 */
public final class GoofyKeybinds {

    private static final Set<KeyAction> down = EnumSet.noneOf(KeyAction.class);

    private GoofyKeybinds() {
    }

    public static void onTick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getWindow() == null) return;
        if (GoofyConfig.INSTANCE == null) return;

        // Bu surumde InputConstants.isKeyDown dogrudan Window nesnesi aliyor
        // (eskiden GLFW pencere handle'i olan long aliyordu).
        boolean screenOpen = minecraft.screen != null;

        for (KeyAction action : KeyAction.values()) {
            int code = action.getKey();
            boolean pressedNow = code >= 0 && InputConstants.isKeyDown(minecraft.getWindow(), code);
            boolean wasDown = down.contains(action);

            if (pressedNow && !wasDown) {
                down.add(action);

                // MENU TUSU AC/KAPA: Ekran acikken de calisir - bizim ekranimizsa
                // kapatir. Buradan yapiliyor cunku Screen#keyPressed'in tasidigi
                // KeyEvent record'unun alan adlari surumden surume degisiyor;
                // GLFW'yi dogrudan okumak her surumde ayni sekilde calisiyor.
                if (action == KeyAction.MENU) {
                    toggleMenu(minecraft);
                    continue;
                }

                // Diger eylemler ekran acikken tetiklenmez; ama tusun "basildi"
                // durumu yine de kaydedilir ki ekran kapandiginda hayalet
                // tetikleme olmasin.
                if (!screenOpen) fire(action);
            } else if (!pressedNow && wasDown) {
                down.remove(action);
            }
        }
    }

    /**
     * Menu tusu: kapaliysa acar, bizim ekranimiz aciksa kapatir.
     * Bir metin kutusuna yaziliyorsa hicbir sey yapmaz - yoksa "m" harfi yazilamazdi.
     */
    private static void toggleMenu(Minecraft minecraft) {
        if (minecraft.screen == null) {
            minecraft.setScreen(new GoofyScreen());
            return;
        }
        if (minecraft.screen instanceof GoofyScreen screen) {
            if (screen.consumeCapture()) return;
            if (screen.isTyping()) return;
            screen.onClose();
            return;
        }
        if (minecraft.screen instanceof BaseScreen screen) {
            screen.onClose();
        }
        // Baska bir ekran (bazaar, envanter...) aciksa dokunmuyoruz.
    }

    private static void fire(KeyAction action) {
        Minecraft minecraft = Minecraft.getInstance();
        switch (action) {
            case MENU -> {
                // toggleMenu tarafindan islenir.
            }
            case START -> startMacro();
            case PAUSE_RESUME -> togglePause();
            case STOP -> stopMacro();
            case RELOAD_CONFIG -> {
                GoofyConfig.load();
                ChatUtils.clientMessage("Config reloaded from disk.");
            }
            case HUD_MODE -> toggleHudMode();
            case ONLY_SELL -> toggleOnlySell();
        }
    }

    // Arayüzdeki butonlar da aynı yolları kullanır - tek davranış, tek yer.

    public static void startMacro() {
        if (FeatureManager.INSTANCE.isMacroRunning()) {
            ChatUtils.clientMessage("Macro is already running.");
            return;
        }
        FeatureManager.INSTANCE.start("BazaarFlipper");
    }

    public static void stopMacro() {
        if (!FeatureManager.INSTANCE.isMacroRunning()) {
            ChatUtils.clientMessage("Macro is already stopped.");
            return;
        }
        FeatureManager.INSTANCE.stop();
    }

    /** Only Sell modunu ac/kapa - hem tustan hem arayuzdeki anahtardan cagrilir. */
    public static void toggleOnlySell() {
        announceOnlySell(OnlySellMode.toggle());
    }

    /** Arayuzdeki anahtar da ayni mesaji versin diye ayri metot. */
    public static void announceOnlySell(boolean on) {
        ChatUtils.clientMessage(on
                ? "Only Sell ON - no new lines will be opened; finishing what you have."
                : "Only Sell OFF - normal buying resumed.");
        ActionLog.add(ActionLog.Tag.SYSTEM, on ? "only sell mode enabled" : "only sell mode disabled");
    }

    public static void toggleHudMode() {
        EconomyTracker.toggleMode();
        ChatUtils.clientMessage("Profit HUD: " + EconomyTracker.getModeLabel());
    }

    public static void togglePause() {
        if (!FeatureManager.INSTANCE.isMacroRunning()) {
            ChatUtils.clientMessage("Start the macro first.");
            return;
        }
        boolean nowPaused = FeatureManager.INSTANCE.toggleManualPause();
        ChatUtils.clientMessage(nowPaused
                ? "Macro paused - it will continue from where it left off."
                : "Macro resumed.");
    }
}
