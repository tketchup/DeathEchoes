package dev.deathechoes.record;

import org.bukkit.Material;

/**
 * Ringpuffer mit festen, primitiven Spalten-Arrays (struct of arrays). Nach dem Anlegen wird beim
 * Aufnehmen nichts mehr alloziert: jeder Frame überschreibt nur Felder an der Kopfposition.
 *
 * <p>Nicht thread-sicher; wird ausschließlich auf dem Main-Thread benutzt.</p>
 */
public final class FrameBuffer {

    private final int capacity;
    private final double[] x;
    private final double[] y;
    private final double[] z;
    private final float[] yaw;
    private final float[] pitch;
    private final byte[] pose;
    private final byte[] flags;
    private final Material[] item;

    /** Index, an den der nächste Frame geschrieben wird. */
    private int head;
    private int size;

    public FrameBuffer(int capacity) {
        if (capacity < 2) throw new IllegalArgumentException("capacity < 2");
        this.capacity = capacity;
        x = new double[capacity];
        y = new double[capacity];
        z = new double[capacity];
        yaw = new float[capacity];
        pitch = new float[capacity];
        pose = new byte[capacity];
        flags = new byte[capacity];
        item = new Material[capacity];
    }

    public void add(double px, double py, double pz, float pYaw, float pPitch, byte pPose, byte pFlags, Material pItem) {
        int i = head;
        x[i] = px;
        y[i] = py;
        z[i] = pz;
        yaw[i] = pYaw;
        pitch[i] = pPitch;
        pose[i] = pPose;
        flags[i] = pFlags;
        item[i] = pItem;
        head = (i + 1) % capacity;
        if (size < capacity) size++;
    }

    public void clear() {
        head = 0;
        size = 0;
        java.util.Arrays.fill(item, null);
    }

    public int size() {
        return size;
    }

    public int capacity() {
        return capacity;
    }

    /** Index des ältesten Frames im Ring. */
    private int start() {
        return size < capacity ? 0 : head;
    }

    /** Physischer Index des n-ten Frames in chronologischer Reihenfolge (0 = ältester). */
    public int indexOf(int n) {
        return (start() + n) % capacity;
    }

    public double x(int n) { return x[indexOf(n)]; }
    public double y(int n) { return y[indexOf(n)]; }
    public double z(int n) { return z[indexOf(n)]; }
    public byte pose(int n) { return pose[indexOf(n)]; }
    public byte flags(int n) { return flags[indexOf(n)]; }

    /**
     * Friert den Puffer in chronologischer Reihenfolge zu einer {@link Recording} ein. Das ist die
     * einzige Stelle, an der kopiert wird (nur beim Tod oder bei /echoes test).
     */
    public Recording freeze(int intervalTicks, Material[] armor) {
        int n = size;
        double[] cx = new double[n];
        double[] cy = new double[n];
        double[] cz = new double[n];
        float[] cyaw = new float[n];
        float[] cpitch = new float[n];
        byte[] cpose = new byte[n];
        byte[] cflags = new byte[n];
        Material[] citem = new Material[n];
        int s = start();
        for (int k = 0; k < n; k++) {
            int i = (s + k) % capacity;
            cx[k] = x[i];
            cy[k] = y[i];
            cz[k] = z[i];
            cyaw[k] = yaw[i];
            cpitch[k] = pitch[i];
            cpose[k] = pose[i];
            cflags[k] = flags[i];
            citem[k] = item[i];
        }
        return new Recording(intervalTicks, cx, cy, cz, cyaw, cpitch, cpose, cflags, citem, armor.clone());
    }
}
