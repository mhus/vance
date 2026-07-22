package de.mhus.vance.shared.compose;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Locates {@code ```vance-compose} fenced blocks inside a workpage markdown
 * document and splices new YAML into one. The block-editor serializes a compose
 * block as a line {@code ```vance-compose}, the manifest YAML, then a closing
 * {@code ```} line (see
 * {@code client/packages/block-editor/src/markdown/serializer.ts}).
 *
 * <p>Used by {@code compose_block_run} to run and write back the inline compose
 * block a user has selected in a workpage — the top-level {@code kind: compose}
 * case needs no locator (the whole document is the manifest).
 */
public final class ComposeFenceLocator {

    private static final String OPEN = "```vance-compose";
    private static final String CLOSE = "```";

    private ComposeFenceLocator() {}

    /**
     * A located compose fence. {@code fenceStart}/{@code fenceEnd} bracket the
     * whole block (opener line .. just past the closing line);
     * {@code yamlStart}/{@code yamlEnd} bracket just the manifest YAML body
     * between the fences. {@code yaml} is that body (ends with a newline, as the
     * serializer guarantees).
     */
    public record Fence(int fenceStart, int fenceEnd, int yamlStart, int yamlEnd, String yaml) {
        public boolean contains(int offset) {
            return offset >= fenceStart && offset < fenceEnd;
        }
    }

    /** All compose fences in document order (empty if none). */
    public static List<Fence> findAll(String doc) {
        List<Fence> out = new ArrayList<>();
        int len = doc.length();
        int i = 0;
        while (i < len) {
            int stop = lineEnd(doc, i);
            if (line(doc, i, stop).equals(OPEN)) {
                int fenceStart = i;
                int yamlStart = stop < len ? stop + 1 : len;
                int j = yamlStart;
                int yamlEnd = -1;
                int fenceEnd = len;
                while (j < len) {
                    int s = lineEnd(doc, j);
                    if (line(doc, j, s).equals(CLOSE)) {
                        yamlEnd = j;                       // closing line start = end of YAML
                        fenceEnd = s < len ? s + 1 : len;
                        break;
                    }
                    j = s < len ? s + 1 : len;
                }
                if (yamlEnd < 0) break;                     // unterminated fence
                out.add(new Fence(fenceStart, fenceEnd, yamlStart, yamlEnd,
                        doc.substring(yamlStart, yamlEnd)));
                i = fenceEnd;
            } else {
                i = stop < len ? stop + 1 : len;
            }
        }
        return out;
    }

    /** The fence containing {@code offset}, or {@code null}. */
    public static @Nullable Fence findAt(List<Fence> fences, int offset) {
        for (Fence f : fences) {
            if (f.contains(offset)) return f;
        }
        return null;
    }

    /** Splice {@code newYaml} into {@code fence}'s body (a trailing newline is ensured). */
    public static String replaceYaml(String doc, Fence fence, String newYaml) {
        String y = newYaml.endsWith("\n") ? newYaml : newYaml + "\n";
        return doc.substring(0, fence.yamlStart()) + y + doc.substring(fence.yamlEnd());
    }

    private static int lineEnd(String doc, int from) {
        int nl = doc.indexOf('\n', from);
        return nl < 0 ? doc.length() : nl;
    }

    private static String line(String doc, int from, int stop) {
        String s = doc.substring(from, stop);
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }
}
