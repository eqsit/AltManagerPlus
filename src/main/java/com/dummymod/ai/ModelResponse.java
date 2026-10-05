package com.dummymod.ai;

import com.google.gson.*;
import java.io.IOException;

/** Collect SSE without exposing partial plans to the game. */
public final class ModelResponse {
    public static JsonObject parse(String body)throws IOException {
        return parse(body,null);
    }
    static JsonObject parse(String body,String key)throws IOException {
        try {return decode(body,key);}
        catch(JsonParseException | IllegalStateException | IndexOutOfBoundsException | NullPointerException e) {
            throw new IOException("OmniRoute вернул некорректный JSON; проект не применён",e);
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
                if(text.length()>300000)throw new IOException("Проект слишком большой");
            }
            if(!done)throw new IOException("Поток OmniRoute оборвался; проект не применён");
            content=text.toString();
        }
        int start=content.indexOf('{'),end=content.lastIndexOf('}');
        if(start<0 || end<start)throw new IOException("Модель не вернула JSON");
        return JsonParser.parseString(content.substring(start,end+1)).getAsJsonObject();
    }
    private static void checkFinish(JsonObject choice)throws IOException {
        if(choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull() && "length".equals(choice.get("finish_reason").getAsString()))throw new IOException("Проект превысил размер ответа; раздели его на части");
    }
}
