package de.mhus.vance.brain.hotblack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.ai.AiModelResolver;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.ai.audio.AiAudioConfig;
import de.mhus.vance.brain.ai.audio.AiAudioService;
import de.mhus.vance.brain.ai.audio.AnalysisResult;
import de.mhus.vance.brain.ai.audio.AuModelInfo;
import de.mhus.vance.brain.ai.audio.AudioSource;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.brain.progress.ProgressEmitter;
import de.mhus.vance.brain.tools.ToolInterruptChecker;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.settings.LanguageResolver;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Gates and dispatch of {@link HotblackService#analyze} — the same
 * fail-closed contract the transcribe path pins, applied to the
 * {@code au} modality: enabled flags before anything, the catalog kind
 * gate before quota, the format gate before the provider call.
 */
class HotblackServiceAnalyzeTest {

    private static final String TENANT = "acme";
    private static final byte[] AUDIO = new byte[] {1, 2, 3, 4};

    private AudioCallTracker callTracker;
    private AiModelResolver modelResolver;
    private ModelCatalog modelCatalog;
    private AiAudioService audioService;
    private SettingService settingService;
    private LanguageResolver languageResolver;
    private HotblackService service;

    @BeforeEach
    void setUp() {
        callTracker = mock(AudioCallTracker.class);
        modelResolver = mock(AiModelResolver.class);
        modelCatalog = mock(ModelCatalog.class);
        audioService = mock(AiAudioService.class);
        settingService = mock(SettingService.class);
        languageResolver = mock(LanguageResolver.class);
        when(settingService.getBooleanValueCascade(
                        eq(TENANT), any(), any(), eq(HotblackService.SETTING_ENABLED), eq(true)))
                .thenReturn(true);
        when(settingService.getBooleanValueCascade(
                        eq(TENANT), any(), any(), eq(HotblackService.SETTING_AU_ENABLED), eq(true)))
                .thenReturn(true);
        when(languageResolver.chatLanguage(eq(TENANT), any(), any(), any())).thenReturn("de");
        // Every cascade string read (API key, timeout override, …) answers
        // "sk-test" — the key matters (buildConfig fails closed without
        // one), the rest tolerate a non-numeric string and fall back.
        when(settingService.getStringValueCascade(any(), any(), any(), any())).thenReturn("sk-test");
        // The API key travels the password cascade (decrypted) — see
        // ChatBehaviorBuilder.resolveApiKey.
        when(settingService.getDecryptedPasswordCascade(any(), any(), any(), any()))
                .thenReturn("sk-test");
        when(callTracker.reserve(eq(HotblackService.MODALITY_AU), eq(TENANT), any(), any(), any()))
                .thenReturn(new AudioCallTracker.Granted("reserve-1"));
        service = new HotblackService(
                callTracker,
                modelResolver,
                modelCatalog,
                audioService,
                new ToolInterruptChecker(mock(ThinkProcessService.class)),
                mock(LightLlmService.class),
                settingService,
                languageResolver,
                mock(DocumentService.class),
                mock(SecurityContextFactory.class),
                mock(ProgressEmitter.class),
                mock(ThinkProcessService.class),
                mock(de.mhus.vance.brain.ai.UsageSink.class),
                null);
    }

    private static AiModelResolver.Resolved resolved() {
        return new AiModelResolver.Resolved("openai", "openrouter", "mistralai/voxtral-small-24b-2507", false);
    }

    private static AnalyzeAudioRequest request() {
        return AnalyzeAudioRequest.builder()
                .tenantId(TENANT)
                .audioData(AUDIO)
                .audioFormat("mp3")
                .audioName("clip.mp3")
                .prompt("Describe genre and mood.")
                .build();
    }

    @Test
    void analyze_resolves_the_au_alias_and_returns_the_description() {
        when(modelResolver.resolveOrDefault(eq(HotblackService.DEFAULT_AU_ALIAS), eq(TENANT), any(), any()))
                .thenReturn(resolved());
        AuModelInfo info = new AuModelInfo(
                "openrouter", "mistralai/voxtral-small-24b-2507", Set.of("mp3", "wav"), 1800, 0.0001, 120);
        when(modelCatalog.lookupAu(eq(TENANT), any(), eq("openrouter"), eq("mistralai/voxtral-small-24b-2507")))
                .thenReturn(Optional.of(info));
        when(audioService.analyze(any(), any(), any())).thenReturn(new AnalysisResult("Calm piano.", 0.0008));

        AnalyzeAudioResult result = service.analyze(request());

        assertThat(result.getAnalysis()).isEqualTo("Calm piano.");
        assertThat(result.getLanguage()).isEqualTo("de");
        assertThat(result.getModelUsed()).isEqualTo("openrouter:mistralai/voxtral-small-24b-2507");
        assertThat(result.getCostUsd()).isEqualTo(0.0008);
        // The composed prompt fixes the describe-not-transcribe role and the
        // effective language rides along.
        verify(audioService)
                .analyze(
                        any(AiAudioConfig.class),
                        any(AudioSource.class),
                        eq(HotblackService.composeAnalysisPrompt("Describe genre and mood.", "de")));
    }

    @Test
    void analyze_fails_closed_without_an_au_catalog_entry() {
        when(modelResolver.resolveOrDefault(eq(HotblackService.DEFAULT_AU_ALIAS), eq(TENANT), any(), any()))
                .thenReturn(resolved());
        when(modelCatalog.lookupAu(any(), any(), any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.analyze(request()))
                .isInstanceOf(HotblackException.class)
                .hasMessageContaining("kind: au");
        verify(callTracker, never()).reserve(any(), any(), any(), any(), any());
    }

    @Test
    void analyze_rejects_formats_the_model_does_not_accept() {
        when(modelResolver.resolveOrDefault(eq(HotblackService.DEFAULT_AU_ALIAS), eq(TENANT), any(), any()))
                .thenReturn(resolved());
        AuModelInfo wavOnly =
                new AuModelInfo("openrouter", "mistralai/voxtral-small-24b-2507", Set.of("wav"), 1800, null, 120);
        when(modelCatalog.lookupAu(eq(TENANT), any(), eq("openrouter"), eq("mistralai/voxtral-small-24b-2507")))
                .thenReturn(Optional.of(wavOnly));

        assertThatThrownBy(() -> service.analyze(request()))
                .isInstanceOf(HotblackException.class)
                .hasMessageContaining("does not accept audio format 'mp3'");
        verify(audioService, never()).analyze(any(), any(), any());
        verify(callTracker, never()).reserve(any(), any(), any(), any(), any());
    }

    @Test
    void disabled_au_modality_blocks_before_any_lookup() {
        when(settingService.getBooleanValueCascade(
                        eq(TENANT), any(), any(), eq(HotblackService.SETTING_AU_ENABLED), eq(true)))
                .thenReturn(false);

        assertThatThrownBy(() -> service.analyze(request()))
                .isInstanceOf(HotblackException.class)
                .hasMessageContaining("disabled");
        verify(modelResolver, never()).resolveOrDefault(any(), any(), any(), any());
    }

    @Test
    void quota_denial_maps_to_quota_exceeded() {
        when(modelResolver.resolveOrDefault(eq(HotblackService.DEFAULT_AU_ALIAS), eq(TENANT), any(), any()))
                .thenReturn(resolved());
        AuModelInfo info = new AuModelInfo(
                "openrouter", "mistralai/voxtral-small-24b-2507", Set.of("mp3", "wav"), 1800, null, 120);
        when(modelCatalog.lookupAu(eq(TENANT), any(), eq("openrouter"), eq("mistralai/voxtral-small-24b-2507")))
                .thenReturn(Optional.of(info));
        when(callTracker.reserve(eq(HotblackService.MODALITY_AU), eq(TENANT), any(), any(), any()))
                .thenReturn(new AudioCallTracker.Denied(new AudioCallTracker.Verdict(false, "daily", "limit reached")));

        assertThatThrownBy(() -> service.analyze(request()))
                .isInstanceOf(HotblackException.class)
                .hasMessageContaining("limit reached");
        verify(audioService, never()).analyze(any(), any(), any());
    }

    @Test
    void both_audio_sources_present_is_invalid() {
        AnalyzeAudioRequest both = AnalyzeAudioRequest.builder()
                .tenantId(TENANT)
                .documentId("doc-1")
                .audioData(AUDIO)
                .prompt("Describe.")
                .build();

        assertThatThrownBy(() -> service.analyze(both))
                .isInstanceOf(HotblackException.class)
                .hasMessageContaining("exactly one audio source");
    }
}
