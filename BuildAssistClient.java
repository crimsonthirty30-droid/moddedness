package com.example.buildassist;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.lwjgl.glfw.GLFW;

import java.util.*;

public class BuildAssistClient implements ClientModInitializer {
    private record Marker(Vec3d pos, String label, int color) {}

    private static final KeyBinding.Category CATEGORY = KeyBinding.Category.create(Identifier.of("buildassist", "main"));
    private static KeyBinding kMenu, kPlayers, kChests, kLights, kAim;

    private static List<Marker> chestMarks = new ArrayList<>();
    private static List<Marker> lightMarks = new ArrayList<>();
    private static int tickCount = 0, applyTimer = 0, restoreSlot = -1;
    private static boolean scanNow = false, flyApplied = false;

    private static final int CHEST_RANGE_CHUNKS = 8, LIGHT_RANGE_CHUNKS = 4, MAX_MARKERS = 60;

    /** Natural light sources that the artificial-light finder ignores. */
    private static final Set<Block> NATURAL = Set.of(
            Blocks.LAVA, Blocks.MAGMA_BLOCK, Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.GLOW_LICHEN,
            Blocks.AMETHYST_CLUSTER, Blocks.LARGE_AMETHYST_BUD, Blocks.MEDIUM_AMETHYST_BUD, Blocks.SMALL_AMETHYST_BUD,
            Blocks.SCULK_CATALYST, Blocks.CAVE_VINES, Blocks.CAVE_VINES_PLANT, Blocks.GLOWSTONE,
            Blocks.SHROOMLIGHT, Blocks.NETHER_PORTAL, Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE,
            Blocks.CRYING_OBSIDIAN, Blocks.END_PORTAL, Blocks.END_PORTAL_FRAME, Blocks.END_ROD,
            Blocks.BEACON, Blocks.CONDUIT, Blocks.OCHRE_FROGLIGHT, Blocks.VERDANT_FROGLIGHT, Blocks.PEARLESCENT_FROGLIGHT);

    @Override
    public void onInitializeClient() {
        Config.load();
        kMenu = key("menu", GLFW.GLFW_KEY_RIGHT_SHIFT);
        kPlayers = key("players", GLFW.GLFW_KEY_G);
        kChests = key("chests", GLFW.GLFW_KEY_H);
        kLights = key("lights", GLFW.GLFW_KEY_J);
        kAim = key("aim", GLFW.GLFW_KEY_V);

        ClientTickEvents.END_CLIENT_TICK.register(BuildAssistClient::tick);
        HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS,
                Identifier.of("buildassist", "markers"), (ctx, tc) -> render(ctx, tc.getTickProgress(false)));

