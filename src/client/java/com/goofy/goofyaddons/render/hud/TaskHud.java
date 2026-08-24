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

import java.util.ArrayList;
import java.util.List;

/**
 * Task HUD - eski GoofyOverlay'in yerine geçen, hangi hattın ne yaptığını
 * gösteren panel.
 *
 * ESKİ HÂLİ header.png dokusu basıyor ve "BUY_ORDER (remaining=4)" gibi KOD
 * adları yazıyordu. Artık doku yok (vanilla hissi vermesin) ve satırlar
 * oyuncunun bildiği terimlerle yazılıyor:
 *
 *   Wisdom 1 -> 5  |  buy order: 4  |  12 / 16 in storage
 *
 * "buy order: 4" = hâlâ bazaar'da bekleyen adet.
 * "12 / 16 in storage" = elimizde+depoda olan / o hattın hedefi.
 */
public class TaskHud {

    private static final int MIN_WIDTH = 210;
    private static final int MAX_ROWS = 8;

    private TaskHud() {
    }

    public static void register() {
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(GoofyAddons.MOD_ID, "task_hud"),
                TaskHud::renderElement
        );
    }

    private static void renderElement(GuiGraphicsExtractor graphics, DeltaTracker tickCounter) {
        if (GoofyConfig.INSTANCE == null || !GoofyConfig.INSTANCE.taskHudVisible) return;
        if (Minecraft.getInstance().player == null) return;
        if (Minecraft.getInstance().screen instanceof HudEditScreen) return;

        render(graphics, GoofyConfig.INSTANCE.taskHudX, GoofyConfig.INSTANCE.taskHudY, false);
    }

    // ---------------------------------------------------------------- veri

    private static List<String> lines() {
        List<String> lines = new ArrayList<>();
        if (!(FeatureManager.INSTANCE.getCurrentFeature() instanceof BazaarFlipper flipper)) return lines;

        for (BazaarFlipper.TaskInfo info : flipper.getTaskInfo()) {
            StringBuilder line = new StringBuilder();
            line.append(info.name()).append(' ').append(info.level())
                    .append(" -> ").append(info.sellLevel());
            line.append("  |  ").append(info.phase());
            if (info.onOrder() > 0) line.append(": ").append(info.onOrder());
            line.append("  |  ").append(info.owned()).append(" / ").append(info.target())
                    .append(" in storage");
            lines.add(line.toString());
            if (lines.size() >= MAX_ROWS) break;
        }
        return lines;
    }

    private static String emptyText() {
        if (!FeatureManager.INSTANCE.isMacroRunning()) return "Macro stopped";
        return "No active tasks";
    }

    // ---------------------------------------------------------------- ölçü

    public static int width() {
        int widest = MIN_WIDTH - HudStyle.PAD_X * 2;
        for (String line : lines()) widest = Math.max(widest, Draw.textWidth(line));
        widest = Math.max(widest, Draw.textWidth(emptyText()));
        return widest + HudStyle.PAD_X * 2;
    }

    public static int height() {
        int rows = Math.max(1, lines().size());
        return HudStyle.PAD_Y + HudStyle.HEADER_H + HudStyle.LINE * rows + HudStyle.PAD_Y - 2;
    }

    // ---------------------------------------------------------------- çizim

    public static void render(GuiGraphicsExtractor g, int x, int y, boolean highlight) {
        List<String> lines = lines();
        int w = width();
        int h = height();

        boolean running = FeatureManager.INSTANCE.isMacroRunning();
        boolean paused = FeatureManager.INSTANCE.isPaused();
        int accent = !running ? Theme.TEXT_FAINT : (paused ? Theme.YELLOW : Theme.ACCENT);

        HudStyle.panel(g, x, y, w, h, accent, highlight);
        HudStyle.header(g, x, y, w, "TASKS",
                lines.isEmpty() ? "" : String.valueOf(lines.size()), Theme.TEXT_DIM);

        int left = x + HudStyle.PAD_X;
        int ty = y + HudStyle.PAD_Y + HudStyle.HEADER_H + 2;

        if (lines.isEmpty()) {
            Draw.text(g, emptyText(), left, ty, Theme.TEXT_FAINT);
            return;
        }

        for (String line : lines) {
            Draw.text(g, Draw.clip(line, w - HudStyle.PAD_X * 2), left, ty, Theme.TEXT_DIM);
            ty += HudStyle.LINE;
        }
    }
}
