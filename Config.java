package com.example.buildassist;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class Config {
    public static boolean flyEnabled = false, players = true, chests = false, lights = false,
            aimAlways = false, swapEnabled = false, swapPlayersOnly = true;
    public static double flyMult = 5, blockReach = 4.5, entityReach = 3, knockback = 0;
    public static int swapSlot = 2, bw = 7, bh = 4, bl = 7, blockIdx = 0, floorIdx = 0;

    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("buildassist.properties");

    private Config() {}

    public static void load() {
        try {
            if (!Files.exists(FILE)) return;
            Properties p = new Properties();
            try (var in = Files.newInputStream(FILE)) { p.load(in); }
            flyEnabled = b(p, "flyEnabled", flyEnabled);
            players = b(p, "players", players);
            chests = b(p, "chests", chests);
            lights = b(p, "lights", lights);
            aimAlways = b(p, "aimAlways", aimAlways);
            swapEnabled = b(p, "swapEnabled", swapEnabled);
            swapPlayersOnly = b(p, "swapPlayersOnly", swapPlayersOnly);
            flyMult = d(p, "flyMult", flyMult);
            blockReach = d(p, "blockReach", blockReach);
            entityReach = d(p, "entityReach", entityReach);
            knockback = d(p, "knockback", knockback);
            swapSlot = i(p, "swapSlot", swapSlot);
            bw = i(p, "bw", bw); bh = i(p, "bh", bh); bl = i(p, "bl", bl);
            blockIdx = i(p, "blockIdx", blockIdx); floorIdx = i(p, "floorIdx", floorIdx);
        } catch (Exception ignored) {}
    }

    public static void save() {
        try {
            Properties p = new Properties();
            p.setProperty("flyEnabled", "" + flyEnabled); p.setProperty("players", "" + players);
            p.setProperty("chests", "" + chests); p.setProperty("lights", "" + lights);
            p.setProperty("aimAlways", "" + aimAlways); p.setProperty("swapEnabled", "" + swapEnabled);
            p.setProperty("swapPlayersOnly", "" + swapPlayersOnly);
            p.setProperty("flyMult", "" + flyMult); p.setProperty("blockReach", "" + blockReach);
            p.setProperty("entityReach", "" + entityReach); p.setProperty("knockback", "" + knockback);
            p.setProperty("swapSlot", "" + swapSlot);
            p.setProperty("bw", "" + bw); p.setProperty("bh", "" + bh); p.setProperty("bl", "" + bl);
            p.setProperty("blockIdx", "" + blockIdx); p.setProperty("floorIdx", "" + floorIdx);
            try (var out = Files.newOutputStream(FILE)) { p.store(out, "Build Assist"); }
        } catch (Exception ignored) {}
    }

    private static boolean b(Properties p, String k, boolean def) { String v = p.getProperty(k); return v == null ? def : Boolean.parseBoolean(v); }
    private static double d(Properties p, String k, double def) { try { return Double.parseDouble(p.getProperty(k)); } catch (Exception e) { return def; } }
    private static int i(Properties p, String k, int def) { try { return Integer.parseInt(p.getProperty(k)); } catch (Exception e) { return def; } }
}
