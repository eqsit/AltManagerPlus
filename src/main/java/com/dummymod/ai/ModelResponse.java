package com.dummymod.ai;

import com.google.gson.*;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

/** Collect SSE without exposing partial plans to the game. */
public final class ModelResponse {
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    private static final Pattern FENCE = Pattern.compile("\\A```(?:json)?[ \\t]*\\R(.*?)\\R```\\z", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Set<String> ACTIONS = Set.of("chat","plan","start","pause","resume","stop","goto","follow","jump","mine","break","give");
    public static JsonObject parse(String body)throws IOException {
        return parse(body,null);
    }
    static JsonObject parse(String body,String key)throws IOException {
        try {return decode(body,key);}
        catch(RuntimeException e) {
            throw new ModelFailure(ModelFailure.Kind.MALFORMED);
        }
    }
    private static JsonObject decode(String body,String key)throws IOException {
        String content;
        if(body.stripLeading().startsWith("{")) {
            JsonObject response=JsonParser.parseString(body).getAsJsonObject();
            if(response.has("error"))throw ApiFailure.response(200,response.toString(),key);
            JsonObject choice=response.getAsJsonArray("choices").get(0).getAsJsonObject();
            checkFinish(choice);content=choice.getAsJsonObject("message").get("content").getAsString();
        } else {
            StringBuilder text=new StringBuilder();boolean done=false;
            for(String line:body.split("\\R")) {
                if(!line.startsWith("data:"))continue;
                String data=line.substring(5).strip();if(data.equals("[DONE]")){done=true;break;}
                if(data.isEmpty())continue;
                JsonObject chunk=JsonParser.parseString(data).getAsJsonObject();
                if(chunk.has("error"))throw ApiFailure.response(200,chunk.toString(),key);
                JsonArray choices=chunk.getAsJsonArray("choices");if(choices==null || choices.isEmpty())continue;
                JsonObject choice=choices.get(0).getAsJsonObject();checkFinish(choice);
                JsonObject delta=choice.getAsJsonObject("delta");
                if(delta!=null && delta.has("content") && !delta.get("content").isJsonNull())text.append(delta.get("content").getAsString());
                if(text.length()>300000)throw new ModelFailure(ModelFailure.Kind.OVERSIZED);
            }
            if(!done)throw new ModelFailure(ModelFailure.Kind.INCOMPLETE);
            content=text.toString();
        }
        if(content==null || content.isBlank())throw new ModelFailure(ModelFailure.Kind.EMPTY);
        if(content.length()>300000)throw new ModelFailure(ModelFailure.Kind.OVERSIZED);
        content=content.strip();var fence=FENCE.matcher(content);if(fence.matches())content=fence.group(1).strip();
        // Parse the complete answer. A JSON sketch inside reasoning or a complete
        // prefix followed by a truncated second document is not a finished plan.
        JsonObject result=JSON.fromJson(content,JsonObject.class);
        if(result==null || !result.has("action") || !result.get("action").isJsonPrimitive()
                || !result.getAsJsonPrimitive("action").isString()
                || !ACTIONS.contains(result.get("action").getAsString()))throw new ModelFailure(ModelFailure.Kind.MALFORMED);
        return result;
    }
    private static void checkFinish(JsonObject choice)throws IOException {
        if(!choice.has("finish_reason") || choice.get("finish_reason").isJsonNull())return;
        String reason=choice.get("finish_reason").getAsString().toLowerCase(Locale.ROOT);
        if(Set.of("length","max_tokens").contains(reason))throw new ModelFailure(ModelFailure.Kind.TRUNCATED);
        if(Set.of("content_filter","safety","recitation").contains(reason))throw new ModelFailure(ModelFailure.Kind.FILTERED);
    }
}