        // Hit-swap: on every attack, the selected slot is switched to the chosen slot in the SAME tick as the hit
        // (slot packet -> attack packet), then switched back at the end of the tick. No timing needed.
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (!world.isClient() || player != mc.player || !Config.swapEnabled || hand != Hand.MAIN_HAND)
                return ActionResult.PASS;
            if (Config.swapPlayersOnly && !(entity instanceof PlayerEntity)) return ActionResult.PASS;
            int target = Config.swapSlot - 1, cur = player.getInventory().getSelectedSlot();
            if (target != cur && restoreSlot < 0) {
                restoreSlot = cur;
                player.getInventory().setSelectedSlot(target);
            }
            return ActionResult.PASS;
        });
    }

    private static KeyBinding key(String name, int code) {
        return KeyBindingHelper.registerKeyBinding(new KeyBinding("key.buildassist." + name, InputUtil.Type.KEYSYM, code, CATEGORY));
    }

    // called by the menu
    public static void scheduleApply() { applyTimer = 10; }
    public static void requestScan() { scanNow = true; }

    private static void applyAttributes() {
        Builder.queue("attribute @s minecraft:block_interaction_range base set " + fmt(Config.blockReach));
        Builder.queue("attribute @s minecraft:entity_interaction_range base set " + fmt(Config.entityReach));
        Builder.queue("attribute @s minecraft:knockback_resistance base set " + fmt(Config.knockback));
    }
    private static String fmt(double v) { return String.format(Locale.ROOT, "%.2f", v); }

    // ------------------------------------------------------------ tick
    private static void tick(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) return;

        // finish a hit-swap
        if (restoreSlot >= 0) { mc.player.getInventory().setSelectedSlot(restoreSlot); restoreSlot = -1; }

        while (kMenu.wasPressed()) mc.setScreen(new AssistScreen());
        while (kPlayers.wasPressed()) { Config.players = !Config.players; toast(mc, "Player tracker", Config.players); Config.save(); }
        while (kChests.wasPressed()) { Config.chests = !Config.chests; toast(mc, "Chest finder", Config.chests); scanNow |= Config.chests; Config.save(); }
        while (kLights.wasPressed()) { Config.lights = !Config.lights; toast(mc, "Light finder", Config.lights); scanNow |= Config.lights; Config.save(); }

        if (applyTimer > 0 && --applyTimer == 0) applyAttributes();
        Builder.tick(mc);

        // fast flight (client side)
        var abil = mc.player.getAbilities();
        if (Config.flyEnabled && abil.allowFlying) {
            float want = (float) (0.05 * Config.flyMult);
            if (abil.getFlySpeed() != want) abil.setFlySpeed(want);
            flyApplied = true;
        } else if (flyApplied) { abil.setFlySpeed(0.05f); flyApplied = false; }

        tickCount++;
        if (Config.chests && (scanNow || tickCount % 20 == 0)) scanChests(mc);
        if (Config.lights && (scanNow || tickCount % 40 == 0)) scanLights(mc);
        scanNow = false;

        if ((kAim.isPressed() || Config.aimAlways) && mc.currentScreen == null) aim(mc);
    }

    private static void toast(MinecraftClient mc, String name, boolean on) {
        mc.player.sendMessage(Text.literal(name + ": " + (on ? "ON" : "OFF")), true);
    }

    // ------------------------------------------------------------ chest finder
    private static void scanChests(MinecraftClient mc) {
        List<Marker> out = new ArrayList<>();
        ChunkPos cp = mc.player.getChunkPos();
        for (int dx = -CHEST_RANGE_CHUNKS; dx <= CHEST_RANGE_CHUNKS; dx++)
            for (int dz = -CHEST_RANGE_CHUNKS; dz <= CHEST_RANGE_CHUNKS; dz++) {
                WorldChunk ch = mc.world.getChunkManager().getWorldChunk(cp.x + dx, cp.z + dz);
                if (ch == null) continue;
                for (BlockEntity be : ch.getBlockEntities().values()) {
                    String n; int col;
                    if (be instanceof EnderChestBlockEntity) { n = "Ender Chest"; col = 0xFFB066FF; }
                    else if (be instanceof ChestBlockEntity) { n = "Chest"; col = 0xFFFFAA00; }
                    else if (be instanceof BarrelBlockEntity) { n = "Barrel"; col = 0xFFC08040; }
                    else if (be instanceof ShulkerBoxBlockEntity) { n = "Shulker"; col = 0xFFFF77CC; }
                    else continue;
                    out.add(new Marker(Vec3d.ofCenter(be.getPos()), n, col));
                }
            }
        chestMarks = nearest(out, mc);
    }

    // ------------------------------------------------------------ artificial light finder
    private static void scanLights(MinecraftClient mc) {
        Map<Long, Marker> cells = new HashMap<>();
        Map<Long, Integer> counts = new HashMap<>();
        ChunkPos cp = mc.player.getChunkPos();
        int py = mc.player.getBlockY();
        for (int dx = -LIGHT_RANGE_CHUNKS; dx <= LIGHT_RANGE_CHUNKS; dx++)
            for (int dz = -LIGHT_RANGE_CHUNKS; dz <= LIGHT_RANGE_CHUNKS; dz++) {
                WorldChunk ch = mc.world.getChunkManager().getWorldChunk(cp.x + dx, cp.z + dz);
                if (ch == null) continue;
                ChunkSection[] secs = ch.getSectionArray();
                for (int si = 0; si < secs.length; si++) {
                    ChunkSection s = secs[si];
                    int baseY = ch.getBottomY() + si * 16;
                    if (s == null || s.isEmpty() || Math.abs(baseY + 8 - py) > 80) continue;
                    if (!s.hasAny(st -> st.getLuminance() > 0)) continue;
                    for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
                        BlockState st = s.getBlockState(x, y, z);
                        if (st.getLuminance() <= 0 || NATURAL.contains(st.getBlock())) continue;
                        BlockPos pos = new BlockPos(ch.getPos().getStartX() + x, baseY + y, ch.getPos().getStartZ() + z);
                        if (mc.world.isSkyVisible(pos)) continue; // underground / covered only
                        long key = new BlockPos(pos.getX() >> 3, pos.getY() >> 3, pos.getZ() >> 3).asLong();
                        cells.putIfAbsent(key, new Marker(Vec3d.ofCenter(pos), "Light", 0xFFFFFF55));
                        counts.merge(key, 1, Integer::sum);
                    }
                }
            }
        List<Marker> out = new ArrayList<>();
        for (var en : cells.entrySet()) {
            int n = counts.get(en.getKey());
            out.add(new Marker(en.getValue().pos(), n > 1 ? "Light x" + n : "Light", 0xFFFFFF55));
        }
        lightMarks = nearest(out, mc);
    }

    private static List<Marker> nearest(List<Marker> in, MinecraftClient mc) {
        Vec3d me = mc.player.getEyePos();
        in.sort(Comparator.comparingDouble(m -> m.pos().squaredDistanceTo(me)));
        return in.size() > MAX_MARKERS ? new ArrayList<>(in.subList(0, MAX_MARKERS)) : in;
    }

    // ------------------------------------------------------------ aim assist
    private static void aim(MinecraftClient mc) {
        Vec3d eye = mc.player.getEyePos();
        Vec3d look = mc.player.getRotationVec(1.0f);
        Vec3d target = null;
        double bestAngle = 25.0;
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player || p.isSpectator()) continue;
            Vec3d t = p.getBoundingBox().getCenter();
            double ang = Math.toDegrees(Math.acos(MathHelper.clamp(look.dotProduct(t.subtract(eye).normalize()), -1, 1)));
            if (ang < bestAngle) { bestAngle = ang; target = t; }
        }
        if (target == null && mc.crosshairTarget != null && mc.crosshairTarget.getType() == HitResult.Type.BLOCK) {
            BlockHitResult hit = (BlockHitResult) mc.crosshairTarget;
            target = Vec3d.ofCenter(hit.getBlockPos()).add(
                    hit.getSide().getOffsetX() * 0.5, hit.getSide().getOffsetY() * 0.5, hit.getSide().getOffsetZ() * 0.5);
        }
        if (target == null) return;
        double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
        float yawT = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitchT = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        float smooth = 0.35f;
        mc.player.setYaw(mc.player.getYaw() + MathHelper.wrapDegrees(yawT - mc.player.getYaw()) * smooth);
        mc.player.setPitch(MathHelper.clamp(mc.player.getPitch() + (pitchT - mc.player.getPitch()) * smooth, -90, 90));
    }

    // ------------------------------------------------------------ HUD
    private static void render(DrawContext ctx, float tp) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null || mc.options.hudHidden) return;
        if (!Config.players && !Config.chests && !Config.lights) return;

        Vec3d eye = mc.player.getLerpedPos(tp).add(0, mc.player.getEyeY() - mc.player.getY(), 0);
        Vec3d f = mc.player.getRotationVec(tp);
        Vec3d r = f.crossProduct(new Vec3d(0, 1, 0));
        if (r.lengthSquared() < 1e-6) return;
        r = r.normalize();
        Vec3d u = r.crossProduct(f);
        int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();
        double tanV = Math.tan(Math.toRadians(mc.options.getFov().getValue()) / 2.0);
        double tanH = tanV * w / h;
        TextRenderer tr = mc.textRenderer;

        if (Config.players)
            for (PlayerEntity p : mc.world.getPlayers()) {
                if (p == mc.player) continue;
                mark(ctx, tr, eye, f, r, u, w, h, tanH, tanV, p.getBoundingBox().getCenter(), p.getName().getString(), 0xFFFF5555);
            }
        if (Config.chests) for (Marker m : chestMarks) mark(ctx, tr, eye, f, r, u, w, h, tanH, tanV, m.pos(), m.label(), m.color());
        if (Config.lights) for (Marker m : lightMarks) mark(ctx, tr, eye, f, r, u, w, h, tanH, tanV, m.pos(), m.label(), m.color());
    }

    private static void mark(DrawContext ctx, TextRenderer tr, Vec3d cp, Vec3d f, Vec3d r, Vec3d u,
                             int w, int h, double tanH, double tanV, Vec3d target, String name, int col) {
        Vec3d d = target.subtract(cp);
        double zc = d.dotProduct(f), xc = d.dotProduct(r), yc = d.dotProduct(u);
        double sx = 0, sy = 0;
        boolean on = false;
        if (zc > 0.05) {
            sx = w / 2.0 + (xc / zc) / tanH * (w / 2.0);
            sy = h / 2.0 - (yc / zc) / tanV * (h / 2.0);
            on = sx >= 0 && sx <= w && sy >= 0 && sy <= h;
        }
        if (!on) { // clamp to screen edge toward the target
            double dx = xc, dy = -yc, len = Math.hypot(dx, dy);
            if (len < 1e-6) { dx = 0; dy = 1; len = 1; }
            dx /= len; dy /= len;
            double m = 14;
            double sc = Math.min(dx == 0 ? 1e9 : (w / 2.0 - m) / Math.abs(dx), dy == 0 ? 1e9 : (h / 2.0 - m) / Math.abs(dy));
            sx = w / 2.0 + dx * sc;
            sy = h / 2.0 + dy * sc;
        }
        int x = (int) sx, y = (int) sy, size = on ? 2 : 4;
        ctx.fill(x - size, y - size, x + size, y + size, col);
        String text = name + " " + (int) d.length() + "m";
        int tx = x > w / 2 ? x - tr.getWidth(text) - 7 : x + 7;
        ctx.drawTextWithShadow(tr, text, tx, y - 4, col);
    }
}
