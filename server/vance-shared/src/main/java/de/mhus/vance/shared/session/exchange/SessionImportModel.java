package de.mhus.vance.shared.session.exchange;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.thinkprocess.PromptMode;
import de.mhus.vance.shared.memory.MemoryKind;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Value types for session import: the request/result envelopes plus the
 * format-neutral intermediate model that both parsers
 * ({@link VanceExportParser}, {@link ClaudeExportParser}) produce and
 * {@link SessionExchangeService#importSession} consumes.
 */
public final class SessionImportModel {

    private SessionImportModel() {}

    /** Which on-disk format the input is. {@code AUTO} sniffs the first lines. */
    public enum ImportFormat { AUTO, VANCE, CLAUDE }

    /**
     * Target coordinates + options for an import. {@code engine}/{@code recipe}
     * only matter for CLAUDE input (VANCE input carries its own resolved chat
     * process). {@code asMemory} additionally seeds an {@code ARCHIVED_CHAT}
     * memory with the transcript.
     */
    public record ImportRequest(
            String tenantId,
            String projectId,
            String userId,
            @Nullable String displayName,
            @Nullable String engine,
            @Nullable String recipe,
            @Nullable String title,
            ImportFormat format,
            boolean asMemory) {}

    /** Outcome of an import — the new session id and what was written. */
    public record ImportResult(
            String sessionId,
            int messageCount,
            int memoryCount,
            String detectedFormat,
            @Nullable String title) {}

    /**
     * One chat turn, format-neutral. {@code sourceId} is the original
     * message id (VANCE input) so memory {@code sourceRefs} and
     * {@code archivedInMemoryId} links can be remapped; null for CLAUDE.
     */
    public record ImportedTurn(
            @Nullable String sourceId,
            ChatRole role,
            String content,
            @Nullable String thinking,
            @Nullable Instant at,
            Set<String> tags,
            Map<String, Object> meta,
            @Nullable String archivedInMemorySourceId) {}

    /** One memory (VANCE input only). Ids are original, remapped on insert. */
    public record ImportedMemory(
            @Nullable String sourceId,
            MemoryKind kind,
            @Nullable String title,
            String content,
            List<String> sourceRefIds,
            @Nullable String supersededBySourceId,
            @Nullable Instant at,
            Map<String, Object> metadata) {}

    /**
     * Resolved chat-process snapshot for VANCE input — reconstructed
     * verbatim so the imported session stays continuable without a Brain
     * recipe resolution. Null in the parsed result means "synthesise from
     * the request engine/recipe" (the CLAUDE path).
     */
    public record ChatProcessSpec(
            String engine,
            @Nullable String thinkEngineVersion,
            @Nullable String recipeName,
            Map<String, Object> engineParams,
            @Nullable String promptOverride,
            @Nullable String promptOverrideAppend,
            PromptMode promptMode,
            @Nullable Set<String> allowedToolsOverride,
            List<String> skillNames) {}

    /** Format-neutral parse result consumed by the importer. */
    public record ParsedImport(
            @Nullable String title,
            @Nullable String displayName,
            @Nullable String profile,
            @Nullable ChatProcessSpec chatProcess,
            List<ImportedTurn> turns,
            List<ImportedMemory> memories) {}
}
