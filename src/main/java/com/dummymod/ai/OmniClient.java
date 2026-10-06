package com.dummymod.ai;

import com.google.gson.*;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;

public final class OmniClient {
    private static final Semaphore REQUESTS=new Semaphore(2,true);
    private static final HttpClient HTTP=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(15)).proxy(new ProxySelector(){
        public List<Proxy> select(URI uri){return List.of(Proxy.NO_PROXY);}
        public void connectFailed(URI uri,SocketAddress address,IOException e){}
    }).build();
    private OmniClient(){}
    public static JsonObject ask(String system,List<Conversation.Message> history) throws Exception {
        AiConnection config=AiConnection.get();
        return ask(config.baseUrl.replaceAll("/+$","")+"/chat/completions",config.apiKey,config.model,system,history);
    }
    static JsonArray messages(String system,List<Conversation.Message> history) {
        JsonArray messages=new JsonArray();JsonObject instruction=new JsonObject();instruction.addProperty("role","system");instruction.addProperty("content",system);messages.add(instruction);
        // Building progress can create several assistant messages in a row.
        // Gemini providers expect alternating conversational turns.
        for(Conversation.Message m:history) {
            if(m.content()==null || m.content().isBlank() || !Set.of("user","assistant").contains(m.role()))continue;
            JsonObject previous=messages.get(messages.size()-1).getAsJsonObject();
            if(m.role().equals(previous.get("role").getAsString()))previous.addProperty("content",previous.get("content").getAsString()+"\n\n"+m.content());
            else {JsonObject j=new JsonObject();j.addProperty("role",m.role());j.addProperty("content",m.content());messages.add(j);}
        }
        if(messages.size()>1 && messages.get(1).getAsJsonObject().get("role").getAsString().equals("assistant")) {
            JsonObject turn=new JsonObject();turn.addProperty("role","user");turn.addProperty("content","Продолжим сохранённый разговор.");messages.asList().add(1,turn);
        }
        return messages;
    }
    static JsonObject requestBody(String model,String system,List<Conversation.Message> history,ModelFailure correction) {
        JsonObject body=new JsonObject();body.addProperty("model",model);
        JsonArray turns=messages(system,history);
        boolean gemini=model.toLowerCase(Locale.ROOT).contains("gemini");
        boolean correct=correction!=null && correction.needsCorrection();
        body.addProperty("max_tokens",gemini?16384:10000);body.addProperty("stream",true);
        if(gemini) {
            // This budget is part of the total output limit, not extra tokens.
            // Explicit thinking overrides OmniRoute's 24576-token default.
            JsonObject thinking=new JsonObject();thinking.addProperty("type","enabled");thinking.addProperty("budget_tokens",correct?1024:4096);body.add("thinking",thinking);
            JsonObject format=new JsonObject();format.addProperty("type","json_object");body.add("response_format",format);
        }
        if(correct) {
            String note="Проверка предыдущего ответа: "+correction.getMessage()+". Верни ОДИН полный JSON с action и reply, без рассуждений, примеров и markdown. Сохрани исходный замысел, детали, материалы, размеры и координаты. Для повторяющихся элементов используй box/hollow_box/cylinder вместо множества точечных операций. Не обрезай и не упрощай постройку молча. Если полный проект не помещается в ответ, верни action=chat и предложи согласовать отдельные части.";
            JsonObject last=turns.get(turns.size()-1).getAsJsonObject();
            if(last.get("role").getAsString().equals("user"))last.addProperty("content",last.get("content").getAsString()+"\n\n"+note);
            else {JsonObject turn=new JsonObject();turn.addProperty("role","user");turn.addProperty("content",note);turns.add(turn);}
        }
        body.add("messages",turns);return body;
    }
    static JsonObject ask(String endpoint,String key,String model,String system,List<Conversation.Message> history)throws Exception {
        ModelFailure correction=null;
        REQUESTS.acquire();
        try {
            for(int attempt=0;;attempt++) {
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                JsonObject body=requestBody(model,system,history,correction);
                HttpRequest req=HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(300)).header("Authorization","Bearer "+key).header("Content-Type","application/json").header("Accept","text/event-stream").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
                var pending=HTTP.sendAsync(req,HttpResponse.BodyHandlers.ofString());
                try {
                    HttpResponse<String> r=pending.get(305,java.util.concurrent.TimeUnit.SECONDS);
                    if(r.statusCode()!=200)throw ApiFailure.response(r.statusCode(),r.body(),key);
                    if(r.body().length()>1500000)throw new ModelFailure(ModelFailure.Kind.OVERSIZED);
                    return ModelResponse.parse(r.body(),key);
                } catch(Exception error) {
                    pending.cancel(true);
                    Throwable cause=error instanceof java.util.concurrent.ExecutionException?error.getCause():error;
                    if(cause instanceof InterruptedException)throw (InterruptedException)cause;
                    boolean retry=cause instanceof ApiFailure f?f.retryable:cause instanceof ModelFailure f?f.retryable():cause instanceof IOException || cause instanceof java.util.concurrent.TimeoutException;
                    if(!retry || attempt>=2) {
                        if(cause instanceof Exception e)throw e;
                        throw error;
                    }
                    if(cause instanceof ModelFailure f && f.needsCorrection())correction=f;
                    org.slf4j.LoggerFactory.getLogger("DummyMod-AI").warn("Retrying OmniRoute request, attempt {} ({})",attempt+2,cause instanceof ApiFailure f?"HTTP "+f.status:cause instanceof ModelFailure f?f.kind.name():cause.getClass().getSimpleName());
                    Thread.sleep((attempt+1)*1500L);
                }
            }
        } finally { REQUESTS.release(); }
    }
}
