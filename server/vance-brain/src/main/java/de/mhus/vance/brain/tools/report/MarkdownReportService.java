package de.mhus.vance.brain.tools.report;

import de.mhus.vance.toolpack.ToolException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Dispatches a {@link MarkdownReportContext} to the
 * {@link MarkdownReportRenderer} that owns the requested format.
 * Spring injects all renderers as a list at construction time, so
 * adding a new format (ODT, EPUB, …) is a matter of dropping a
 * {@code @Component} that implements the interface — no
 * registration boilerplate.
 */
@Service
@Slf4j
public class MarkdownReportService {

    private final Map<String, MarkdownReportRenderer> byFormat;

    public MarkdownReportService(List<MarkdownReportRenderer> renderers) {
        Map<String, MarkdownReportRenderer> map = new HashMap<>();
        for (MarkdownReportRenderer r : renderers) {
            String key = r.format().toLowerCase(Locale.ROOT);
            MarkdownReportRenderer prev = map.put(key, r);
            if (prev != null) {
                throw new IllegalStateException(
                        "Duplicate MarkdownReportRenderer for format '"
                                + key + "': " + prev.getClass().getName()
                                + " and " + r.getClass().getName());
            }
        }
        this.byFormat = Map.copyOf(map);
        log.info("MarkdownReportService bootstrapped: formats={}",
                byFormat.keySet());
    }

    public Set<String> supportedFormats() {
        return byFormat.keySet();
    }

    public MarkdownReportRenderer rendererFor(String format) {
        if (format == null || format.isBlank()) {
            throw new ToolException(
                    "'format' is required — one of " + byFormat.keySet());
        }
        MarkdownReportRenderer r = byFormat.get(format.toLowerCase(Locale.ROOT));
        if (r == null) {
            throw new ToolException(
                    "Unsupported format '" + format + "' — supported: "
                            + byFormat.keySet());
        }
        return r;
    }

    /** Shortcut: pick renderer + render. */
    public RenderedReport render(String format, MarkdownReportContext context) {
        MarkdownReportRenderer r = rendererFor(format);
        byte[] bytes = r.render(context);
        return new RenderedReport(bytes, r.mimeType(), r.fileExtension());
    }

    public record RenderedReport(byte[] bytes, String mimeType, String fileExtension) {}
}
