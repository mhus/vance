package de.mhus.vance.brain.ai.parser;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Spring-wired lookup table for {@link MessageParser} beans. Resolves
 * the name carried in {@code ModelInfo.messageParser()} (which itself
 * comes from a per-model YAML override or the bundled
 * {@code model-quirks.yaml} pattern fallback) to the concrete bean.
 *
 * <p>Construction logs the registered set so an operator can sanity-
 * check at boot that the expected parsers are present, and warns on
 * duplicate {@code name()} values (only the first wins — duplicate
 * registration is a configuration bug, not a runtime error).
 */
@Component
@Slf4j
public class MessageParserRegistry {

    private final Map<String, MessageParser> byName;

    public MessageParserRegistry(List<MessageParser> parsers) {
        Map<String, MessageParser> acc = new LinkedHashMap<>();
        for (MessageParser parser : parsers) {
            String name = parser.name();
            if (name == null || name.isBlank()) {
                log.warn("MessageParserRegistry: ignoring parser '{}' with blank name()",
                        parser.getClass().getName());
                continue;
            }
            MessageParser previous = acc.putIfAbsent(name, parser);
            if (previous != null) {
                log.warn("MessageParserRegistry: duplicate name='{}' — keeping {}, ignoring {}",
                        name, previous.getClass().getSimpleName(),
                        parser.getClass().getSimpleName());
            }
        }
        this.byName = Map.copyOf(acc);
        if (byName.isEmpty()) {
            log.info("MessageParserRegistry: no parsers registered");
        } else {
            log.info("MessageParserRegistry: registered {}", byName.keySet());
        }
    }

    /**
     * Returns the parser registered under {@code name}, or empty when
     * the name is unknown / blank. Unknown names are not an error —
     * callers (the chat-model decorator) treat absence as "no
     * transformation" and pass the response through.
     */
    public Optional<MessageParser> get(@Nullable String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byName.get(name));
    }

    /** For diagnostic / admin endpoints. */
    public java.util.Set<String> names() {
        return byName.keySet();
    }
}
