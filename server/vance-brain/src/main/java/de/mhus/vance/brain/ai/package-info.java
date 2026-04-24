/**
 * AI stack for the Brain.
 *
 * <p>This is where the actual LLM/agent magic lives. {@code vance-shared} is
 * deliberately AI-free — langchain4j and langgraph4j are brain-only
 * dependencies.
 *
 * <p>The core abstraction is {@link AiChat}: a configured, ready-to-use chat
 * instance against a specific provider+model. Callers build an
 * {@link AiChatConfig} (usually from {@code SettingService} lookups), combine
 * it with runtime {@link AiChatOptions}, and hand both to
 * {@link AiModelService#createChat(AiChatConfig, AiChatOptions)}.
 *
 * <p>Provider implementations live in sub-packages (e.g.
 * {@code de.mhus.vance.brain.ai.anthropic}) as Spring beans implementing
 * {@link AiModelProvider}. {@link AiModelService} auto-discovers them.
 *
 * <p>{@link AiGraphService} is the entry point for langgraph4j-based agent
 * flows. In v1 it is a placeholder — concrete graphs arrive with Arthur /
 * DeepThink.
 */
@NullMarked
package de.mhus.vance.brain.ai;

import org.jspecify.annotations.NullMarked;
