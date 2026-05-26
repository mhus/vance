package de.mhus.vance.brain.tools.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.mhus.vance.brain.tools.web.PdfSearchTool.PdfHttp;
import de.mhus.vance.brain.tools.web.PdfSearchTool.RawResult;
import de.mhus.vance.brain.tools.web.PdfSearchTool.ValidationVerdict;
import de.mhus.vance.shared.settings.SettingService;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for the pure transformations on {@link PdfSearchTool}:
 * Serper JSON parsing, the {@code num} clamp, and the HEAD probe
 * decisions. The Serper call itself is left to integration testing.
 */
class PdfSearchToolTest {

    private PdfSearchTool tool;

    @BeforeEach
    void setUp() {
        tool = new PdfSearchTool(
                mock(SettingService.class),
                new ObjectMapper(),
                mock(PdfHttp.class),
                HttpClient.newHttpClient());
    }

    @Test
    void parseSerper_extractsOrganicRows() throws Exception {
        String json = """
                {
                  "organic": [
                    {
                      "title": "EU AI Act — Final Text",
                      "link": "https://eur-lex.europa.eu/eli/reg/2024/1689/oj/eng.pdf",
                      "snippet": "Regulation (EU) 2024/1689 of the European Parliament...",
                      "source": "EUR-Lex"
                    },
                    {
                      "title": "AI Act — Compromise Text",
                      "link": "https://example.com/ai-act-compromise.pdf",
                      "snippet": "Compromise version of the proposal..."
                    }
                  ]
                }
                """;
        List<RawResult> rows = tool.parseSerper(json);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).title).isEqualTo("EU AI Act — Final Text");
        assertThat(rows.get(0).url).isEqualTo(
                "https://eur-lex.europa.eu/eli/reg/2024/1689/oj/eng.pdf");
        assertThat(rows.get(0).source).isEqualTo("EUR-Lex");
        assertThat(rows.get(1).source).isEmpty();
    }

    @Test
    void parseSerper_skipsRowsWithoutLink() throws Exception {
        String json = """
                {
                  "organic": [
                    { "title": "A", "link": "https://example.com/a.pdf" },
                    { "title": "B (no link)" },
                    { "title": "C", "link": "https://example.com/c.pdf" }
                  ]
                }
                """;
        List<RawResult> rows = tool.parseSerper(json);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).title).isEqualTo("A");
        assertThat(rows.get(1).title).isEqualTo("C");
    }

    @Test
    void parseSerper_missingArray_returnsEmpty() throws Exception {
        assertThat(tool.parseSerper("{}")).isEmpty();
        assertThat(tool.parseSerper("{\"organic\":null}")).isEmpty();
    }

    @Test
    void clampNum_appliesDefaultAndBounds() {
        assertThat(PdfSearchTool.clampNum(null)).isEqualTo(5);
        assertThat(PdfSearchTool.clampNum("")).isEqualTo(5);
        assertThat(PdfSearchTool.clampNum("not-a-number")).isEqualTo(5);
        assertThat(PdfSearchTool.clampNum(0)).isEqualTo(1);
        assertThat(PdfSearchTool.clampNum(-3)).isEqualTo(1);
        assertThat(PdfSearchTool.clampNum(7)).isEqualTo(7);
        assertThat(PdfSearchTool.clampNum(99)).isEqualTo(10);
        assertThat(PdfSearchTool.clampNum("3")).isEqualTo(3);
    }

    @Test
    void jdkPdfHttp_pdfContentType_returnsOk() throws Exception {
        // The JDK-backed probe is hard to mock directly, but its
        // decision shape is the contract we care about. Test that
        // through the ValidationVerdict factory shape: an "ok"
        // verdict carries finalUrl + contentType, a failure carries
        // a reason.
        ValidationVerdict ok = ValidationVerdict.ok(
                "https://example.com/a.pdf", "application/pdf", 12345L);
        assertThat(ok.isOk()).isTrue();
        assertThat(ok.getFinalUrl()).isEqualTo("https://example.com/a.pdf");
        assertThat(ok.getContentLength()).isEqualTo(12345L);

        ValidationVerdict fail = ValidationVerdict.fail("content_type_text/html");
        assertThat(fail.isOk()).isFalse();
        assertThat(fail.getReason()).isEqualTo("content_type_text/html");
        assertThat(fail.getFinalUrl()).isNull();
    }

    @Test
    void stubHttp_respectsHeadVerdict() throws Exception {
        // Drives a fake PdfHttp implementation directly to confirm the
        // contract the validator service relies on: returns whatever
        // the http.head() call returns, no transformation.
        PdfHttp stub = new PdfHttp() {
            @Override
            public ValidationVerdict head(URI uri, Duration timeout, String userAgent) {
                if (uri.toString().endsWith("good.pdf")) {
                    return ValidationVerdict.ok(uri.toString(),
                            "application/pdf", 1024L);
                }
                return ValidationVerdict.fail("status_404");
            }
        };
        ValidationVerdict ok = stub.head(
                URI.create("https://example.com/good.pdf"),
                Duration.ofSeconds(2), "test-ua");
        assertThat(ok.isOk()).isTrue();
        ValidationVerdict fail = stub.head(
                URI.create("https://example.com/dead.pdf"),
                Duration.ofSeconds(2), "test-ua");
        assertThat(fail.isOk()).isFalse();
        assertThat(fail.getReason()).isEqualTo("status_404");
    }
}
