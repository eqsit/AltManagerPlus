package com.dummymod.ai;

import com.google.gson.*;
import java.io.IOException;
import java.util.Locale;

/** Safe provider diagnostics and a bounded retry policy. */
public final class ApiFailure extends IOException {
    public final int status;
    public final boolean retryable;
    public ApiFailure(int status,String reason,boolean retryable) {
        super("OmniRoute HTTP "+status+": "+reason);this.status=status;this.retryable=retryable;
    }
    public static ApiFailure response(int status,String body,String key) {
        boolean empty=body==null || body.isBlank();
        String reason=empty?"шлюз вернул пустой ответ":"ошибка провайдера";
        try {
            JsonObject root=JsonParser.parseString(body).getAsJsonObject();JsonElement error=root.get("error");
            if(error!=null && error.isJsonObject()) {
                JsonObject e=error.getAsJsonObject();
                if(e.has("message") && e.get("message").isJsonPrimitive())reason=e.get("message").getAsString();
                if(status==200 && e.has("code") && e.get("code").isJsonPrimitive())try{status=e.get("code").getAsInt();}catch(Exception ignored){}
            } else if(error!=null && error.isJsonPrimitive())reason=error.getAsString();
        } catch(Exception ignored){}
        if(key!=null && !key.isEmpty())reason=reason.replace(key,"[скрыто]");
        reason=reason.replaceAll("(?i)(bearer\\s+|sk-)[A-Za-z0-9._-]+","[скрыто]").replaceAll("[\\p{Cntrl}§]"," ");
        String lower=reason.toLowerCase(Locale.ROOT);
        boolean temporary=lower.contains("overload") || lower.contains("unavailable") || lower.contains("exhausted") || lower.contains("rate limit") || lower.contains("temporar") || lower.contains("capacity") || lower.contains("proxy unreachable") || lower.contains("fetch failed") || lower.contains("connection reset") || lower.contains("timed out");
        boolean retry=status==408 || status==425 || status==429 || status>=500 || ((status==200 || status==400) && (temporary || empty));
        return new ApiFailure(status,reason.substring(0,Math.min(reason.length(),240)),retry);
    }
    public String userMessage() {
        if(status==401 || status==403)return "OmniRoute отклонил ключ или доступ к модели (HTTP "+status+"). Проверь настройки ИИ.";
        if(retryable)return "OmniRoute пока не отвечает после повторных попыток (HTTP "+status+"). Проект сохранён, стройка может продолжаться.";
        return "OmniRoute отклонил запрос (HTTP "+status+"): "+getMessage().substring(getMessage().indexOf(": ")+2)+". Проект сохранён.";
    }
}
