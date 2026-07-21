package de.mhus.vance.shared.document.kind.validate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregated result of validating one document / content blob against its
 * kind. {@code target} names what was checked (the document path when known,
 * else the kind name). The response shape mirrors
 * {@code WorkbookValidationService.Result.toMap()} exactly — the
 * {@code kind_validate} tool and the {@code workbook_validate} tool return the
 * same {@code { target, ok, errors, warnings, findings[] }} envelope.
 *
 * <p>Warnings never flip {@link #ok()}; only {@code ERROR}-level findings do.
 */
public record KindValidationResult(String target, List<Finding> findings) {

    /** Count of {@code ERROR}-level findings. */
    public long errors() {
        return findings.stream().filter(f -> f.level() == Finding.Level.ERROR).count();
    }

    /** Count of {@code WARNING}-level findings. */
    public long warnings() {
        return findings.size() - errors();
    }

    /** {@code true} iff there are no {@code ERROR}-level findings. */
    public boolean ok() {
        return errors() == 0;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("target", target);
        m.put("ok", ok());
        m.put("errors", errors());
        m.put("warnings", warnings());
        m.put("findings", findings.stream().map(Finding::toMap).toList());
        return m;
    }
}
