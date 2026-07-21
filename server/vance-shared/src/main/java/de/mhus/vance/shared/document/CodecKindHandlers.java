package de.mhus.vance.shared.document;

import de.mhus.vance.shared.document.kind.ChartCodec;
import de.mhus.vance.shared.document.kind.ChecklistCodec;
import de.mhus.vance.shared.document.kind.CodecKindHandler;
import de.mhus.vance.shared.document.kind.DataCodec;
import de.mhus.vance.shared.document.kind.DiagramCodec;
import de.mhus.vance.shared.document.kind.GraphCodec;
import de.mhus.vance.shared.document.kind.KindHandler;
import de.mhus.vance.shared.document.kind.ListCodec;
import de.mhus.vance.shared.document.kind.MindmapCodec;
import de.mhus.vance.shared.document.kind.SheetCodec;
import de.mhus.vance.shared.document.kind.TreeCodec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the codec-backed built-in kinds as {@link CodecKindHandler} beans —
 * each gets a {@code validate} that runs its codec and reports a parse failure
 * as a {@code <kind>-parse} ERROR finding (kind-handler track Phase 4, the cheap
 * uniform baseline). Split out from {@link BuiltInKindHandlers}, which keeps the
 * trivial name-only kinds that have no codec ({@code text}, {@code schema},
 * {@code compose}, {@code slides}, {@code application}).
 *
 * <p>A kind graduates from here to its own dedicated {@code @Service} handler
 * when it wants semantic checks beyond "does it parse" — as {@code records}
 * (RecordsKindHandler) and {@code canvas} (CanvasKindHandler) did.
 */
@Configuration
public class CodecKindHandlers {

    @Bean public KindHandler sheetKindHandler() {
        return new CodecKindHandler("sheet", SheetCodec::parse, SheetCodec::supports);
    }
    @Bean public KindHandler chartKindHandler() {
        return new CodecKindHandler("chart", ChartCodec::parse, ChartCodec::supports);
    }
    @Bean public KindHandler graphKindHandler() {
        return new CodecKindHandler("graph", GraphCodec::parse, GraphCodec::supports);
    }
    @Bean public KindHandler diagramKindHandler() {
        return new CodecKindHandler("diagram", DiagramCodec::parse, DiagramCodec::supports);
    }
    @Bean public KindHandler treeKindHandler() {
        return new CodecKindHandler("tree", TreeCodec::parse, TreeCodec::supports);
    }
    @Bean public KindHandler listKindHandler() {
        return new CodecKindHandler("list", ListCodec::parse, ListCodec::supports);
    }
    @Bean public KindHandler checklistKindHandler() {
        return new CodecKindHandler("checklist", ChecklistCodec::parse, ChecklistCodec::supports);
    }
    @Bean public KindHandler mindmapKindHandler() {
        return new CodecKindHandler("mindmap", MindmapCodec::parse, MindmapCodec::supports);
    }
    @Bean public KindHandler dataKindHandler() {
        return new CodecKindHandler("data", DataCodec::parse, DataCodec::supports);
    }
}
