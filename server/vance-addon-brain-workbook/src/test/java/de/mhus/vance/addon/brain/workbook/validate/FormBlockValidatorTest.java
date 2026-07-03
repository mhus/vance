package de.mhus.vance.addon.brain.workbook.validate;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FormBlockValidator} using an in-memory {@link DocRefs}
 * — no Spring, no Mongo. Covers the failure paths the log analysis surfaced
 * (missing config, wrong script extension, legacy $meta, bad field type) plus
 * the clean case.
 */
class FormBlockValidatorTest {

    private static final String PAGE = "apps/g/page.workpage.md";
    private final FormBlockValidator validator = new FormBlockValidator();

    @Test
    void cleanForm_producesNoErrors() {
        FakeDocRefs docs = new FakeDocRefs();
        docs.put("apps/g/data/x.records.json", "records", null);
        docs.put("apps/g/calc.js", "javascript", null);

        List<Finding> f = validator.validate(formBlock(
                "vance:data/x.records.json?kind=records", "vance:calc.js",
                fields(Map.of("name", "note", "type", "integer"))), ctx(docs));

        assertThat(errors(f)).isEmpty();
    }

    @Test
    void missingConfig_isError() {
        List<Finding> f = validator.validate(
                formBlock(null, null, fields(Map.of("name", "n", "type", "string"))),
                ctx(new FakeDocRefs()));
        assertThat(codes(f)).contains("missing-config");
    }

    @Test
    void configWrongKind_warns() {
        FakeDocRefs docs = new FakeDocRefs();
        docs.put("apps/g/data/x.records.json", "text", null);   // not records
        List<Finding> f = validator.validate(formBlock(
                "vance:data/x.records.json?kind=records", null,
                fields(Map.of("name", "n", "type", "string"))), ctx(docs));
        assertThat(codes(f)).contains("kind-mismatch-config");
    }

    @Test
    void saveScriptNotJs_isError() {
        FakeDocRefs docs = new FakeDocRefs();
        docs.put("apps/g/data/x.records.json", "records", null);
        docs.put("apps/g/calc.py", "python", null);
        List<Finding> f = validator.validate(formBlock(
                "vance:data/x.records.json?kind=records", "vance:calc.py",
                fields(Map.of("name", "n", "type", "string"))), ctx(docs));
        assertThat(codes(f)).contains("not-js-saveScript");
    }

    @Test
    void badFieldType_isError() {
        FakeDocRefs docs = new FakeDocRefs();
        docs.put("apps/g/data/x.records.json", "records", null);
        List<Finding> f = validator.validate(formBlock(
                "vance:data/x.records.json?kind=records", null,
                fields(Map.of("name", "n", "type", "number"))), ctx(docs));   // no 'number'
        assertThat(codes(f)).contains("field-bad-type");
    }

    @Test
    void legacyMetaOnDataDoc_isError() {
        FakeDocRefs docs = new FakeDocRefs();
        docs.put("apps/g/data/x.records.json", "records",
                Map.of("$meta", Map.of("form", Map.of("single", true))));
        List<Finding> f = validator.validate(formBlock(
                "vance:data/x.records.json?kind=records", null,
                fields(Map.of("name", "n", "type", "string"))), ctx(docs));
        assertThat(codes(f)).contains("legacy-meta");
    }

    // ---- helpers -------------------------------------------------------

    private static ValidationContext ctx(DocRefs docs) {
        return new ValidationContext(PAGE, docs);
    }

    private static List<Map<String, Object>> fields(Map<String, Object> field) {
        return List.of(new LinkedHashMap<>(field));
    }

    private static FenceBlock formBlock(
            @Nullable String config, @Nullable String saveScript,
            List<Map<String, Object>> fields) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        if (config != null) attrs.put("config", config);
        if (saveScript != null) attrs.put("saveScript", saveScript);
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("single", false);
        form.put("fields", fields);
        attrs.put("form", form);
        return new FenceBlock("form", attrs, "", PAGE, 1, null);
    }

    private static List<Finding> errors(List<Finding> f) {
        return f.stream().filter(x -> x.level() == Finding.Level.ERROR).toList();
    }

    private static Set<String> codes(List<Finding> f) {
        return f.stream().map(Finding::code).collect(java.util.stream.Collectors.toSet());
    }

    /** In-memory DocRefs: existence + kind + optional YAML per path. */
    private static final class FakeDocRefs implements DocRefs {
        private final Set<String> paths = new HashSet<>();
        private final Map<String, String> kinds = new HashMap<>();
        private final Map<String, Map<String, Object>> yaml = new HashMap<>();

        void put(String path, @Nullable String kind, @Nullable Map<String, Object> y) {
            paths.add(path);
            if (kind != null) kinds.put(path, kind);
            if (y != null) yaml.put(path, y);
        }

        @Override public boolean exists(String path) { return paths.contains(path); }
        @Override public @Nullable String kindOf(String path) { return kinds.get(path); }
        @Override public @Nullable Map<String, Object> readYaml(String path) { return yaml.get(path); }
    }
}
