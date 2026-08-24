package com.goofy.goofyaddons;

import com.goofy.goofyaddons.config.BookPresets;
import com.goofy.goofyaddons.config.GoofyConfig;
import com.goofy.goofyaddons.event.ChatHook;
import com.goofy.goofyaddons.failsafes.FailsafeManager;
import com.goofy.goofyaddons.features.FeatureManager;
import com.goofy.goofyaddons.features.bookflipper.helper.ItemCatalog;
import com.goofy.goofyaddons.features.bookflipper.helper.TradeHistory;
import com.goofy.goofyaddons.features.economy.EconomyTracker;
import com.goofy.goofyaddons.keybinds.GoofyKeybinds;
import com.goofy.goofyaddons.keybinds.KeyAction;
import com.goofy.goofyaddons.render.hud.EconomyHud;
import com.goofy.goofyaddons.render.hud.StateHud;
import com.goofy.goofyaddons.render.hud.TaskHud;
import com.goofy.goofyaddons.utils.ActionLog;
import com.goofy.goofyaddons.utils.ChatUtils;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public class GoofyAddonsClient implements ClientModInitializer {

    /**
     * TEŞHİS DAMGASI: Bu satır, oyunda hangi jar'ın çalıştığını kesinleştirir.
     * Dünyaya girince sohbette görünmüyorsa çalışan jar ESKİDİR - yeni kodun
     * hiçbiri devrede değil demektir.
     */
    private static final String BUILD_TAG = "UI-9";

    private static boolean greeted = false;

    @Override
    public void onInitializeClient() {
        System.out.println("[GoofyAddons] client init - build " + BUILD_TAG);

        GoofyConfig.load();
        BookPresets.load();
        ActionLog.init();
        ChatHook.register();
        EconomyTracker.register();
        TradeHistory.register();
        // Uc HUD da kayitli; hangisinin gorunecegi config'ten (HUD sayfasi) okunur.
        EconomyHud.register();
        TaskHud.register();
        StateHud.register();

        // Kitap ID kataloğu: arka planda indirilir, oyunun açılışını bekletmez.
        ItemCatalog.init();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!greeted && client.player != null) {
                greeted = true;
                ChatUtils.clientMessage("GoofyAddons build " + BUILD_TAG + " loaded  |  "
                        + KeyAction.MENU.keyName() + " = open / close menu  |  "
                        + KeyAction.HUD_MODE.keyName() + " = profit HUD mode");
            }

            FailsafeManager.INSTANCE.onTick();
            FeatureManager.INSTANCE.onTick();
            EconomyTracker.onTick();
            TradeHistory.onTick();
            ActionLog.onTick();
            GoofyKeybinds.onTick();
        });
    }
}
