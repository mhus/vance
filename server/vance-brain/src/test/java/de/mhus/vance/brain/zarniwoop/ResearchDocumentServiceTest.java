package de.mhus.vance.brain.zarniwoop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.permission.WriteActor;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.research.RankedHit;
import de.mhus.vance.toolpack.research.RankedHitSet;
import de.mhus.vance.toolpack.research.SearchModality;
import de.mhus.vance.toolpack.research.SearchScope;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ResearchDocumentServiceTest {

    private static final ToolInvocationContext CTX =
            new ToolInvocationContext("acme", "alpha", null, null, "u1");
    private static final WriteActor ACTOR = WriteActor.SYSTEM;

    private ZarniwoopResearchService research;
    private LightLlmService lightLlm;
    private DocumentService docs;
    private ResearchDocumentService service;

    @BeforeEach
    void setUp() {
        research = mock(ZarniwoopResearchService.class);
        lightLlm = mock(LightLlmService.class);
        docs = mock(DocumentService.class);
        service = new ResearchDocumentService(
                research, lightLlm, docs, new MetricService(new SimpleMeterRegistry()));
    }

    private static RankedHit hit(String title, String url, String note) {
        return new RankedHit(
                title, url, 0.9, 0.9, 1.0, SearchModality.WEB, "serper", "snippet", note, Map.of());
    }

    private static RankedHitSet hitsOf(RankedHit... hs) {
        return new RankedHitSet(
                "q", List.of(hs), List.of(), 0, Set.of("serper"), Map.of(), List.of("gap-1"));
    }

    @Test
    void createDocument_happyPath_writesDocumentSummaryAndSourceNotes() {
        when(research.investigate(eq("q"), any(SearchScope.class), eq(CTX)))
                .thenReturn(hitsOf(hit("A", "https://a", "why a"), hit("B", "https://b", null)));
        when(lightLlm.callForJson(any())).thenReturn(Map.of(
                "title", "Doc Title",
                "body", "# Body",
                "summary", "short summary",
                "tags", List.of("Physics", "fusion")));
        when(docs.findByPath(any(), any(), any())).thenReturn(Optional.empty());
        when(docs.createText(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> DocumentDocument.builder()
                        .id("doc-1").projectId("alpha").path(inv.getArgument(2)).build());

        ResearchDocumentResult r = service.createDocument(
                "q", "research/q.md", List.of("MyTag"), "alpha", CTX, ACTOR);

        // Document body written under the caller's actor, into the given path.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> tagsCap = ArgumentCaptor.forClass(List.class);
        verify(docs).createText(eq("acme"), eq("alpha"), eq("research/q.md"),
                eq("Doc Title"), tagsCap.capture(), eq("# Body"), eq("u1"), eq(ACTOR));
        // Marker tag first, then synthesizer tags (lowercased), then caller tags.
        assertThat(tagsCap.getValue()).containsExactly("research", "physics", "fusion", "mytag");

        // Summary stamped separately.
        verify(docs).setSummary("doc-1", "short summary", ACTOR);

        // One source note per kept hit, carrying the URL and relevance note.
        ArgumentCaptor<String> noteCap = ArgumentCaptor.forClass(String.class);
        verify(docs, times(2)).addNote(
                eq("doc-1"), noteCap.capture(), eq("u1"), isNull(), isNull(), eq(ACTOR));
        assertThat(noteCap.getAllValues().get(0)).contains("https://a").contains("why a");
        assertThat(noteCap.getAllValues().get(1)).contains("https://b");

        assertThat(r.docId()).isEqualTo("doc-1");
        assertThat(r.path()).isEqualTo("research/q.md");
        assertThat(r.title()).isEqualTo("Doc Title");
        assertThat(r.summary()).isEqualTo("short summary");
        assertThat(r.sourceCount()).isEqualTo(2);
        assertThat(r.tags()).contains("research");
        assertThat(r.gaps()).containsExactly("gap-1");
    }

    @Test
    void createDocument_noUsableSources_throwsBeforeSynthesisOrWrite() {
        when(research.investigate(any(), any(SearchScope.class), any())).thenReturn(
                new RankedHitSet("q", List.of(), List.of(), 0, Set.of(), Map.of(), List.of()));

        assertThatThrownBy(() -> service.createDocument(
                "q", "research/q.md", List.of(), "alpha", CTX, ACTOR))
                .isInstanceOf(ZarniwoopException.class)
                .hasMessageContaining("no usable sources");

        verifyNoInteractions(lightLlm);
        verify(docs, never())
                .createText(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void createDocument_pathCollision_appendsNumericSuffix() {
        when(research.investigate(any(), any(SearchScope.class), any()))
                .thenReturn(hitsOf(hit("A", "https://a", "n")));
        when(lightLlm.callForJson(any())).thenReturn(Map.of("title", "T", "body", "B"));
        when(docs.findByPath("acme", "alpha", "research/q.md"))
                .thenReturn(Optional.of(DocumentDocument.builder().id("x").build()));
        when(docs.findByPath("acme", "alpha", "research/q-2.md")).thenReturn(Optional.empty());
        when(docs.createText(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> DocumentDocument.builder()
                        .id("d").projectId("alpha").path(inv.getArgument(2)).build());

        ResearchDocumentResult r = service.createDocument(
                "q", "research/q.md", List.of(), "alpha", CTX, ACTOR);

        verify(docs).createText(eq("acme"), eq("alpha"), eq("research/q-2.md"),
                any(), any(), eq("B"), any(), any());
        assertThat(r.path()).isEqualTo("research/q-2.md");
        // No summary key in the synthesis → summary field left untouched.
        assertThat(r.summary()).isNull();
        verify(docs, never()).setSummary(any(), any(), any());
    }

    @Test
    void deriveDefaultPath_slugifiesQuestionUnderResearchDir() {
        assertThat(ResearchDocumentService.deriveDefaultPath("Tokamak cooling: how?"))
                .isEqualTo("research/tokamak-cooling-how.md");
        assertThat(ResearchDocumentService.deriveDefaultPath("!!!")).isEqualTo("research/research.md");
    }
}
