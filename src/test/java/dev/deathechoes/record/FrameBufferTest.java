package dev.deathechoes.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class FrameBufferTest {

    private static void addN(FrameBuffer b, int from, int to) {
        for (int i = from; i < to; i++) {
            b.add(i, 0, 0, 0, 0, (byte) 0, (byte) 0, null);
        }
    }

    @Test
    void keepsChronologicalOrderBeforeWrap() {
        FrameBuffer b = new FrameBuffer(5);
        addN(b, 0, 3);
        Recording r = b.freeze(2, new Material[4]);
        assertEquals(3, r.frames());
        assertEquals(0, r.x()[0]);
        assertEquals(2, r.x()[2]);
        assertEquals(4, r.durationTicks());
    }

    @Test
    void keepsOnlyNewestAfterWrap() {
        FrameBuffer b = new FrameBuffer(5);
        addN(b, 0, 12);
        assertEquals(5, b.size());
        Recording r = b.freeze(2, new Material[4]);
        assertEquals(5, r.frames());
        for (int k = 0; k < 5; k++) {
            assertEquals(7 + k, r.x()[k]);
        }
    }

    @Test
    void clearResets() {
        FrameBuffer b = new FrameBuffer(4);
        b.add(1, 2, 3, 0, 0, (byte) 0, (byte) 0, Material.STONE);
        b.clear();
        assertEquals(0, b.size());
        addN(b, 10, 12);
        Recording r = b.freeze(1, new Material[4]);
        assertEquals(10, r.x()[0]);
        assertNull(r.item()[0]);
    }

    @Test
    void freezeIsIndependentCopy() {
        FrameBuffer b = new FrameBuffer(3);
        addN(b, 0, 3);
        Recording r = b.freeze(2, new Material[4]);
        addN(b, 100, 103);
        assertEquals(0, r.x()[0]);
    }
}
