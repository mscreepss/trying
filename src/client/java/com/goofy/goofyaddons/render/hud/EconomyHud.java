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
 * Profit HUD.
 *
 * Eski HUD'un okunaklı düzeni (mod başlığı + Spend/Earn/Profit + Uptime) korundu;
 * değişen yalnızca çerçeve: koyu panel, sol tarafta kâr durumuna göre renk şeridi,
 * hizalanmış sayılar. Genişlik içeriğe göre hesaplanır, yani rakamlar büyüdükçe
 * panel de büyür - sayılar asla kırpılmaz.
 *
 * Mod değiştirme tuşu (varsayılan V) başlıkta gösterilir.
 */
public class EconomyHud {

    private static final NumberFormat FORMAT = NumberFormat.getNumberInstance(Locale.US);

    static {
        FORMAT.setMaximumFractionDigits(0);
    }

    private static final int LINE = 11;
    private static final int PAD_X = 8;
    private static final int PAD_Y = 6;
    private static final int LINES = 5;
    private static final int MIN_WIDTH = 150;
    private static final int GAP = 14; // etiket ile sayı arası en az boşluk

    public static final int HEIGHT = PAD_Y * 2 + LINE * LINES - 3;

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
        int widest = MIN_WIDTH - PAD_X * 2;
        widest = Math.max(widest, Draw.textWidth(modeLine()));
        widest = Math.max(widest, rowWidth("Spend", FORMAT.format(EconomyTracker.getSpend())));
        widest = Math.max(widest, rowWidth("Earn", FORMAT.format(EconomyTracker.getEarn())));
        widest = Math.max(widest, rowWidth("Profit", FORMAT.format(EconomyTracker.getProfit())));
        widest = Math.max(widest, rowWidth("Uptime", uptimeText()));
        return widest + PAD_X * 2;
    }

    private static int rowWidth(String label, String value) {
        return Draw.textWidth(label) + GAP + Draw.textWidth(value);
    }

    private static String modeLine() {
        return "Economy: " + EconomyTracker.getModeLabel() + "  [" + KeyAction.HUD_MODE.keyName() + "]";
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
        boolean running = FeatureManager.INSTANCE.isMacroRunning();
        boolean session = EconomyTracker.getMode() == EconomyTracker.Mode.SESSION;

        int accent = profit >= 0 ? Theme.GREEN : Theme.RED;

        Draw.panel(g, x, y, w, HEIGHT, Theme.HUD_BG, highlight ? Theme.ACCENT : Theme.HUD_STROKE);
        Draw.rect(g, x + 1, y + 2, 2, HEIGHT - 4, accent);

        int left = x + PAD_X;
        int right = x + w - PAD_X;
        int ty = y + PAD_Y;

        // 1) mod başlığı + tuş ipucu + durum ışığı
        String modeLabel = "Economy: " + EconomyTracker.getModeLabel();
        Draw.text(g, modeLabel, left, ty, session ? Theme.ACCENT : Theme.YELLOW);
        Draw.text(g, "  [" + KeyAction.HUD_MODE.keyName() + "]",
                left + Draw.textWidth(modeLabel), ty, Theme.TEXT_FAINT);
        Draw.dot(g, right - 4, ty + 2, !running ? Theme.TEXT_FAINT : (counting ? Theme.GREEN : Theme.YELLOW));
        ty += LINE + 2;

        // 2-4) para satırları
        row(g, left, right, ty, "Spend", FORMAT.format(EconomyTracker.getSpend()), Theme.TEXT_DIM, Theme.RED);
        ty += LINE;
        row(g, left, right, ty, "Earn", FORMAT.format(EconomyTracker.getEarn()), Theme.TEXT_DIM, Theme.GREEN);
        ty += LINE;
        row(g, left, right, ty, "Profit", (profit >= 0 ? "+" : "") + FORMAT.format(profit), Theme.TEXT, accent);
        ty += LINE;

        // 5) uptime
        row(g, left, right, ty, "Uptime", uptimeText(), Theme.TEXT_DIM, counting ? Theme.TEXT : Theme.TEXT_FAINT);
    }

    private static void row(GuiGraphicsExtractor g, int left, int right, int y,
                            String label, String value, int labelColor, int valueColor) {
        Draw.text(g, label, left, y, labelColor);
        Draw.textRight(g, value, right, y, valueColor);
    }
}
