/**
 * Trillian — agentic user-loop family. Trillian processes act as
 * service-account users that delegate tasks to sub-sessions on
 * behalf of a human steering them. See
 * {@code planning/trillian-engine.md}.
 *
 * <p>Nature-0 is the architecture proof: every Trillian-Control
 * session pairs a chat-host process (Arthur-based, talks to the
 * human) with a Trillian-User process (Frankie-based, owned by a
 * fresh {@code _trillian-0XXXX} service account, runs the
 * observe-think-act loop). {@link TrillianSessionBootstrapper}
 * creates the pairing on session-create.
 */
@org.jspecify.annotations.NullMarked
package de.mhus.vance.brain.trillian;
