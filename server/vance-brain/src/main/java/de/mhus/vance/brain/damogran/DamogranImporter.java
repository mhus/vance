package de.mhus.vance.brain.damogran;

import de.mhus.vance.brain.damogran.DamogranManifest.ImportEntry;
import java.util.Set;

/**
 * SPI for a compose <em>import</em> source, selected by the URI scheme of the
 * entry's {@code from} (e.g. {@code vance}, {@code http}/{@code https},
 * {@code git}). One Spring bean per scheme; the {@link DamogranTransport}
 * dispatcher builds a {@code scheme → bean} registry from all beans.
 *
 * <p>Invariant: the destination ({@code entry.to()}) is <em>always</em>
 * workspace-local — an importer pulls a remote/document into the workspace,
 * never the other way around. Addons add new sources (S3, gdrive, …) by
 * contributing a bean; the core is not touched.
 */
public interface DamogranImporter {

    /** URI schemes this importer handles (e.g. {@code {"http", "https"}}). */
    Set<String> schemes();

    /** Pull {@code entry.from()} into the workspace at {@code entry.to()}. */
    void doImport(DamogranContext ctx, ImportEntry entry);
}
