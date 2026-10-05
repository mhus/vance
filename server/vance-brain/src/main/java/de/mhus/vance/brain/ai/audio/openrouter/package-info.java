/**
 * Provider adapters speaking a dedicated audio wire that is not the
 * OpenAI protocol the instance declares for chat. First one:
 * {@code OpenRouterAudioProvider} (TTS / STT / music via OpenRouter's
 * own endpoints, dispatched on the {@code openrouter} instance label).
 */
@NullMarked
package de.mhus.vance.brain.ai.audio.openrouter;

import org.jspecify.annotations.NullMarked;
