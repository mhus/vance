package de.mhus.vance.brain.trillian.nature;

import de.mhus.vance.brain.trillian.TrillianInternalApi;
import de.mhus.vance.brain.trillian.TrillianSessionBootstrapper;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;

/**
 * Mechanics every Trillian generation shares, regardless of Nature.
 *
 * <p>The attribute map and how it renders into the Control and User-Loop
 * prompts are not a generational decision — they are how a Trillian is
 * configured at all. A Nature that had to reimplement them would be
 * reimplementing the engine, not overlaying behaviour on it.
 *
 * <p><b>Why this is not {@link TrillianNature0}.</b> Nature-0 happens to
 * be exactly this baseline and nothing more, which makes it tempting to
 * derive later Natures from it directly. That conflates two different
 * things: "what every Trillian does" and "what generation zero does".
 * The moment Nature-0 gains something of its own — or loses something as
 * an experiment — every descendant would inherit it silently. Natures
 * extend this class; Nature-0 is one of them.
 *
 * <p>Subclasses supply {@link #id()} and {@link #title()} and override
 * whichever hooks their generation actually changes.
 */
@RequiredArgsConstructor
public abstract class TrillianNatureBase implements TrillianNature {

    /** Needed to follow {@code peerProcessId} from Control to the loop. */
    protected final ThinkProcessService thinkProcessService;

    /**
     * Trillian-User reads its own {@code engineParams.attributes} —
     * Control set them there via {@code user_attr_set}.
     */
    @Override
    public String userPromptAddendum(ThinkProcessDocument process) {
        return renderAttributes(
                TrillianInternalApi.readAttributes(process),
                "set by Control");
    }

    /**
     * Control reads the attributes off the peer (Trillian-User-Loop)
     * process — that's the canonical storage location. Without this
     * follow-the-peer lookup, the human-facing Control would not
     * reflect a persona / mode the human just configured.
     */
    @Override
    public String controlPromptAddendum(ThinkProcessDocument process) {
        Optional<ThinkProcessDocument> peer = resolvePeer(process);
        if (peer.isEmpty()) {
            return "";
        }
        return renderAttributes(
                TrillianInternalApi.readAttributes(peer.get()),
                "currently active on this Trillian");
    }

    private Optional<ThinkProcessDocument> resolvePeer(ThinkProcessDocument process) {
        if (process.getEngineParams() == null) {
            return Optional.empty();
        }
        Object raw = process.getEngineParams()
                .get(TrillianSessionBootstrapper.PARAM_PEER_PROCESS_ID);
        if (!(raw instanceof String peerId) || peerId.isBlank()) {
            return Optional.empty();
        }
        return thinkProcessService.findById(peerId);
    }

    private static String renderAttributes(Map<String, Object> attrs, String context) {
        if (attrs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## Attributes (").append(context).append(")\n\n");
        sb.append("The human has configured the following attributes on this ")
                .append("Trillian. Read them and let them shape how you act — ")
                .append("they apply to both Control and the User-Loop, so the ")
                .append("whole Trillian behaves consistently.\n\n");
        for (Map.Entry<String, Object> e : attrs.entrySet()) {
            sb.append("- **").append(e.getKey()).append(":** ")
                    .append(formatValue(e.getValue())).append('\n');
        }
        return sb.toString();
    }

    private static String formatValue(Object value) {
        if (value == null) return "(null)";
        String s = value.toString();
        // Single-line attribute values keep the markdown bullet clean;
        // multi-line values render verbatim under the bullet.
        if (s.indexOf('\n') < 0) {
            return s;
        }
        return "\n  ```\n  " + s.replace("\n", "\n  ") + "\n  ```";
    }
}
