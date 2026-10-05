package com.dummymod.ai;

import java.util.*;
import java.util.regex.*;

public final class ChatAccess {
    private static final Pattern NAME=Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final Pattern VANILLA=Pattern.compile("^<([A-Za-z0-9_]{3,16})>\\s+(.+)$");
    private static final Pattern SERVER=Pattern.compile("^(?:\\[[^\\]\\r\\n]{1,32}\\]\\s*)*([A-Za-z0-9_]{3,16})\\s*[:»→]\\s*(.+)$");
    public record Incoming(String sender,String content) { }
    private ChatAccess() { }
    public static boolean allowed(String whitelist,String name) {
        if (name==null || whitelist==null || !NAME.matcher(name).matches()) return false;
        return Arrays.stream(whitelist.split("[,;\\s]+")) .anyMatch(n -> NAME.matcher(n).matches() && n.equalsIgnoreCase(name));
    }
    public static boolean validWhitelist(String text) {
        return text==null || text.isBlank() || Arrays.stream(text.trim().split("[,;\\s]+")).allMatch(n->NAME.matcher(n).matches());
    }
    public static Incoming parseFormatted(String text) {
        if(text==null)return null;
        text=text.replaceAll("§.","");
        for(Pattern p:List.of(VANILLA,SERVER)) {
            Matcher m=p.matcher(text);if(m.matches())return new Incoming(m.group(1),m.group(2));
        }
        return null;
    }
}
