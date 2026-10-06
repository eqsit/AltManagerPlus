package com.dummymod.ai;

import java.io.IOException;

/** Fixed diagnostics: never expose a provider's response body or credentials. */
public final class ModelFailure extends IOException {
    public enum Kind { TRUNCATED, MALFORMED, EMPTY, INCOMPLETE, OVERSIZED, FILTERED }
    public final Kind kind;
    public ModelFailure(Kind kind) {
        super(switch (kind) {
            case TRUNCATED -> "Модель достигла лимита ответа; незаконченный чертёж не применён";
            case MALFORMED -> "Модель вернула некорректный JSON; проект не применён";
            case EMPTY -> "Модель не вернула ответ";
            case INCOMPLETE -> "Поток OmniRoute оборвался до завершения ответа";
            case OVERSIZED -> "Ответ модели слишком большой; проект нужно разделить на части";
            case FILTERED -> "Провайдер остановил генерацию ответа";
        });
        this.kind = kind;
    }
    public boolean retryable() { return kind != Kind.OVERSIZED && kind != Kind.FILTERED; }
    public boolean needsCorrection() {
        return kind == Kind.TRUNCATED || kind == Kind.MALFORMED || kind == Kind.EMPTY;
    }
    public String userMessage() { return getMessage() + ". Проект и переписка сохранены."; }
}
