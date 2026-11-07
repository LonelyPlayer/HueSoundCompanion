package app.huebeat.core;

import java.util.Random;

/**
 * Which points of a scene belong to which device. A strip brings many points, a lamp one, so
 * random picks go by device first: every light gets the same chance.
 */
public final class Devices {
    public final int[] start, len, of;

    /** @param start first point of each device, @param len its number of points, @param n points in all */
    public Devices(int[] start, int[] len, int n) {
        this.start = start.clone();
        this.len = len.clone();
        of = new int[n];
        for (int d = 0; d < start.length; d++) {
            for (int i = start[d]; i < start[d] + len[d] && i < n; i++) of[i] = d;
        }
    }

    public int count() {
        return start.length;
    }

    /** A random device, then a random point of it. */
    public int randomPoint(Random r) {
        if (start.length == 0) return 0;
        int d = r.nextInt(start.length);
        return start[d] + (len[d] > 1 ? r.nextInt(len[d]) : 0);
    }
}
