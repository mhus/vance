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

    // Name-only kinds with no codec — nothing to parse-validate.
    @Bean public KindHandler textKindHandler() { return () -> "text"; }
    @Bean public KindHandler slidesKindHandler() { return () -> "slides"; }
    @Bean public KindHandler schemaKindHandler() { return () -> "schema"; }
    @Bean public KindHandler applicationKindHandler() { return () -> "application"; }
    @Bean public KindHandler composeKindHandler() { return () -> "compose"; }

    // Codec-backed kinds (sheet, chart, graph, diagram, tree, list, checklist,
    // mindmap, data) now register in CodecKindHandlers with a parse-validate().
    // 'records' → RecordsKindHandler (@Service, Phase 3); 'canvas' →
    // CanvasKindHandler (canvas addon, Phase 4); both do semantic checks.
    // 'tex-compose' → vance-addon-brain-tex (TexComposeKindHandler).
}
