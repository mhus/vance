package de.mhus.vance.brain.kit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.merge.MergeAlgorithm;
import org.eclipse.jgit.merge.MergeFormatter;
import org.eclipse.jgit.merge.MergeResult;

/**
 * Three-way text merge for the {@code merge} update policy.
 *
 * <p>Uses JGit's merge algorithm rather than a hand-rolled one: JGit is
 * already the kit subsystem's git layer, so this costs no dependency and
 * produces conflict markers everyone recognises.
 *
 * <p>Line-based, like git. That is why the policy applies to documents
 * only — merging two versions of a single setting value line by line
 * would be theatre.
 */
final class KitMerge {

    private KitMerge() {}

    /**
     * Outcome of a merge attempt.
     *
     * @param content clean merge result, or the conflict-marked text when
     *        {@code conflicted} is true
     * @param conflicted true when the two sides changed the same lines
     */
    record Result(String content, boolean conflicted) {}

    /**
     * Merge {@code ours} and {@code theirs} over their common ancestor.
     *
     * @param base the content as the kit last installed it
     * @param ours what is in the project now (the user's edit)
     * @param theirs what the new kit version ships
     */
    static Result merge(String base, String ours, String theirs) {
        MergeResult<RawText> merged = new MergeAlgorithm().merge(
                RawTextComparator.DEFAULT,
                new RawText(base.getBytes(StandardCharsets.UTF_8)),
                new RawText(ours.getBytes(StandardCharsets.UTF_8)),
                new RawText(theirs.getBytes(StandardCharsets.UTF_8)));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            new MergeFormatter().formatMerge(
                    out, merged,
                    // Marker labels the user will read in their editor.
                    List.of("base", "yours", "kit"),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            // ByteArrayOutputStream does not do I/O; this cannot happen.
            throw new IllegalStateException("in-memory merge formatting failed", e);
        }
        return new Result(out.toString(StandardCharsets.UTF_8), merged.containsConflicts());
    }
}
