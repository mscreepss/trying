package com.goofy.goofyaddons.keybinds;

import com.goofy.goofyaddons.config.GoofyConfig;
import com.goofy.goofyaddons.features.FeatureManager;
import com.goofy.goofyaddons.features.economy.EconomyTracker;
import com.goofy.goofyaddons.ui.GoofyScreen;
import com.goofy.goofyaddons.ui.HudEditScreen;
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
                // Ekran açıkken tetikleme yok; ama tuşun "basıldı" durumunu yine de
                // kaydediyoruz ki ekran kapandığında hayalet tetikleme olmasın.
                if (!screenOpen) fire(action);
            } else if (!pressedNow && wasDown) {
                down.remove(action);
            }
        }
    }

    private static void fire(KeyAction action) {
        Minecraft minecraft = Minecraft.getInstance();
        switch (action) {
            case MENU -> minecraft.setScreen(new GoofyScreen());
            case MOVE_HUD -> minecraft.setScreen(new HudEditScreen());
            case START -> startMacro();
            case PAUSE_RESUME -> togglePause();
            case STOP -> stopMacro();
            case RELOAD_CONFIG -> {
                GoofyConfig.load();
                ChatUtils.clientMessage("Config yeniden yuklendi.");
            }
            case HUD_MODE -> toggleHudMode();
        }
    }

    // Arayüzdeki butonlar da aynı yolları kullanır - tek davranış, tek yer.

    public static void startMacro() {
        if (FeatureManager.INSTANCE.isMacroRunning()) {
            ChatUtils.clientMessage("Makro zaten calisiyor.");
            return;
        }
        FeatureManager.INSTANCE.start("BazaarFlipper");
    }

    public static void stopMacro() {
        if (!FeatureManager.INSTANCE.isMacroRunning()) {
            ChatUtils.clientMessage("Makro zaten durmus durumda.");
            return;
        }
        FeatureManager.INSTANCE.stop();
    }

    public static void toggleHudMode() {
        EconomyTracker.toggleMode();
        ChatUtils.clientMessage("Economy HUD: " + EconomyTracker.getModeLabel() + " modu");
    }

    public static void togglePause() {
        if (!FeatureManager.INSTANCE.isMacroRunning()) {
            ChatUtils.clientMessage("Once makroyu baslatmalisin.");
            return;
        }
        boolean nowPaused = FeatureManager.INSTANCE.toggleManualPause();
        ChatUtils.clientMessage(nowPaused
                ? "Makro duraklatildi - kaldigi yerden devam edecek."
                : "Makro devam ediyor.");
    }
}
