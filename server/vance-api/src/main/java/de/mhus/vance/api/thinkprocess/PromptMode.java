package de.mhus.vance.api.thinkprocess;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * How a recipe's {@code promptPrefix} is combined with the
 * engine-declared system prompt.
 *
 * <ul>
 *   <li>{@link #APPEND} — engine prompt first, recipe prefix after a
 *       separator. Engine hard-rules stay binding; recipe adds
 *       role-specialisation. Default.</li>
 *   <li>{@link #OVERWRITE} — engine prompt is ignored; the recipe
 *       carries the entire system prompt. Use only when the recipe
 *       intends to replace the engine's built-in behaviour wholesale
 *       — engine hard-rules must be re-stated by the recipe itself.</li>
 * </ul>
 */
@GenerateTypeScript("thinkprocess")
public enum PromptMode {
    APPEND,
    OVERWRITE
}
