package com.dummymod.ai;

import com.google.gson.*;
import java.util.*;

/** Validate off the game thread and ask the model to correct a rejected draft. */
public final class PlanRepair {
    public record Result(JsonObject response, BuildPlan plan) { }
    @FunctionalInterface public interface Request {
        JsonObject ask(String system, List<Conversation.Message> messages) throws Exception;
    }
    @FunctionalInterface public interface Rejected {
        void accept(int attempt, JsonObject response, String reason);
    }
    public static final class InvalidPlan extends IllegalArgumentException {
        InvalidPlan(String reason) { super(reason); }
    }
    private PlanRepair() { }

    public static Result ask(String system, List<Conversation.Message> history, Request request, Rejected rejected) throws Exception {
        List<Conversation.Message> turns = new ArrayList<>(history);
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        JsonObject response = request.ask(system, List.copyOf(turns));
        JsonObject draft = null;
        for (int attempt = 0; ; attempt++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (response.has("plan")) draft = response.deepCopy();
            String reason;
            try {
                String action = response.has("action") ? response.get("action").getAsString() : "chat";
                if (attempt > 0 && !Set.of("plan", "chat").contains(action))
                    throw new IllegalArgumentException("Исправление схемы не разрешает запуск стройки или другие действия");
                if (!action.equals("plan")) return new Result(response, null);
                BuildPlan plan = BuildPlan.parse(response.getAsJsonObject("plan"));
                JsonObject canonical = response.deepCopy(); canonical.add("plan", plan.source);
                org.slf4j.LoggerFactory.getLogger("DummyMod-AI").info("AI blueprint validated: operations={}, size={}x{}x{}, corners_normalized={}", plan.source.getAsJsonArray("operations").size(), plan.width, plan.height, plan.length, !plan.source.equals(response.get("plan")));
                return new Result(canonical, plan);
            } catch (RuntimeException error) {
                reason = error instanceof IllegalArgumentException && error.getMessage() != null
                        ? error.getMessage().replaceAll("[\\p{Cntrl}§]", " ") : "Некорректная структура схемы";
                reason = reason.substring(0, Math.min(reason.length(), 180));
            }
            rejected.accept(attempt + 1, draft == null ? response.deepCopy() : draft.deepCopy(), reason);
            if (attempt >= 2) throw new InvalidPlan(reason);
            turns.add(new Conversation.Message("assistant", response.toString()));
            turns.add(new Conversation.Message("user", "Проверка схемы нашла ошибку: " + reason + ". Исправь полный JSON проекта, сохранив замысел, материалы, расположение и размеры. origin — абсолютная точка мира; from/to — смещения 0..95 от origin. Не подставляй мировые координаты в from/to. У каждой пары углов меньшая координата должна идти в from. Проверяй ВСЕ операции. Не обрезай и не уменьшай проект молча. Если исправить его в лимитах нельзя, ответь action=chat и объясни, какие части нужно согласовать. Разрешены только action=plan или action=chat; ничего не начинай строить."));
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            response = request.ask(system, List.copyOf(turns));
        }
    }
}
