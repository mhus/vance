package de.mhus.vance.brain.trillian.nature;

import de.mhus.vance.brain.trillian.TrillianInternalApi;
import de.mhus.vance.brain.trillian.TrillianSessionBootstrapper;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Trillian Nature-0 — the architecture-proof baseline.
 *
 * <p>Hooks return defaults except for {@link #userPromptAddendum} —
 * Nature-0 renders the {@code attributes} map (set by Control via
 * {@code user_attr_set}) as a simple key/value block in the
 * Trillian-User loop's system prompt. That makes the Control-side
 * attribute mechanism observable end-to-end already in Nature-0 —
 * the LLM-driven loop sees the attributes and can act on them
 * (even though Nature-0 doesn't formally interpret them as persona,
 * traits, modes etc.; that's Nature-A+ work).
 *
 * <p>Recipes pin Nature-0 via {@code params.nature: '0'} in the
 * bundled {@code trillian-0.yaml}, {@code trillian-user-0.yaml}, and
 * {@code trillian-worker-0.yaml}. The {@code trillian.yaml} default-
 * alias also pins Nature-0 today.
 */
@Component
@RequiredArgsConstructor
public class TrillianNature0 implements TrillianNature {

    public static final String ID = "0";

    private final ThinkProcessService thinkProcessService;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "Trillian Nature-0 (architecture proof)";
    }

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
