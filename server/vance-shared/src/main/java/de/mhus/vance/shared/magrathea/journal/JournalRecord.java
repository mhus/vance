package de.mhus.vance.shared.magrathea.journal;

/**
 * Marker interface for typed bodies stored in
 * {@link de.mhus.vance.shared.magrathea.MagratheaJournalEntry#getData()}.
 * Implementations are POJOs serialised via Jackson; the
 * {@link MagratheaJournalEntry#getType()} field stores the FQN so the
 * loader can reconstitute the typed instance.
 *
 * <p>Pattern modelled after Nimbus' {@code JournalRecord} interface
 * (see {@code nimbus-world/world-shared/workflow/JournalRecord.java}).
 */
public interface JournalRecord {
}
