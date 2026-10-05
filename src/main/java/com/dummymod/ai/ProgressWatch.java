package com.dummymod.ai;

/** Waiting for supplies is not a stall. Only a new best remaining count is progress. */
public final class ProgressWatch {
    private int last=-1,idle,total,retries;
    public boolean observe(int remaining,boolean moving,boolean waiting,int elapsedTicks) {
        return observe(remaining,moving,waiting,elapsedTicks,false);
    }
    public boolean observe(int remaining,boolean moving,boolean waiting,int elapsedTicks,boolean planning) {
        // Repeatedly breaking and replacing the same cell must not replenish
        // the recovery budget just because the count oscillates.
        if(last<0 || remaining<last){last=remaining;idle=total=retries=0;return false;}
        if(waiting){idle=total=0;return false;}
        total+=elapsedTicks;idle=moving || planning?0:idle+elapsedTicks;
        return idle>=200 || total>=(planning?1800:600);
    }
    public boolean recover(){idle=total=0;return ++retries<=4;}
    public void reset(){last=-1;idle=total=retries=0;}
    public int retries(){return retries;}
}
