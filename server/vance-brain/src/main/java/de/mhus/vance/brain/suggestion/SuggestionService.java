package de.mhus.vance.brain.suggestion;

import de.mhus.vance.api.form.FormSuggestionDto;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Registry of the installed {@link SuggestionSource}s. */
@Service
@Slf4j
public class SuggestionService {

    static final int MAX_LIMIT = 25;

    private final Map<String, SuggestionSource> sources = new LinkedHashMap<>();

    public SuggestionService(List<SuggestionSource> sources) {
        for (SuggestionSource s : sources) {
            SuggestionSource previous = this.sources.putIfAbsent(s.name(), s);
            if (previous != null) {
                throw new IllegalStateException("Two suggestion sources named '" + s.name() + "': "
                        + previous.getClass().getName() + " and " + s.getClass().getName());
            }
        }
        log.debug("SuggestionService: sources {}", this.sources.keySet());
    }

    /** Suggestions of one source; an unknown source or a blank query answers none. */
    public List<FormSuggestionDto> suggest(String source, SuggestionRequest request) {
        SuggestionSource s = sources.get(source);
        if (s == null || request.query().isBlank()) return List.of();
        List<FormSuggestionDto> out = s.suggest(request);
        return out.size() > request.limit() ? out.subList(0, request.limit()) : out;
    }
}
