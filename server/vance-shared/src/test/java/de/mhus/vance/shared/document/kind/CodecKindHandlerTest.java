package de.mhus.vance.shared.document.kind;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.document.kind.validate.DocRefs;
import de.mhus.vance.shared.document.kind.validate.Finding;
import de.mhus.vance.shared.document.kind.validate.KindValidationContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Pure handler-logic test: parse-ok → no findings; parse-throws → one
 * {@code <kind>-parse} ERROR; and mime falls back to a codec-supported one when
 * the context mime is not accepted. The real codecs are exercised by their own
 * *CodecTest; here the codec is a stub so the test stays deterministic.
 */
class CodecKindHandlerTest {

    private static final DocRefs NO_REFS = new DocRefs() {
        @Override public boolean exists(String path) { return false; }
        @Override public @Nullable String kindOf(String path) { return null; }
        @Override public @Nullable Map<String, Object> readYaml(String path) { return null; }
    };

    private static KindValidationContext ctx(@Nullable String mime) {
        return new KindValidationContext("t", "p", "doc.x", mime, NO_REFS);
    }

    @Test
    void parseSucceeds_hasNoFindings() {
        CodecKindHandler h = new CodecKindHandler("demo", (c, m) -> "parsed", m -> true);
        assertThat(h.validate("anything", ctx("text/markdown"))).isEmpty();
    }

    @Test
    void parseThrows_isKindParseError() {
        CodecKindHandler h = new CodecKindHandler(
                "demo", (c, m) -> { throw new KindCodecException("boom"); }, m -> true);
        List<Finding> f = h.validate("bad", ctx("text/markdown"));
        assertThat(f).hasSize(1);
        assertThat(f.get(0).level()).isEqualTo(Finding.Level.ERROR);
        assertThat(f.get(0).code()).isEqualTo("demo-parse");
        assertThat(f.get(0).message()).contains("boom");
    }

    @Test
    void unsupportedContextMime_fallsBackToASupportedOne() {
        List<String> mimesUsed = new ArrayList<>();
        // codec accepts only yaml; the context mime is markdown → must resolve to yaml.
        CodecKindHandler h = new CodecKindHandler(
                "demo",
                (c, m) -> { mimesUsed.add(m); return "ok"; },
                m -> "application/yaml".equals(m));
        h.validate("some: yaml", ctx("text/markdown"));
        assertThat(mimesUsed).containsExactly("application/yaml");
    }
}
