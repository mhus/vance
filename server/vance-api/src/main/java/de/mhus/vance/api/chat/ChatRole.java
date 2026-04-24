package de.mhus.vance.api.chat;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Role of a chat message — standard LLM three-role model.
 *
 * <p>Further roles ({@code TASK_NOTIFICATION} for sibling-process events,
 * {@code TOOL} for tool-call results) will be added when those features
 * arrive.
 */
@GenerateTypeScript("chat")
public enum ChatRole {
    USER,
    ASSISTANT,
    SYSTEM
}
