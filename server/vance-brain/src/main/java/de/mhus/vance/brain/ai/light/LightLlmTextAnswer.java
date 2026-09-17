package de.mhus.vance.brain.ai.light;

import org.jspecify.annotations.Nullable;

/**
 * Raw-call answer with the answering model and its token usage — the
 * counterpart of {@link LightLlmJsonAnswer} for {@code call()}-style text
 * replies. Exists because bulk engines meter cost per call; the usage is
 * the answering attempt's (mirrors the audit emitter).
 */
public record LightLlmTextAnswer(String text, @Nullable String model, LightLlmJsonAnswer.@Nullable Usage usage) {}
