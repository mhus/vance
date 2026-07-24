package de.mhus.vance.addon.brain.finance;

import de.mhus.vance.addon.brain.finance.model.FinanceInterest;
import de.mhus.vance.addon.brain.finance.model.FinanceNode;
import de.mhus.vance.addon.brain.finance.model.FinanceTreeDocument;
import de.mhus.vance.addon.brain.finance.model.FinanceValue;
import de.mhus.vance.addon.brain.finance.model.Period;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Maps between the typed {@link FinanceTreeDocument} model and the wire
 * {@link FinanceTreeDto} the Web-UI editor loads/saves.
 *
 * <p>{@code toDto} is a direct projection. {@code fromDto} rebuilds the DTO into
 * the on-disk map grammar and routes it through
 * {@link FinanceTreeCodec#nodeFromMap} — so DTO input goes through the <em>same</em>
 * validation/coercion as YAML/JSON on disk (recurring needs a period, one_time
 * needs a date, unknown units rejected), never a second parallel code path.
 */
public final class FinanceDtoMapper {

    private FinanceDtoMapper() {
        // utility class
    }

    // ── model → DTO ───────────────────────────────────────────────

    public static FinanceTreeDto toDto(FinanceTreeDocument doc) {
        return new FinanceTreeDto(doc.version(), doc.title(), doc.description(),
                doc.root() == null ? null : nodeToDto(doc.root()));
    }

    private static FinanceNodeDto nodeToDto(FinanceNode n) {
        List<FinanceValueDto> values = new ArrayList<>();
        for (FinanceValue v : n.values()) values.add(valueToDto(v));
        List<FinanceNodeDto> children = new ArrayList<>();
        for (FinanceNode c : n.children()) children.add(nodeToDto(c));
        return new FinanceNodeDto(n.name(), n.title(), n.icon(), n.color(), n.sign(),
                n.description(), n.notesRef(), values, children);
    }

    private static FinanceValueDto valueToDto(FinanceValue v) {
        return new FinanceValueDto(
                v.value(),
                v.mode().wire(),
                v.period() == null ? null : periodToDto(v.period()),
                v.validFrom(),
                v.validTo(),
                v.sign(),
                v.interest() == null ? null : interestToDto(v.interest()));
    }

    private static FinancePeriodDto periodToDto(Period p) {
        return new FinancePeriodDto(p.count(), p.unit().wire());
    }

    private static FinanceInterestDto interestToDto(FinanceInterest i) {
        return new FinanceInterestDto(i.rate(), periodToDto(i.period()), i.basis().wire(),
                i.compound());
    }

    // ── DTO → model (via codec grammar) ───────────────────────────

    public static FinanceTreeDocument fromDto(FinanceTreeDto dto) {
        FinanceNode root = dto.root() == null
                ? null
                : FinanceTreeCodec.nodeFromMap(nodeToMap(dto.root()));
        int version = dto.version() <= 0 ? FinanceTreeDocument.CURRENT_VERSION : dto.version();
        return new FinanceTreeDocument(version, dto.title(), dto.description(), root);
    }

    private static Map<String, Object> nodeToMap(FinanceNodeDto n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", n.name());
        putIfNotNull(m, "title", n.title());
        putIfNotNull(m, "icon", n.icon());
        putIfNotNull(m, "color", n.color());
        m.put("sign", n.sign());
        putIfNotNull(m, "description", n.description());
        putIfNotNull(m, "notesRef", n.notesRef());
        List<Map<String, Object>> values = new ArrayList<>();
        for (FinanceValueDto v : n.values()) values.add(valueToMap(v));
        m.put("values", values);
        List<Map<String, Object>> children = new ArrayList<>();
        for (FinanceNodeDto c : n.children()) children.add(nodeToMap(c));
        m.put("children", children);
        return m;
    }

    private static Map<String, Object> valueToMap(FinanceValueDto v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", v.value());
        if (v.mode() != null) m.put("mode", v.mode());
        if (v.period() != null) m.put("period", periodToMap(v.period()));
        putIfNotNull(m, "validFrom", v.validFrom());
        putIfNotNull(m, "validTo", v.validTo());
        if (v.sign() != null) m.put("sign", v.sign());
        if (v.interest() != null) m.put("interest", interestToMap(v.interest()));
        return m;
    }

    private static Map<String, Object> periodToMap(FinancePeriodDto p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", p.count());
        m.put("unit", p.unit());
        return m;
    }

    private static Map<String, Object> interestToMap(FinanceInterestDto i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rate", i.rate());
        if (i.period() != null) m.put("period", periodToMap(i.period()));
        if (i.basis() != null) m.put("basis", i.basis());
        m.put("compound", i.compound());
        return m;
    }

    private static void putIfNotNull(Map<String, Object> m, String key, @Nullable String value) {
        if (value != null) m.put(key, value);
    }
}
