package com.goofy.goofyaddons.render.hud;

import com.goofy.goofyaddons.GoofyAddons;
import com.goofy.goofyaddons.config.GoofyConfig;
import com.goofy.goofyaddons.features.FeatureManager;
import com.goofy.goofyaddons.features.economy.EconomyTracker;
import com.goofy.goofyaddons.keybinds.KeyAction;
import com.goofy.goofyaddons.ui.Draw;
import com.goofy.goofyaddons.ui.HudEditScreen;
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
 * Profit HUD - harcama / kazanç / kâr / süre.
 *
 * Genişlik içeriğe göre hesaplanır, yani rakamlar büyüdükçe panel de büyür;
 * sayılar asla kırpılmaz. Konum ve açık/kapalı durumu config'ten okunur, HUD
 * taşıma ekranından sürüklenerek ayarlanır.
 */
public class EconomyHud {

    private static final NumberFormat FORMAT = NumberFormat.getNumberInstance(Locale.US);

    static {
        FORMAT.setMaximumFractionDigits(0);
    }

    private static final int LINES = 4;
    private static final int MIN_WIDTH = 168;
    private static final int GAP = 18;

    public static final int HEIGHT =
            HudStyle.PAD_Y + HudStyle.HEADER_H + HudStyle.LINE * LINES + HudStyle.PAD_Y - 2;

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
        // Taşıma ekranı kendi önizlemesini çiziyor, iki kez çizmeyelim.
        if (Minecraft.getInstance().screen instanceof HudEditScreen) return;

        render(graphics, GoofyConfig.INSTANCE.hudX, GoofyConfig.INSTANCE.hudY, false);
    }

    /** İçeriğe göre panel genişliği - HUD taşıma ekranı da bunu kullanır. */
    public static int width() {
        int widest = MIN_WIDTH - HudStyle.PAD_X * 2;
        widest = Math.max(widest, Draw.textWidth("PROFIT  " + EconomyTracker.getModeLabel())
                + 12 + Draw.textWidth("[" + KeyAction.HUD_MODE.keyName() + "]"));
        widest = Math.max(widest, rowWidth("Spend", FORMAT.format(EconomyTracker.getSpend())));
        widest = Math.max(widest, rowWidth("Earn", FORMAT.format(EconomyTracker.getEarn())));
        widest = Math.max(widest, rowWidth("Profit", FORMAT.format(EconomyTracker.getProfit())));
        widest = Math.max(widest, rowWidth("Uptime", uptimeText()));
        return widest + HudStyle.PAD_X * 2;
    }

    public static int height() {
        return HEIGHT;
    }

    private static int rowWidth(String label, String value) {
        return Draw.textWidth(label) + GAP + Draw.textWidth(value);
    }

    private static String uptimeText() {
        String base = EconomyTracker.formatUptime(EconomyTracker.getUptimeMs());
        if (EconomyTracker.isCountingUptime()) return base;
        return base + (FeatureManager.INSTANCE.isMacroRunning() ? " (paused)" : " (stopped)");
    }

    /** HUD'u verilen konuma çizer. highlight = taşıma modunda vurgu çerçevesi. */
    public static void render(GuiGraphicsExtractor g, int x, int y, boolean highlight) {
        int w = width();
        double profit = EconomyTracker.getProfit();
        boolean counting = EconomyTracker.isCountingUptime();
        int accent = profit >= 0 ? Theme.GREEN : Theme.RED;

        HudStyle.panel(g, x, y, w, HEIGHT, accent, highlight);
        HudStyle.header(g, x, y, w, "PROFIT  " + EconomyTracker.getModeLabel().toUpperCase(Locale.US),
                "[" + KeyAction.HUD_MODE.keyName() + "]", Theme.TEXT_DIM);

        int left = x + HudStyle.PAD_X;
        int right = x + w - HudStyle.PAD_X;
        int ty = y + HudStyle.PAD_Y + HudStyle.HEADER_H + 2;

        HudStyle.row(g, left, right, ty, "Spend", FORMAT.format(EconomyTracker.getSpend()),
                Theme.TEXT_DIM, Theme.RED);
        ty += HudStyle.LINE;
        HudStyle.row(g, left, right, ty, "Earn", FORMAT.format(EconomyTracker.getEarn()),
                Theme.TEXT_DIM, Theme.GREEN);
        ty += HudStyle.LINE;
        HudStyle.row(g, left, right, ty, "Profit", (profit >= 0 ? "+" : "") + FORMAT.format(profit),
                Theme.TEXT, accent);
        ty += HudStyle.LINE;
        HudStyle.row(g, left, right, ty, "Uptime", uptimeText(),
                Theme.TEXT_DIM, counting ? Theme.TEXT : Theme.TEXT_FAINT);
    }
}
