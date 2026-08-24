package com.goofy.goofyaddons.render.hud;

import com.goofy.goofyaddons.GoofyAddons;
import com.goofy.goofyaddons.config.GoofyConfig;
import com.goofy.goofyaddons.features.FeatureManager;
import com.goofy.goofyaddons.features.bookflipper.BazaarFlipper;
import com.goofy.goofyaddons.ui.Draw;
import com.goofy.goofyaddons.ui.HudEditScreen;
import com.goofy.goofyaddons.ui.Theme;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/**
 * State HUD - tek satırlık, en sade HUD. Makronun ŞU AN ne yaptığını söyler.
 *
 *   ● Anvil                         Wisdom 1
 *
 * Işık: yeşil çalışıyor, sarı duraklatıldı, soluk durdu.
 * Sağdaki metin o an üzerinde çalışılan kitap (yoksa boş).
 */
public class StateHud {

    private static final int MIN_WIDTH = 132;
    private static final int GAP = 16;

    public static final int HEIGHT = 22;

    private StateHud() {
    }

    public static void register() {
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(GoofyAddons.MOD_ID, "state_hud"),
                StateHud::renderElement
        );
    }

    private static void renderElement(GuiGraphicsExtractor graphics, DeltaTracker tickCounter) {
        if (GoofyConfig.INSTANCE == null || !GoofyConfig.INSTANCE.stateHudVisible) return;
        if (Minecraft.getInstance().player == null) return;
        if (Minecraft.getInstance().screen instanceof HudEditScreen) return;

        render(graphics, GoofyConfig.INSTANCE.stateHudX, GoofyConfig.INSTANCE.stateHudY, false);
    }

    private static String stateText() {
        if (!FeatureManager.INSTANCE.isMacroRunning()) return "Stopped";
        if (FeatureManager.INSTANCE.isPaused()) return "Paused";
        if (FeatureManager.INSTANCE.getCurrentFeature() instanceof BazaarFlipper flipper) {
            return flipper.getFriendlyState();
        }
        return "Idle";
    }

    private static String bookText() {
        if (!(FeatureManager.INSTANCE.getCurrentFeature() instanceof BazaarFlipper flipper)) return "";
        String book = flipper.getActiveBookName();
        return book == null || book.equals("-") ? "" : book;
    }

    public static int width() {
        int content = Draw.textWidth(stateText()) + 12;
        String book = bookText();
        if (!book.isEmpty()) content += GAP + Draw.textWidth(book);
        return Math.max(MIN_WIDTH, content + HudStyle.PAD_X * 2);
    }

    public static int height() {
        return HEIGHT;
    }

    public static void render(GuiGraphicsExtractor g, int x, int y, boolean highlight) {
        boolean running = FeatureManager.INSTANCE.isMacroRunning();
        boolean paused = FeatureManager.INSTANCE.isPaused();
        int accent = !running ? Theme.TEXT_FAINT : (paused ? Theme.YELLOW : Theme.GREEN);

        int w = width();
        HudStyle.panel(g, x, y, w, HEIGHT, accent, highlight);

        int left = x + HudStyle.PAD_X;
        int textY = y + (HEIGHT - 8) / 2;

        Draw.dot(g, left, textY + 2, accent);
        Draw.text(g, stateText(), left + 12, textY, running ? Theme.TEXT : Theme.TEXT_DIM);

        String book = bookText();
        if (!book.isEmpty()) {
            Draw.textRight(g, book, x + w - HudStyle.PAD_X, textY, Theme.TEXT_FAINT);
        }
    }
}
