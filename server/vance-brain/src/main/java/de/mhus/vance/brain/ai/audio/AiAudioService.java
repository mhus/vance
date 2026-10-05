package de.mhus.vance.brain.ai.audio;

import de.mhus.vance.brain.ai.ProviderType;
import de.mhus.vance.shared.document.AudioDestinationStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Dispatches an {@link AiAudioConfig} to the matching
 * {@link AiAudioModelProvider} and runs the call against the supplied
 * destination / source.
 *
 * <p><b>Dispatch order — instance before protocol</b>, same shape and
 * same reason as {@code AiImageService}: OpenRouter's chat wire is
 * OpenAI, but its audio APIs are dedicated endpoints — the adapter is
 * selected by the instance label, not the protocol. Instances without
 * a dedicated provider keep dispatching on the protocol type; duplicate
 * type/instance keys fail fast at startup.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AiAudioService {

    private final List<AiAudioModelProvider> providerBeans;
    private Map<ProviderType, AiAudioModelProvider> providers;
    private Map<String, AiAudioModelProvider> instanceProviders;

    @jakarta.annotation.PostConstruct
    public void postConstruct() {
        this.providers = providerBeans.stream()
                .filter(p -> p.getInstanceName().isEmpty())
                .collect(Collectors.toUnmodifiableMap(AiAudioModelProvider::getType, p -> p, (a, b) -> {
                    throw new IllegalStateException("Duplicate AiAudioModelProvider type: " + a.getType() + " — "
                            + a.getClass() + " vs " + b.getClass());
                }));
        this.instanceProviders = providerBeans.stream()
                .filter(p -> p.getInstanceName().isPresent())
                .collect(Collectors.toUnmodifiableMap(p -> p.getInstanceName().orElseThrow(), p -> p, (a, b) -> {
                    throw new IllegalStateException("Duplicate AiAudioModelProvider instance: "
                            + a.getInstanceName().orElseThrow()
                            + " — " + a.getClass() + " vs " + b.getClass());
                }));
        log.info("Registered AI audio providers: {} (instances: {})", providers.keySet(), instanceProviders.keySet());
    }

    /** Text-to-speech. See {@link AiAudioModelProvider#synthesize}. */
    public void synthesize(AiAudioConfig config, TtsRequest request, AudioDestinationStream destination) {
        providerFor(config).synthesize(config, request, destination);
    }
    /** Transcription. See {@link AiAudioModelProvider#transcribe}. */
    public SttResult transcribe(AiAudioConfig config, AudioSource source, @Nullable String language) {
        return providerFor(config).transcribe(config, source, language);
    }

    /** Music / non-speech audio generation. */
    public void generateMusic(
            AiAudioConfig config, String prompt, int durationSeconds, AudioDestinationStream destination) {
        providerFor(config).generateMusic(config, prompt, durationSeconds, destination);
    }

    /**
     * Instance dispatch first, protocol dispatch second — see the class
     * doc for why the OpenRouter audio adapter cannot hang off the
     * {@code openai} protocol key.
     */
    private AiAudioModelProvider providerFor(AiAudioConfig config) {
        AiAudioModelProvider byInstance = instanceProviders.get(config.providerInstance());
        if (byInstance != null) {
            return byInstance;
        }
        ProviderType type = ProviderType.requireWireName(config.provider());
        AiAudioModelProvider byType = providers.get(type);
        if (byType == null) {
            throw new AiAudioException("No audio adapter for provider " + config.provider()
                    + (config.providerInstance().equals(config.provider())
                            ? ""
                            : " (instance " + config.providerInstance() + ")")
                    + " — registered: " + providers.keySet()
                    + (instanceProviders.isEmpty() ? "" : ", instances: " + instanceProviders.keySet()));
        }
        return byType;
    }

    /** Wire-names of all registered audio providers, in no particular order. */
    public List<String> listProviders() {
        return providers.keySet().stream().map(ProviderType::wireName).toList();
    }

    /** Typed lookup. */
    public boolean hasProvider(ProviderType type) {
        return providers.containsKey(type);
    }

    /** Wire-name lookup. Returns {@code false} for unknown wire-names. */
    public boolean hasProvider(String name) {
        Optional<ProviderType> type = ProviderType.fromWireName(name);
        return type.isPresent() && providers.containsKey(type.get());
    }
}
