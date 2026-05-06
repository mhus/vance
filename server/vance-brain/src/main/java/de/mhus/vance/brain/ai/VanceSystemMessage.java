package de.mhus.vance.brain.ai;

import dev.langchain4j.data.message.SystemMessage;

/**
 * langchain4j {@link SystemMessage} extended with a
 * {@link SystemBlockKind} tag. The Anthropic adapter reads the tag to
 * decide where to plant the {@code cache_control} marker.
 *
 * <p>Engines that build their prompt as a single string and hand it to
 * {@link SystemMessage#from(String)} continue to work — the mapper
 * treats those as {@link SystemBlockKind#STATIC} (safe default).
 * Engines that want to keep a dynamic tail (memory, todos, …) outside
 * the cache prefix split their prompt into multiple system messages
 * and use this class for the dynamic ones:
 *
 * <pre>{@code
 * messages.add(SystemMessage.from(staticPrompt));                       // STATIC
 * messages.add(SystemMessage.from(skillsBlock));                        // STATIC
 * messages.add(new VanceSystemMessage(workingMemory,
 *                                     SystemBlockKind.DYNAMIC));         // → after marker
 * messages.add(new VanceSystemMessage(planTodos,
 *                                     SystemBlockKind.DYNAMIC));         // → after marker
 * }</pre>
 *
 * <p>Marker placement is then "last STATIC block":
 * {@link AnthropicRequestMapper} sets {@code cache_control} on the
 * {@code skillsBlock} above, so the {@code workingMemory} +
 * {@code planTodos} blocks change every turn without breaking the
 * cache hash.
 *
 * <p><b>equals/hashCode caveat.</b> langchain4j's {@link SystemMessage}
 * compares by {@code text}; we intentionally <i>don't</i> override
 * those — the tag is metadata about how to serialise the block, not
 * part of its identity. If you put two messages with identical text
 * but different kinds in a {@code Set}, they collapse. That's a code
 * bug at the call site, not at this layer.
 */
public class VanceSystemMessage extends SystemMessage {

    private final SystemBlockKind kind;

    public VanceSystemMessage(String text, SystemBlockKind kind) {
        super(text);
        this.kind = kind == null ? SystemBlockKind.STATIC : kind;
    }

    /** Static factory mirroring {@link SystemMessage#from(String)}. */
    public static VanceSystemMessage of(String text, SystemBlockKind kind) {
        return new VanceSystemMessage(text, kind);
    }

    /** Convenience: a dynamic block. Reads cleaner at the call site. */
    public static VanceSystemMessage dynamic(String text) {
        return new VanceSystemMessage(text, SystemBlockKind.DYNAMIC);
    }

    public SystemBlockKind kind() {
        return kind;
    }
}
