package de.mhus.vance.shared.document;

import de.mhus.vance.shared.document.kind.KindHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the built-in document kinds as {@link KindHandler} beans.
 * Each kind currently only declares its canonical name; later, when an
 * extension point grows (default stub body, MIME-type, validation),
 * individual entries can be extracted into their own {@code @Service}
 * classes without disturbing the registration contract.
 *
 * <p>Addons add new kinds by exposing their own {@link KindHandler}
 * bean (e.g. {@code @Service CalendarKindHandler}) in their
 * {@code @ComponentScan}-ed package; Spring auto-collects everything
 * into the {@link KindRegistry}.
 */
@Configuration
public class BuiltInKindHandlers {

    @Bean public KindHandler textKindHandler() { return () -> "text"; }
    @Bean public KindHandler listKindHandler() { return () -> "list"; }
    @Bean public KindHandler checklistKindHandler() { return () -> "checklist"; }
    @Bean public KindHandler treeKindHandler() { return () -> "tree"; }
    @Bean public KindHandler mindmapKindHandler() { return () -> "mindmap"; }
    @Bean public KindHandler recordsKindHandler() { return () -> "records"; }
    @Bean public KindHandler sheetKindHandler() { return () -> "sheet"; }
    @Bean public KindHandler graphKindHandler() { return () -> "graph"; }
    @Bean public KindHandler chartKindHandler() { return () -> "chart"; }
    @Bean public KindHandler slidesKindHandler() { return () -> "slides"; }
    @Bean public KindHandler dataKindHandler() { return () -> "data"; }
    @Bean public KindHandler schemaKindHandler() { return () -> "schema"; }
    @Bean public KindHandler diagramKindHandler() { return () -> "diagram"; }
    @Bean public KindHandler applicationKindHandler() { return () -> "application"; }
    @Bean public KindHandler texComposeKindHandler() { return () -> "tex-compose"; }
}
