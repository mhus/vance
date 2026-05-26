package de.mhus.vance.brain.tools.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.mhus.vance.brain.tools.web.ImageSearchTool.RawResult;
import de.mhus.vance.shared.settings.SettingService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests covering the pure transformations on
 * {@link ImageSearchTool}: Serper JSON parsing and the {@code num}
 * clamp. The HTTP path is left for an integration-style test
 * (deferred — requires a SerperClient extraction so the HTTP
 * boundary can be stubbed).
 */
class ImageSearchToolTest {

    private ImageSearchTool tool;

    @BeforeEach
    void setUp() {
        tool = new ImageSearchTool(
                mock(SettingService.class),
                new ObjectMapper(),
                mock(ImageValidatorService.class));
    }

    @Test
    void parseSerper_extractsAllFields() throws Exception {
        String json = """
                {
                  "images": [
                    {
                      "title": "Lisbon tram 28",
                      "imageUrl": "https://example.com/tram.jpg",
                      "thumbnailUrl": "https://example.com/thumbs/tram.jpg",
                      "source": "Lisbon Guide",
                      "link": "https://example.com/articles/tram"
                    }
                  ]
                }
                """;
        List<RawResult> results = tool.parseSerper(json);
        assertThat(results).hasSize(1);
        RawResult r = results.get(0);
        assertThat(r.title).isEqualTo("Lisbon tram 28");
        assertThat(r.imageUrl).isEqualTo("https://example.com/tram.jpg");
        assertThat(r.thumbnailUrl).isEqualTo("https://example.com/thumbs/tram.jpg");
        assertThat(r.source).isEqualTo("Lisbon Guide");
        assertThat(r.sourceLink).isEqualTo("https://example.com/articles/tram");
    }

    @Test
    void parseSerper_skipsRowsWithoutImageUrl() throws Exception {
        // Serper occasionally returns metadata-only rows for
        // licensed previews — these can't drive an embed, so we
        // drop them before the validator ever sees the batch.
        String json = """
                {
                  "images": [
                    { "title": "A", "imageUrl": "https://a.example/x.jpg" },
                    { "title": "B (no url)" },
                    { "title": "C", "imageUrl": "https://c.example/x.png" }
                  ]
                }
                """;
        List<RawResult> results = tool.parseSerper(json);
        assertThat(results).hasSize(2);
        assertThat(results.get(0).title).isEqualTo("A");
        assertThat(results.get(1).title).isEqualTo("C");
    }

    @Test
    void parseSerper_missingArray_returnsEmpty() throws Exception {
        assertThat(tool.parseSerper("{}")).isEmpty();
        assertThat(tool.parseSerper("{\"images\":null}")).isEmpty();
    }

    @Test
    void clampNum_appliesDefaultAndBounds() {
        assertThat(ImageSearchTool.clampNum(null)).isEqualTo(5);
        assertThat(ImageSearchTool.clampNum("")).isEqualTo(5);
        assertThat(ImageSearchTool.clampNum("not-a-number")).isEqualTo(5);
        assertThat(ImageSearchTool.clampNum(0)).isEqualTo(1);
        assertThat(ImageSearchTool.clampNum(-3)).isEqualTo(1);
        assertThat(ImageSearchTool.clampNum(7)).isEqualTo(7);
        assertThat(ImageSearchTool.clampNum(99)).isEqualTo(10); // capped at MAX_NUM
        assertThat(ImageSearchTool.clampNum("3")).isEqualTo(3);
    }
}
