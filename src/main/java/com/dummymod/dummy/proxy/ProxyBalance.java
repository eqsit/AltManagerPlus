package com.dummymod.dummy.proxy;

/** Select the least occupied route; rotate ties without moving existing sockets. */
public final class ProxyBalance {
    private ProxyBalance() {}
    public static int choose(long[] loads, int cursor) {
        if (loads.length == 0) throw new IllegalArgumentException("No routes");
        int best = Math.floorMod(cursor, loads.length);
        for (int offset = 1; offset < loads.length; offset++) {
            int index = Math.floorMod(Math.floorMod(cursor, loads.length) + offset, loads.length);
            if (loads[index] < loads[best]) best = index;
        }
        return best;
    }
}
