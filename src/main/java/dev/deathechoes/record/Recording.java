package dev.deathechoes.record;

import org.bukkit.Material;

/**
 * Eingefrorene Aufnahme: chronologische Spalten-Arrays, ab dem Einfrieren unveränderlich
 * (die Arrays werden nach außen nicht verändert).
 *
 * @param armor Rüstung beim Tod: Helm, Brust, Beine, Schuhe (Einträge dürfen null sein)
 */
public record Recording(
        int intervalTicks,
        double[] x,
        double[] y,
        double[] z,
        float[] yaw,
        float[] pitch,
        byte[] pose,
        byte[] flags,
        Material[] item,
        Material[] armor) {

    public static final byte POSE_STANDING = 0;
    public static final byte POSE_SNEAKING = 1;
    public static final byte POSE_SWIMMING = 2;
    public static final byte POSE_GLIDING = 3;
    public static final byte POSE_SLEEPING = 4;

    public static final byte FLAG_SPRINT = 1;
    public static final byte FLAG_SWING = 1 << 1;
    public static final byte FLAG_HURT = 1 << 2;

    public int frames() {
        return x.length;
    }

    /** Abgespielte Länge in Ticks (vom ersten bis zum letzten Frame). */
    public int durationTicks() {
        return Math.max(0, frames() - 1) * intervalTicks;
    }

    public int countFlag(byte flag) {
        int c = 0;
        for (byte f : flags) if ((f & flag) != 0) c++;
        return c;
    }
}
