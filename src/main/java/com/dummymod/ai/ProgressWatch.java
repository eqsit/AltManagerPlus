package com.dummymod.ai;

/** Waiting for supplies is not a stall. Reset recovery attempts only on block progress. */
public final class ProgressWatch {
    private int last=-1,idle,total,retries;
    public boolean observe(int remaining,boolean moving,boolean waiting,int elapsedTicks) {
        if(remaining!=last){last=remaining;idle=total=retries=0;return false;}
        if(waiting){idle=total=0;return false;}
        total+=elapsedTicks;idle=moving?0:idle+elapsedTicks;
        return idle>=200 || total>=600;
    }
    public boolean recover(){idle=total=0;return ++retries<=4;}
    public void reset(){last=-1;idle=total=retries=0;}
    public int retries(){return retries;}
}
