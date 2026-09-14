package com.dummymod.dummy;

public class AutoclickerState {
    public boolean enabled;
    private int cps = 10;
    private double clickAccumulator;

    public int getCps() {
        return cps;
    }

    public void setCps(int cps) {
        this.cps = Math.max(1, Math.min(50, cps));
        this.clickAccumulator = Math.min(this.clickAccumulator, 0.999999D);
    }

    /**
     * Returns the exact number of clicks due this 20 TPS client tick. This
     * supports every integer from 1 through 50 CPS instead of rounding to a
     * whole tick interval.
     */
    public int consumeClicksThisTick() {
        if (!enabled) {
            clickAccumulator = 0.0D;
            return 0;
        }
        clickAccumulator += cps / 20.0D;
        int clicks = (int) Math.floor(clickAccumulator);
        clickAccumulator -= clicks;
        return clicks;
    }
}
