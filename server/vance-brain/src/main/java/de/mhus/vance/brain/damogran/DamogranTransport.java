package de.mhus.vance.brain.damogran;

import de.mhus.vance.brain.damogran.DamogranManifest.ExportEntry;
import de.mhus.vance.brain.damogran.DamogranManifest.ImportEntry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Dispatches compose import/export to the {@link DamogranImporter} /
 * {@link DamogranExporter} bean registered for the entry's URI scheme
 * ({@code vance}, {@code http}/{@code https}, {@code git}, …). Built-in and
 * addon-provided sources/targets plug in via those SPIs — no scheme-switch in
 * the core.
 */
@Slf4j
@Service
public class DamogranTransport {

    private final Map<String, DamogranImporter> importers;
    private final Map<String, DamogranExporter> exporters;

    public DamogranTransport(List<DamogranImporter> importerBeans, List<DamogranExporter> exporterBeans) {
        this.importers = index(importerBeans, DamogranImporter::schemes, "importer");
        this.exporters = index(exporterBeans, DamogranExporter::schemes, "exporter");
        log.debug("DamogranTransport: import schemes={}, export schemes={}",
                new TreeSet<>(importers.keySet()), new TreeSet<>(exporters.keySet()));
    }

    public void doImport(DamogranContext ctx, ImportEntry entry) {
        String scheme = DamogranUri.scheme(entry.from());
        DamogranImporter importer = importers.get(scheme);
        if (importer == null) {
            throw new DamogranException("no importer for scheme '" + scheme + "' (from: "
                    + entry.from() + "); known: " + new TreeSet<>(importers.keySet()));
        }
        importer.doImport(ctx, entry);
    }

    public void doExport(DamogranContext ctx, ExportEntry entry) {
        String scheme = DamogranUri.scheme(entry.to());
        DamogranExporter exporter = exporters.get(scheme);
        if (exporter == null) {
            throw new DamogranException("no exporter for scheme '" + scheme + "' (to: "
                    + entry.to() + "); known: " + new TreeSet<>(exporters.keySet()));
        }
        exporter.doExport(ctx, entry);
    }

    private static <T> Map<String, T> index(
            List<T> beans, java.util.function.Function<T, java.util.Set<String>> schemesOf, String kind) {
        Map<String, T> map = new HashMap<>();
        for (T bean : beans) {
            for (String scheme : schemesOf.apply(bean)) {
                T prev = map.put(scheme, bean);
                if (prev != null) {
                    throw new IllegalStateException("Duplicate Damogran " + kind + " for scheme '"
                            + scheme + "': " + prev.getClass().getName() + " and " + bean.getClass().getName());
                }
            }
        }
        return Map.copyOf(map);
    }
}
