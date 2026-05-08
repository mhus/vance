package de.mhus.vance.shared.settings;

import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Resolves the three language-related settings consistently across the
 * codebase. Three distinct concepts, three distinct cascades:
 *
 * <ul>
 *   <li>{@link Keys#WEBUI_LANGUAGE} — language of the Web-UI chrome
 *       (buttons, labels). User-private, no cascade.</li>
 *   <li>{@link Keys#CHAT_LANGUAGE} — language the assistant speaks /
 *       listens in. Cascade {@code think-process → _user_<userId> →
 *       <projectId> → _vance}: a user's default, but a project may
 *       override (e.g. an English code-review project owned by a
 *       German-speaking user).</li>
 *   <li>{@link Keys#CONTENT_LANGUAGE} — language Documents / Insights /
 *       Memory entries are written in. Cascade {@code think-process →
 *       <projectId> → _vance}, deliberately <b>without</b> a user
 *       layer: content belongs to the project, not the writer, so
 *       multi-author projects don't end up with three-language
 *       documents.</li>
 * </ul>
 *
 * <p>The legacy {@code context.language} key is gone — it conflated
 * the chat / content distinction. Migration is "manual": replace
 * settings rows by hand or via a one-off script. The resolver does
 * <b>not</b> read the legacy key.
 *
 * <p>Default fallback is {@link #DEFAULT_LANGUAGE} ({@value #DEFAULT_LANGUAGE})
 * when no scope has the key set. Callers that want "no opinion" should
 * use {@link #findChatLanguage} / {@link #findContentLanguage} which
 * return {@code null} on absence.
 */
@Service
@RequiredArgsConstructor
public class LanguageResolver {

    /** Final fallback when neither user nor project nor tenant set a language. */
    public static final String DEFAULT_LANGUAGE = "en";

    /** Setting-key constants — public so callers can read/write directly via SettingService when needed. */
    public static final class Keys {
        /** Web-UI chrome language. Scope: user-only. */
        public static final String WEBUI_LANGUAGE = "webui.language";

        /** Assistant chat language. Scope: project → user → tenant. */
        public static final String CHAT_LANGUAGE = "chat.language";

        /** Document / memory content language. Scope: project → tenant. */
        public static final String CONTENT_LANGUAGE = "content.language";

        private Keys() {}
    }

    private final SettingService settingService;

    /**
     * Resolves {@link Keys#CHAT_LANGUAGE} via the
     * project → user → tenant cascade. {@code null} when nothing is set.
     * Use {@link #chatLanguage} for the version that defaults to
     * {@link #DEFAULT_LANGUAGE}.
     */
    public @Nullable String findChatLanguage(
            String tenantId,
            @Nullable String userId,
            @Nullable String projectId,
            @Nullable String thinkProcessId) {
        return settingService.getStringValueUserProjectCascade(
                tenantId, userId, projectId, thinkProcessId, Keys.CHAT_LANGUAGE);
    }

    /** Same as {@link #findChatLanguage} but falls back to {@link #DEFAULT_LANGUAGE}. */
    public String chatLanguage(
            String tenantId,
            @Nullable String userId,
            @Nullable String projectId,
            @Nullable String thinkProcessId) {
        String v = findChatLanguage(tenantId, userId, projectId, thinkProcessId);
        return v == null || v.isBlank() ? DEFAULT_LANGUAGE : v;
    }

    /**
     * Resolves {@link Keys#CONTENT_LANGUAGE} via the project → tenant
     * cascade. {@code null} when nothing is set. Use
     * {@link #contentLanguage} for the version that defaults to
     * {@link #DEFAULT_LANGUAGE}.
     *
     * <p>The user layer is intentionally <b>not</b> consulted — content
     * belongs to the project.
     */
    public @Nullable String findContentLanguage(
            String tenantId,
            @Nullable String projectId,
            @Nullable String thinkProcessId) {
        return settingService.getStringValueCascade(
                tenantId, projectId, thinkProcessId, Keys.CONTENT_LANGUAGE);
    }

    /** Same as {@link #findContentLanguage} but falls back to {@link #DEFAULT_LANGUAGE}. */
    public String contentLanguage(
            String tenantId,
            @Nullable String projectId,
            @Nullable String thinkProcessId) {
        String v = findContentLanguage(tenantId, projectId, thinkProcessId);
        return v == null || v.isBlank() ? DEFAULT_LANGUAGE : v;
    }

    /**
     * Reads the user-private {@link Keys#WEBUI_LANGUAGE}. {@code null}
     * when the user hasn't picked one — callers default in their own
     * UI layer (the Web-UI does this client-side).
     */
    public @Nullable String findWebuiLanguage(String tenantId, String userId) {
        return settingService.getUserStringValue(tenantId, userId, Keys.WEBUI_LANGUAGE);
    }
}
