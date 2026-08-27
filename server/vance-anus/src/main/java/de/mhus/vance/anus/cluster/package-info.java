/**
 * What the admin shell does to the cluster: place and hand off projects
 * ({@link de.mhus.vance.anus.cluster.ProjectClusterService}), and read,
 * configure and probe pods ({@link de.mhus.vance.anus.cluster.PodClusterService}).
 *
 * <p>Separate from {@code de.mhus.vance.anus.shell} because these are
 * operations, not a command surface: they answer with facts a caller can act
 * on, and the {@code @Command} methods turn those into the text an operator
 * reads. Anything that only exists to be typed at a terminal — the option
 * grammar, the CSV convention, the typed confirmation, the table layout, the
 * dry-run decision — stays on the shell side.
 *
 * <p>The two services split along the two things a cluster is made of, and they
 * meet at the placement contract: a project carries a selector, a pod carries
 * labels, and {@code PodSelector} matches them. Neither service evaluates that
 * match — the brain does, which is why every write here goes over REST even
 * though anus could reach the collection directly.
 *
 * <p>Everything here still lives in anus, deliberately. These paths reach
 * {@code /internal/**} with the cluster-wide technical token and carry no
 * tenant authorization; that is the admin shell's standing and nobody else's.
 */
@org.jspecify.annotations.NullMarked
package de.mhus.vance.anus.cluster;
