package com.dummymod.ai;

import com.dummymod.config.DummyConfig;
import com.dummymod.dummy.*;
import java.util.*;
import java.util.regex.*;

/** Deduplicate the same server broadcast arriving on several dummy connections. */
public final class AiChatRouter {
    private static final Map<String,Long> SEEN=new HashMap<>();
    private static final Map<String,Long> ENGAGED=new HashMap<>();
    private AiChatRouter(){}
    public static void formatted(String text) {
        if(!DummyConfig.getInstance().aiServerFormattedChat)return;
        ChatAccess.Incoming m=ChatAccess.parseFormatted(text);if(m!=null)receive(m.sender(),m.content());
    }
    public static void receive(String sender,String content) {
        DummyConfig c=DummyConfig.getInstance();
        if(!c.aiEnabled || !ChatAccess.allowed(c.aiChatWhitelist,sender) || content==null)return;
        if(DummyManager.dummySessions.stream().anyMatch(s->s.displayName().equalsIgnoreCase(sender)))return;
        long now=System.currentTimeMillis();String key=sender.toLowerCase(Locale.ROOT)+"|"+content;
        SEEN.entrySet().removeIf(e->now-e.getValue()>3000);if(SEEN.put(key,now)!=null)return;
        List<PlayerSession> candidates=DummyManager.dummySessions.stream().filter(PlayerSession::isValid).toList();
        boolean addressed=false;
        for(PlayerSession s:candidates) {
            Pattern name=Pattern.compile("(?iu)(?<![a-z0-9_])@?"+Pattern.quote(s.displayName())+"(?![a-z0-9_])");Matcher m=name.matcher(content);
            if(m.find()) {
                addressed=true;String text=(content.substring(0,m.start())+" "+content.substring(m.end())).replaceFirst("^[\\s,:]+","").strip();
                ENGAGED.put(sender.toLowerCase(Locale.ROOT)+"|"+s.id,now);s.ai.receive(sender,text);
            }
        }
        if(addressed)return;
        PlayerSession target=candidates.stream().filter(s->now-ENGAGED.getOrDefault(sender.toLowerCase(Locale.ROOT)+"|"+s.id,0L)<1800000).max(Comparator.comparingLong(s->ENGAGED.getOrDefault(sender.toLowerCase(Locale.ROOT)+"|"+s.id,0L))).orElse(null);
        if(target==null && candidates.size()==1)target=candidates.getFirst();
        if(target!=null){ENGAGED.put(sender.toLowerCase(Locale.ROOT)+"|"+target.id,now);target.ai.receive(sender,content);}
    }
}
