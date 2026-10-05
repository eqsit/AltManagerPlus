package com.dummymod.ai;

import com.dummymod.dummy.PlayerSession;

public final class Supply {
    private Supply(){}
    public static boolean canGive(PlayerSession s) {
        if(s.networkHandler==null)return false;
        var root=s.networkHandler.getCommandDispatcher().getRoot();return root.getChild("give")!=null || root.getChild("minecraft:give")!=null;
    }
    static String command(String name,String item,int count) {
        if(!name.matches("[A-Za-z0-9_]{3,16}") || !item.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || count<1 || count>64)throw new IllegalArgumentException("Неверные параметры выдачи блока");
        return "give "+name+" "+item+" "+count;
    }
    public static void give(PlayerSession s,String item,int count) {
        if(!canGive(s))throw new IllegalArgumentException("Сервер не предоставил этой дамми команду /give");
        s.networkHandler.sendChatCommand(command(s.displayName(),item,count));
    }
}
