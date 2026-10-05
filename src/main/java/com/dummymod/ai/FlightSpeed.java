package com.dummymod.ai;

import java.util.Locale;

public enum FlightSpeed {
    AUTO("авто"),FAST("быстро"),SLOW("медленно");
    public final String label;
    FlightSpeed(String label){this.label=label;}
    public FlightSpeed next(){return values()[(ordinal()+1)%values().length];}
    public double step(int remainingSteps){return this!=SLOW && remainingSteps>2?0.65:this==SLOW?0.16:0.19;}
    public static FlightSpeed command(String text) {
        String s=text.toLowerCase(Locale.ROOT).replaceAll("[.!]+$","").trim();
        if(s.matches("(?:летай|лети|летать|пол[её]т) (?:быстро|быстрее|побыстрее|с бегом|со спринтом|в спринте)"))return FAST;
        if(s.matches("(?:летай|лети|летать|пол[её]т) (?:медленно|медленнее|помедленнее|без бега|без спринта)"))return SLOW;
        if(s.matches("(?:летай|лети|летать|пол[её]т) (?:авто|обычно|автоматически)"))return AUTO;
        return null;
    }
}
