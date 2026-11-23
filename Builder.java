package com.example.buildassist;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayDeque;

/** Queues vanilla commands (/fill, /setblock, /attribute) and sends them a few per tick so you never get spam-kicked. */
public final class Builder {
    public static final String[] BLOCKS = {"stone_bricks", "deepslate_bricks", "obsidian", "glass", "oak_planks",
            "bricks", "polished_andesite", "smooth_stone", "cobblestone", "dirt", "iron_block"};
    public static final String[] FLOORS = {"lava", "water", "magma_block", "powder_snow", "cobweb"};
    private static final ArrayDeque<String> QUEUE = new ArrayDeque<>();
    private static final int MAX_FILL = 32768; // vanilla commandModificationBlockLimit default

    private Builder() {}

    public static void queue(String cmd) { QUEUE.add(cmd); }

    public static void tick(MinecraftClient mc) {
        for (int n = 0; n < 3 && !QUEUE.isEmpty(); n++) {
            if (mc.player == null) { QUEUE.clear(); return; }
            mc.player.networkHandler.sendChatCommand(QUEUE.poll());
        }
    }

    private static void say(String s) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) mc.player.sendMessage(Text.literal(s), true);
    }

    /** Local coordinate frame: i = right, j = up, k = forward, origin = your feet block. */
    private static final class Ctx {
        final BlockPos o; final Direction f, r;
        Ctx(BlockPos o, Direction f) { this.o = o; this.f = f; this.r = f.rotateYClockwise(); }
        static Ctx get() {
            var p = MinecraftClient.getInstance().player;
            return p == null ? null : new Ctx(p.getBlockPos(), p.getHorizontalFacing());
        }
        BlockPos at(int i, int j, int k) { return o.offset(f, k).offset(r, i).up(j); }
        static String xyz(BlockPos p) { return p.getX() + " " + p.getY() + " " + p.getZ(); }
        void fill(int i1, int j1, int k1, int i2, int j2, int k2, String what) {
            queue("fill " + xyz(at(i1, j1, k1)) + " " + xyz(at(i2, j2, k2)) + " " + what);
        }
        void set(int i, int j, int k, String block) { queue("setblock " + xyz(at(i, j, k)) + " " + block); }
    }

    public static void room(int w, int h, int d, String block) {
        Ctx c = Ctx.get(); if (c == null) return;
        if ((long) (w + 2) * (h + 2) * (d + 2) > MAX_FILL) { say("Room too big"); return; }
        int i0 = -((w + 1) / 2), i1 = i0 + w + 1;
        c.fill(i0, -1, 1, i1, h, d + 2, "minecraft:" + block + " hollow");
        c.fill(0, 0, 1, 0, 1, 1, "minecraft:air");                         // doorway
        c.set((i0 + i1) / 2, h, (d + 3) / 2, "minecraft:sea_lantern");      // ceiling light
        say("Building room...");
    }

    public static void tunnel(int w, int h, int len) {
        Ctx c = Ctx.get(); if (c == null) return;
        if ((long) w * h * len > MAX_FILL) { say("Tunnel too big"); return; }
        int i0 = -(w / 2);
        c.fill(i0, 0, 1, i0 + w - 1, h - 1, len, "minecraft:air");
        say("Digging tunnel...");
    }

    public static void stairs(int steps) {
        Ctx c = Ctx.get(); if (c == null) return;
        String stair = "minecraft:stone_brick_stairs[facing=" + c.f.getOpposite().asString() + "]";
        for (int n = 1; n <= steps; n++) {
            c.fill(-1, -n + 1, n, 1, -n + 3, n, "minecraft:air");
            c.fill(-1, -n, n, 1, -n, n, stair);
        }
        say("Digging staircase...");
    }

    public static void pit(int depth, String floor) {
        Ctx c = Ctx.get(); if (c == null) return;
        c.fill(-1, -depth, 2, 1, -1, 4, "minecraft:air");
        c.fill(-1, -depth - 1, 2, 1, -depth - 1, 4, "minecraft:" + floor);
        say("Digging pit trap...");
    }
}
