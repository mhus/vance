package de.mhus.vance.shared.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

/**
 * Verifies the resolver hits the expected SettingService cascades and
 * defaults correctly. The cascade logic itself is owned by
 * {@link SettingService}; we only check that the resolver delegates to
 * the right method per concept.
 */
class LanguageResolverTest {

    private final SettingService settingService = mock(SettingService.class);
    private final LanguageResolver resolver = new LanguageResolver(settingService);

    @Test
    void chatLanguage_usesUserProjectCascade() {
        when(settingService.getStringValueUserProjectCascade(
                "tenant", "alice", "proj", "p-1", LanguageResolver.Keys.CHAT_LANGUAGE))
                .thenReturn("de");

        assertThat(resolver.chatLanguage("tenant", "alice", "proj", "p-1")).isEqualTo("de");
        verify(settingService).getStringValueUserProjectCascade(
                eq("tenant"), eq("alice"), eq("proj"), eq("p-1"),
                eq(LanguageResolver.Keys.CHAT_LANGUAGE));
    }

    @Test
    void chatLanguage_fallsBackToDefault_whenUnset() {
        when(settingService.getStringValueUserProjectCascade(
                "tenant", "alice", "proj", null, LanguageResolver.Keys.CHAT_LANGUAGE))
                .thenReturn(null);

        assertThat(resolver.chatLanguage("tenant", "alice", "proj", null))
                .isEqualTo(LanguageResolver.DEFAULT_LANGUAGE);
    }

    @Test
    void contentLanguage_usesProjectCascade_skippingUserLayer() {
        when(settingService.getStringValueCascade(
                "tenant", "proj", "p-1", LanguageResolver.Keys.CONTENT_LANGUAGE))
                .thenReturn("en");

        assertThat(resolver.contentLanguage("tenant", "proj", "p-1")).isEqualTo("en");
        verify(settingService).getStringValueCascade(
                eq("tenant"), eq("proj"), eq("p-1"),
                eq(LanguageResolver.Keys.CONTENT_LANGUAGE));
    }

    @Test
    void contentLanguage_fallsBackToDefault_whenUnset() {
        when(settingService.getStringValueCascade(
                "tenant", null, null, LanguageResolver.Keys.CONTENT_LANGUAGE))
                .thenReturn(null);

        assertThat(resolver.contentLanguage("tenant", null, null))
                .isEqualTo(LanguageResolver.DEFAULT_LANGUAGE);
    }

    @Test
    void findVariants_returnNull_whenAbsent() {
        when(settingService.getStringValueUserProjectCascade(
                "tenant", null, null, null, LanguageResolver.Keys.CHAT_LANGUAGE))
                .thenReturn(null);
        when(settingService.getStringValueCascade(
                "tenant", null, null, LanguageResolver.Keys.CONTENT_LANGUAGE))
                .thenReturn(null);

        assertThat(resolver.findChatLanguage("tenant", null, null, null)).isNull();
        assertThat(resolver.findContentLanguage("tenant", null, null)).isNull();
    }

    @Test
    void blankValue_treatedAsAbsent_andFallsBack() {
        when(settingService.getStringValueUserProjectCascade(
                "tenant", "alice", "proj", null, LanguageResolver.Keys.CHAT_LANGUAGE))
                .thenReturn("   ");

        assertThat(resolver.chatLanguage("tenant", "alice", "proj", null))
                .isEqualTo(LanguageResolver.DEFAULT_LANGUAGE);
    }

    @Test
    void webuiLanguage_usesUserOnlyLookup() {
        when(settingService.getUserStringValue("tenant", "alice", LanguageResolver.Keys.WEBUI_LANGUAGE))
                .thenReturn("zh");

        assertThat(resolver.findWebuiLanguage("tenant", "alice")).isEqualTo("zh");
        verify(settingService).getUserStringValue(
                eq("tenant"), eq("alice"), eq(LanguageResolver.Keys.WEBUI_LANGUAGE));
    }
}
