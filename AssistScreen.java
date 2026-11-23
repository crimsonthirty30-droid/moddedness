package com.example.buildassist;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

public class AssistScreen extends Screen {
    private record Hdr(String text, int x, int y, int w) {}

    private final List<Hdr> headers = new ArrayList<>();
    private int px, py;
    private static final int PW = 372, PH = 316, CW = 168;

    public AssistScreen() { super(Text.literal("Build Assist")); }

    // ---------------------------------------------------------------- widgets
    private static class Slide extends SliderWidget {
        private final String label, unit; private final double min, max, step; private final DoubleConsumer set;

        Slide(int x, int y, int w, String label, double min, double max, double step, double cur, String unit, DoubleConsumer set) {
            super(x, y, w, 20, Text.empty(), (cur - min) / (max - min));
            this.label = label; this.unit = unit; this.min = min; this.max = max; this.step = step; this.set = set;
            updateMessage();
        }
        private double val() {
            double v = min + value * (max - min);
            return Math.max(min, Math.min(max, Math.round(v / step) * step));
        }
        @Override protected void updateMessage() {
            if (label == null) return;
            double v = val();
            String s = step >= 1 ? String.valueOf((int) v) : String.format(Locale.ROOT, "%.1f", v);
            setMessage(Text.literal(label + ": " + s + unit));
        }
        @Override protected void applyValue() { set.accept(val()); }
    }

    private static Text onOff(String name, boolean on) {
        return Text.literal(name + ": ").append(Text.literal(on ? "ON" : "OFF").formatted(on ? Formatting.GREEN : Formatting.RED));
    }

    private ButtonWidget toggle(int x, int y, int w, String name, BooleanSupplier get, Consumer<Boolean> set) {
        return ButtonWidget.builder(onOff(name, get.getAsBoolean()), b -> {
            set.accept(!get.getAsBoolean());
            b.setMessage(onOff(name, get.getAsBoolean()));
        }).dimensions(x, y, w, 20).build();
    }

    private void hdr(String t, int x, int y, int w) { headers.add(new Hdr(t, x, y, w)); }

