package de.mhus.vance.brain.tools.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.mhus.vance.brain.tools.web.VideoSearchTool.RawResult;
import de.mhus.vance.shared.settings.SettingService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for the pure parts of {@link VideoSearchTool}: Serper
 * /videos JSON parsing and the {@code num} clamp. The HTTP-driven
 * path is left for a future integration-style test once the Serper
 * HTTP boundary is extracted into its own client.
 */
class VideoSearchToolTest {

    private VideoSearchTool tool;

    @BeforeEach
    void setUp() {
        tool = new VideoSearchTool(
                mock(SettingService.class),
                new ObjectMapper(),
                mock(YouTubeValidatorService.class));
    }

    @Test
    void parseSerper_extractsAllVideoFields() throws Exception {
        String json = """
                {
                  "videos": [
                    {
                      "title": "Lisbon Walking Tour 4K",
                      "link": "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                      "snippet": "Walking through Alfama at sunset...",
                      "imageUrl": "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
                      "duration": "12:34",
                      "source": "YouTube",
                      "channel": "Wander Lisbon",
                      "date": "2 months ago"
                    }
                  ]
                }
                """;
        List<RawResult> rows = tool.parseSerper(json);
        assertThat(rows).hasSize(1);
        RawResult r = rows.get(0);
        assertThat(r.title).isEqualTo("Lisbon Walking Tour 4K");
        assertThat(r.videoLink).isEqualTo("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        assertThat(r.thumbnailUrl).isEqualTo("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg");
        assertThat(r.duration).isEqualTo("12:34");
        assertThat(r.channel).isEqualTo("Wander Lisbon");
        assertThat(r.date).isEqualTo("2 months ago");
    }

    @Test
    void parseSerper_skipsRowsWithoutLink() throws Exception {
        // Serper occasionally returns sparse entries — no link, no
        // way to derive a video id, so drop them before they reach
        // the validator.
        String json = """
                {
                  "videos": [
                    { "title": "A", "link": "https://youtu.be/abc11111111" },
                    { "title": "B (no link)" },
                    { "title": "C", "link": "https://youtu.be/ccc22222222" }
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
        assertThat(tool.parseSerper("{\"videos\":null}")).isEmpty();
    }

    @Test
    void clampNum_appliesDefaultAndBounds() {
        assertThat(VideoSearchTool.clampNum(null)).isEqualTo(5);
        assertThat(VideoSearchTool.clampNum("")).isEqualTo(5);
        assertThat(VideoSearchTool.clampNum("not-a-number")).isEqualTo(5);
        assertThat(VideoSearchTool.clampNum(0)).isEqualTo(1);
        assertThat(VideoSearchTool.clampNum(-3)).isEqualTo(1);
        assertThat(VideoSearchTool.clampNum(7)).isEqualTo(7);
        assertThat(VideoSearchTool.clampNum(99)).isEqualTo(10); // capped
        assertThat(VideoSearchTool.clampNum("3")).isEqualTo(3);
    }
}
