package de.mhus.vance.brain.damogran;

import de.mhus.vance.brain.damogran.DamogranManifest.ExportEntry;
import java.util.Set;

/**
 * SPI for a compose <em>export</em> target, selected by the URI scheme of the
 * entry's {@code to} (e.g. {@code vance} document, {@code git} commit/push).
 * One Spring bean per scheme; {@link DamogranTransport} dispatches by scheme.
 *
 * <p>Invariant: the source ({@code entry.from()}) is <em>always</em>
 * workspace-local — an exporter pushes a workspace file/dir out to a
 * remote/document. Addons add new targets by contributing a bean.
 */
public interface DamogranExporter {

    /** URI schemes this exporter handles. */
    Set<String> schemes();

    /** Push the workspace {@code entry.from()} out to {@code entry.to()}. */
    void doExport(DamogranContext ctx, ExportEntry entry);
}
