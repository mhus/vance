/**
 * Model-specific {@link de.mhus.vance.brain.ai.parser.MessageParser}
 * implementations. One bean per known quirk family ({@code gemma4},
 * {@code deepseek-v4}, …); selection is data-driven through
 * {@code ModelInfo.messageParser()} with defaults in
 * {@code vance-defaults/model-quirks.yaml}.
 */
@org.jspecify.annotations.NullMarked
package de.mhus.vance.brain.ai.parser;
