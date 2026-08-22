package com.goofy.goofyaddons.render.hud;

import com.goofy.goofyaddons.GoofyAddons;
import com.goofy.goofyaddons.config.GoofyConfig;
import com.goofy.goofyaddons.features.FeatureManager;
import com.goofy.goofyaddons.features.economy.EconomyTracker;
import com.goofy.goofyaddons.ui.Draw;
import com.goofy.goofyaddons.ui.Theme;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * Profit HUD - yeniden tasarlandı.
 *
 * Tek bir koyu kart: üstte durum ışığı + mod etiketi, ortada büyük PROFIT rakamı,
 * altta spend/earn ve uptime. Konum config'ten (hudX/hudY) okunur, Move HUD
 * ekranından sürükle-bırak ile değiştirilir.
 */
public class EconomyHud {

    private static final NumberFormat FORMAT = NumberFormat.getNumberInstance(Locale.US);

    static {
        FORMAT.setMaximumFractionDigits(0);
    }

    public static final int WIDTH = 132;
    public static final int HEIGHT = 62;

    private EconomyHud() {
    }

    public static void register() {
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(GoofyAddons.MOD_ID, "economy_hud"),
                EconomyHud::renderElement
        );
    }

    private static void renderElement(GuiGraphicsExtractor graphics, DeltaTracker tickCounter) {
        if (GoofyConfig.INSTANCE == null || !GoofyConfig.INSTANCE.hudVisible) return;
        if (Minecraft.getInstance().player == null) return;
        // Move HUD ekranı kendi önizlemesini çizer, iki kez çizmeyelim.
        if (Minecraft.getInstance().screen instanceof com.goofy.goofyaddons.ui.HudEditScreen) return;

        render(graphics, GoofyConfig.INSTANCE.hudX, GoofyConfig.INSTANCE.hudY, false);
    }

    /**
     * HUD'u verilen konuma çizer. Move HUD ekranı da bunu çağırır (highlight = true
     * iken taşınabilir olduğunu belli eden bir çerçeve eklenir).
     */
    public static void render(GuiGraphicsExtractor g, int x, int y, boolean highlight) {
        boolean counting = EconomyTracker.isCountingUptime();
        boolean running = FeatureManager.INSTANCE.isMacroRunning();
        boolean paused = FeatureManager.INSTANCE.isPaused();

        double profit = EconomyTracker.getProfit();
        int accent = profit >= 0 ? Theme.GREEN : Theme.RED;

        // gövde
        Draw.panel(g, x, y, WIDTH, HEIGHT, Theme.HUD_BG, highlight ? Theme.ACCENT : Theme.HUD_STROKE);
        // sol kenarda ince vurgu şeridi - kâr pozitifse yeşil, negatifse kırmızı
        Draw.rect(g, x + 1, y + 2, 2, HEIGHT - 4, accent);

        int px = x + 9;
        int py = y + 6;

        // --- üst satır: durum ışığı + mod ---
        int statusColor = !running ? Theme.TEXT_FAINT : (paused ? Theme.YELLOW : Theme.GREEN);
        Draw.dot(g, px, py + 2, statusColor);
        Draw.text(g, EconomyTracker.getModeLabel().toUpperCase(), px + 9, py, Theme.TEXT_DIM);
        Draw.textRight(g, counting ? "LIVE" : (running ? "PAUSED" : "IDLE"),
                x + WIDTH - 8, py, counting ? Theme.ACCENT : Theme.TEXT_FAINT);

        // --- ana rakam ---
        py += 13;
        String profitText = (profit >= 0 ? "+" : "") + FORMAT.format(profit);
        Draw.text(g, profitText, px, py, accent);

        // --- alt: spend / earn ---
        py += 13;
        Draw.text(g, "SPEND", px, py, Theme.TEXT_FAINT);
        Draw.textRight(g, FORMAT.format(EconomyTracker.getSpend()), x + WIDTH - 8, py, Theme.TEXT_DIM);

        py += 10;
        Draw.text(g, "EARN", px, py, Theme.TEXT_FAINT);
        Draw.textRight(g, FORMAT.format(EconomyTracker.getEarn()), x + WIDTH - 8, py, Theme.TEXT_DIM);

        // --- uptime ---
        py += 10;
        Draw.text(g, "UPTIME", px, py, Theme.TEXT_FAINT);
        Draw.textRight(g, EconomyTracker.formatUptime(EconomyTracker.getUptimeMs()),
                x + WIDTH - 8, py, counting ? Theme.TEXT : Theme.TEXT_FAINT);
    }
}
