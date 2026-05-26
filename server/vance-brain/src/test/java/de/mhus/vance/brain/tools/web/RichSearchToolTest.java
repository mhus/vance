package de.mhus.vance.brain.tools.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RichSearchTool} — verifies fan-out, the
 * bucketed result shape, and graceful degradation when one of the
 * downstream tools throws.
 */
class RichSearchToolTest {

    private WebSearchTool webSearch;
    private ImageSearchTool imageSearch;
    private VideoSearchTool videoSearch;
    private PdfSearchTool pdfSearch;
    private RichSearchTool tool;
    private ToolInvocationContext ctx;

    @BeforeEach
    void setUp() {
        webSearch = mock(WebSearchTool.class);
        imageSearch = mock(ImageSearchTool.class);
        videoSearch = mock(VideoSearchTool.class);
        pdfSearch = mock(PdfSearchTool.class);
        tool = new RichSearchTool(webSearch, imageSearch, videoSearch, pdfSearch);
        ctx = mock(ToolInvocationContext.class);
    }

    @Test
    void invoke_allFourBuckets_populated() {
        when(webSearch.invoke(any(), eq(ctx))).thenReturn(stubResult("text-A", "text-B"));
        when(imageSearch.invoke(any(), eq(ctx))).thenReturn(stubResult("img-A"));
        when(videoSearch.invoke(any(), eq(ctx))).thenReturn(stubResult("vid-A"));
        when(pdfSearch.invoke(any(), eq(ctx))).thenReturn(stubResult("pdf-A", "pdf-B"));

        Map<String, Object> out = tool.invoke(Map.of("query", "Lissabon"), ctx);

        assertThat(out.get("query")).isEqualTo("Lissabon");
        assertThat(bucketCount(out, "text")).isEqualTo(2);
        assertThat(bucketCount(out, "images")).isEqualTo(1);
        assertThat(bucketCount(out, "videos")).isEqualTo(1);
        assertThat(bucketCount(out, "pdfs")).isEqualTo(2);
    }

    @Test
    void invoke_passesDefaultLimits_perBucket() {
        when(webSearch.invoke(any(), eq(ctx))).thenReturn(stubResult());
        when(imageSearch.invoke(any(), eq(ctx))).thenReturn(stubResult());
        when(videoSearch.invoke(any(), eq(ctx))).thenReturn(stubResult());
        when(pdfSearch.invoke(any(), eq(ctx))).thenReturn(stubResult());

        tool.invoke(Map.of("query", "x"), ctx);

        verify(webSearch).invoke(eq(Map.of(
                "query", "x", "num", RichSearchTool.DEFAULT_TEXT)), eq(ctx));
        verify(imageSearch).invoke(eq(Map.of(
                "query", "x", "num", RichSearchTool.DEFAULT_IMAGES)), eq(ctx));
        verify(videoSearch).invoke(eq(Map.of(
                "query", "x", "num", RichSearchTool.DEFAULT_VIDEOS)), eq(ctx));
        verify(pdfSearch).invoke(eq(Map.of(
                "query", "x", "num", RichSearchTool.DEFAULT_PDFS)), eq(ctx));
    }

    @Test
    void invoke_oneBucketThrows_othersStillSucceed() {
        when(webSearch.invoke(any(), eq(ctx))).thenReturn(stubResult("text-A"));
        when(imageSearch.invoke(any(), eq(ctx)))
                .thenThrow(new RuntimeException("serper down"));
        when(videoSearch.invoke(any(), eq(ctx))).thenReturn(stubResult("vid-A"));
        when(pdfSearch.invoke(any(), eq(ctx))).thenReturn(stubResult("pdf-A"));

        Map<String, Object> out = tool.invoke(Map.of("query", "Lissabon"), ctx);

        assertThat(bucketCount(out, "text")).isEqualTo(1);
        assertThat(bucketCount(out, "videos")).isEqualTo(1);
        assertThat(bucketCount(out, "pdfs")).isEqualTo(1);
        // The failed bucket comes back empty with an error message.
        @SuppressWarnings("unchecked")
        Map<String, Object> images = (Map<String, Object>) out.get("images");
        assertThat(images.get("count")).isEqualTo(0);
        assertThat(images).containsKey("error");
        assertThat(images.get("error").toString()).contains("serper down");
    }

    @Test
    void invoke_strippsPerToolQuery_fromBuckets() {
        // Downstream tools echo the query back in their own result map.
        // The rich bucket should not duplicate it; the rich-search top-
        // level already carries it once.
        Map<String, Object> echo = stubResult("hit-A");
        echo.put("query", "Lissabon");
        when(webSearch.invoke(any(), eq(ctx))).thenReturn(echo);
        when(imageSearch.invoke(any(), eq(ctx))).thenReturn(stubResult());
        when(videoSearch.invoke(any(), eq(ctx))).thenReturn(stubResult());
        when(pdfSearch.invoke(any(), eq(ctx))).thenReturn(stubResult());

        Map<String, Object> out = tool.invoke(Map.of("query", "Lissabon"), ctx);

        @SuppressWarnings("unchecked")
        Map<String, Object> text = (Map<String, Object>) out.get("text");
        assertThat(text).doesNotContainKey("query");
        assertThat(text).containsKey("results");
        assertThat(text).containsKey("count");
    }

    @Test
    void invoke_emptyDownstreamResults_yieldsEmptyButShapedBuckets() {
        when(webSearch.invoke(any(), eq(ctx))).thenReturn(stubResult());
        when(imageSearch.invoke(any(), eq(ctx))).thenReturn(stubResult());
        when(videoSearch.invoke(any(), eq(ctx))).thenReturn(stubResult());
        when(pdfSearch.invoke(any(), eq(ctx))).thenReturn(stubResult());

        Map<String, Object> out = tool.invoke(Map.of("query", "obscure topic"), ctx);

        for (String bucket : List.of("text", "images", "videos", "pdfs")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> b = (Map<String, Object>) out.get(bucket);
            assertThat(b).as("bucket %s shape", bucket)
                    .containsKey("results")
                    .containsKey("count");
            assertThat(b.get("count")).isEqualTo(0);
        }
    }

    @Test
    void invoke_blankQuery_throws() {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> tool.invoke(Map.of("query", "  "), ctx))
                .isInstanceOf(de.mhus.vance.toolpack.ToolException.class)
                .hasMessageContaining("query");
    }

    // ──────────────────────────────────────────────────────────────────

    private static Map<String, Object> stubResult(String... titles) {
        Map<String, Object> r = new LinkedHashMap<>();
        java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (String t : titles) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("title", t);
            rows.add(row);
        }
        r.put("results", rows);
        r.put("count", rows.size());
        return r;
    }

    private static int bucketCount(Map<String, Object> out, String bucket) {
        @SuppressWarnings("unchecked")
        Map<String, Object> b = (Map<String, Object>) out.get(bucket);
        Object c = b.get("count");
        return c instanceof Number n ? n.intValue() : 0;
    }
}
