package de.mhus.vance.brain.hactar;

import de.mhus.vance.api.hactar.HactarState;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Single owner of the {@link HactarState} ⇄ {@code engineParams} codec: load
 * from (and persist to) the process' {@code deepThoughtState} engine-param
 * slot. Extracted from the engine when the run moved onto a background thread
 * (planning/hactar-agent-identity.md §3.1) — the engine lane and the
 * {@code HactarRunService} runner both need the exact same read/write path.
 *
 * <p>Tolerant load: legacy states without the newer keys deserialize with
 * their builder defaults / {@code null} (wrapper types on additive fields —
 * Zaphod Jackson-3 lesson), never with a primitive-default crash.
 */
@Component
@RequiredArgsConstructor
public class HactarStateStore {

    private final ThinkProcessService thinkProcessService;
    private final tools.jackson.databind.ObjectMapper objectMapper;

    @SuppressWarnings("unchecked")
    public HactarState load(ThinkProcessDocument process) {
        Map<String, Object> p = process.getEngineParams();
        if (p == null) {
            return HactarState.builder().build();
        }
        Object raw = p.get(HactarEngine.STATE_KEY);
        if (raw == null) {
            return HactarState.builder().build();
        }
        return objectMapper.convertValue(raw, HactarState.class);
    }

    @SuppressWarnings("unchecked")
    public void persist(ThinkProcessDocument process, HactarState state) {
        Map<String, Object> existing = process.getEngineParams();
        // Defensive copy: engineParams may arrive as an immutable map
        // (spawn configs use Map.of(...)); the codec owns a mutable one.
        Map<String, Object> p = new LinkedHashMap<>(existing == null ? Map.of() : existing);
        p.put(HactarEngine.STATE_KEY, objectMapper.convertValue(state, Map.class));
        process.setEngineParams(p);
        thinkProcessService.replaceEngineParams(process.getId(), p);
    }
}
