package com.dummymod.ai;

import java.util.HashMap;
import java.util.Map;
import java.util.function.ToIntFunction;

/** Remember delivery even after the builder has consumed the entire stack. */
final class SupplyWatch<T> {
    private record Attempt(long sentAt,int before,boolean delivered){}
    private final Map<T,Attempt> attempts=new HashMap<>();
    void sent(T item,long now,int before){attempts.put(item,new Attempt(now,before,false));}
    void observe(ToIntFunction<T> count){attempts.replaceAll((item,a)->!a.delivered && count.applyAsInt(item)>a.before?new Attempt(a.sentAt,a.before,true):a);}
    boolean canRequest(T item,long now){Attempt a=attempts.get(item);return a==null || a.delivered && now-a.sentAt>=5000;}
}