    // ---------------------------------------------------------------- layout
    @Override
    protected void init() {
        headers.clear();
        px = (width - PW) / 2; py = (height - PH) / 2;
        int x1 = px + 12, x2 = px + 12 + CW + 12, y0 = py + 28;

        // ----- left column
        hdr("FLIGHT", x1, y0, CW);
        addDrawableChild(toggle(x1, y0 + 12, CW, "Fast flight", () -> Config.flyEnabled, v -> Config.flyEnabled = v));
        addDrawableChild(new Slide(x1, y0 + 34, CW, "Speed", 1, 20, 0.5, Config.flyMult, "x", v -> Config.flyMult = v));

        hdr("REACH", x1, y0 + 60, CW);
        addDrawableChild(new Slide(x1, y0 + 72, CW, "Block reach", 1, 64, 0.5, Config.blockReach, "", v -> { Config.blockReach = v; BuildAssistClient.scheduleApply(); }));
        addDrawableChild(new Slide(x1, y0 + 94, CW, "Entity reach", 1, 64, 0.5, Config.entityReach, "", v -> { Config.entityReach = v; BuildAssistClient.scheduleApply(); }));

        hdr("KNOCKBACK", x1, y0 + 120, CW);
        addDrawableChild(new Slide(x1, y0 + 132, CW, "Resistance", 0, 100, 5, Config.knockback * 100, "%", v -> { Config.knockback = v / 100.0; BuildAssistClient.scheduleApply(); }));
        addDrawableChild(ButtonWidget.builder(Text.literal("Reset reach & knockback"), b -> {
            Config.blockReach = 4.5; Config.entityReach = 3; Config.knockback = 0;
            BuildAssistClient.scheduleApply();
            MinecraftClient.getInstance().setScreen(new AssistScreen());
        }).dimensions(x1, y0 + 154, CW, 20).build());

        // ----- right column
        hdr("TRACKERS & FINDERS", x2, y0, CW);
        addDrawableChild(toggle(x2, y0 + 12, CW, "Players", () -> Config.players, v -> Config.players = v));
        addDrawableChild(toggle(x2, y0 + 34, CW, "Chests / Ender", () -> Config.chests, v -> { Config.chests = v; if (v) BuildAssistClient.requestScan(); }));
        addDrawableChild(toggle(x2, y0 + 56, CW, "Artificial lights", () -> Config.lights, v -> { Config.lights = v; if (v) BuildAssistClient.requestScan(); }));
        addDrawableChild(toggle(x2, y0 + 78, CW, "Aim assist (always)", () -> Config.aimAlways, v -> Config.aimAlways = v));

        hdr("HIT-SWAP (no timing needed)", x2, y0 + 104, CW);
        addDrawableChild(toggle(x2, y0 + 116, CW, "Hit-swap", () -> Config.swapEnabled, v -> Config.swapEnabled = v));
        addDrawableChild(ButtonWidget.builder(Text.literal("Swap to slot: " + Config.swapSlot), b -> {
            Config.swapSlot = Config.swapSlot % 9 + 1;
            b.setMessage(Text.literal("Swap to slot: " + Config.swapSlot));
        }).dimensions(x2, y0 + 138, CW, 20).build());
        addDrawableChild(toggle(x2, y0 + 160, CW, "Players only", () -> Config.swapPlayersOnly, v -> Config.swapPlayersOnly = v));

        // ----- builder (full width)
        int y1 = y0 + 188, fw = PW - 24;
        hdr("BUILDER (needs op / cheats)", x1, y1, fw);
        int sw = 112;
        addDrawableChild(new Slide(x1, y1 + 12, sw, "Width", 1, 30, 1, Config.bw, "", v -> Config.bw = (int) v));
        addDrawableChild(new Slide(x1 + sw + 6, y1 + 12, sw, "Height", 1, 30, 1, Config.bh, "", v -> Config.bh = (int) v));
        addDrawableChild(new Slide(x1 + 2 * (sw + 6), y1 + 12, sw, "Length", 1, 30, 1, Config.bl, "", v -> Config.bl = (int) v));

        addDrawableChild(ButtonWidget.builder(Text.literal("Block: " + Builder.BLOCKS[Config.blockIdx]), b -> {
            Config.blockIdx = (Config.blockIdx + 1) % Builder.BLOCKS.length;
            b.setMessage(Text.literal("Block: " + Builder.BLOCKS[Config.blockIdx]));
        }).dimensions(x1, y1 + 34, CW, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Pit floor: " + Builder.FLOORS[Config.floorIdx]), b -> {
            Config.floorIdx = (Config.floorIdx + 1) % Builder.FLOORS.length;
            b.setMessage(Text.literal("Pit floor: " + Builder.FLOORS[Config.floorIdx]));
        }).dimensions(x2, y1 + 34, CW, 20).build());

        int bw = 84, gap = 4, by = y1 + 56;
        addDrawableChild(ButtonWidget.builder(Text.literal("Room"), b -> { Builder.room(Config.bw, Config.bh, Config.bl, Builder.BLOCKS[Config.blockIdx]); close(); })
                .dimensions(x1, by, bw, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Tunnel"), b -> { Builder.tunnel(Config.bw, Config.bh, Config.bl); close(); })
                .dimensions(x1 + (bw + gap), by, bw, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Stairs"), b -> { Builder.stairs(Config.bl); close(); })
                .dimensions(x1 + 2 * (bw + gap), by, bw, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Pit trap"), b -> { Builder.pit(Math.max(2, Config.bh), Builder.FLOORS[Config.floorIdx]); close(); })
                .dimensions(x1 + 3 * (bw + gap), by, bw, 20).build());
    }

    // ---------------------------------------------------------------- drawing
    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.renderBackground(ctx, mouseX, mouseY, delta);
        ctx.fill(px - 1, py - 1, px + PW + 1, py + PH + 1, 0xFF3A7BFF);   // border
        ctx.fill(px, py, px + PW, py + PH, 0xF0101420);                    // panel
        ctx.fill(px, py, px + PW, py + 22, 0xFF1B2A4A);                    // title bar
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("BUILD ASSIST"), px + PW / 2, py + 7, 0xFF55FFFF);
        for (Hdr h : headers) {
            ctx.drawTextWithShadow(textRenderer, h.text(), h.x(), h.y(), 0xFFFFAA00);
            ctx.fill(h.x(), h.y() + 9, h.x() + h.w(), h.y() + 10, 0x55FFFFFF);
        }
        ctx.drawTextWithShadow(textRenderer, "Room = W x H x L   Tunnel = W x H x L   Stairs = L steps   Pit = H deep",
                px + 12, py + PH - 14, 0xFF888888);
    }

    @Override public boolean shouldPause() { return false; }

    @Override public void removed() { Config.save(); }
}
