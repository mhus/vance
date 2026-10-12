package de.mhus.vance.brain.zem;

import de.mhus.vance.api.zem.ZemAnswerDto;
import de.mhus.vance.api.zem.ZemDecisionDto;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The one compact form of a decision that callers outside Java see —
 * the {@code zem_decide} tool, {@code vance.zem.decide} in scripts and
 * (v2) one Wowbagger output line per record. Keyed by question name, so
 * a script reads {@code d.unsafe.noul}:
 *
 * <pre>
 * {"unsafe": {"type": "noul", "noul": 0.97, "confidence": 0.94},
 *  "intent": {"type": "choice", "choice": "refund", "confidence": 0.96,
 *             "p": {"refund": 0.98, "other": 0.02}},
 *  "urgency": {"type": "score", "score": 1.17, "confidence": 0.73,
 *              "p": {"0": 0.0, "1": 0.82, "2": 0.18}}}
 * </pre>
 *
 * Numbers are rounded to three places — providers report two (Jev) or
 * sixteen (Ollama), and three keep the two comparable without noise.
 * {@code p} is the distribution; bulk callers can drop it, because with
 * many options it dominates the size of a line.
 */
public final class ZemDecisionJson {

    private ZemDecisionJson() {}

    /** Answers keyed by question name, distributions included. */
    public static Map<String, Object> answers(ZemDecisionDto decision) {
        return answers(decision, true);
    }

    public static Map<String, Object> answers(ZemDecisionDto decision, boolean withDistribution) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (ZemAnswerDto a : decision.getAnswers()) {
            out.put(a.getName(), answer(a, withDistribution));
        }
        return out;
    }

    private static Map<String, Object> answer(ZemAnswerDto a, boolean withDistribution) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", a.getType().name().toLowerCase(Locale.ROOT));
        switch (a.getType()) {
            case NOUL -> out.put("noul", round(a.getNoul()));
            case CHOICE -> out.put("choice", a.getChoice());
            case SCORE -> out.put("score", round(a.getScore()));
            case REFUSAL -> {
                return out;
            }
        }
        if (a.getConfidence() != null) {
            out.put("confidence", round(a.getConfidence()));
        }
        if (withDistribution && a.getProbabilities() != null) {
            Map<String, Object> p = new LinkedHashMap<>();
            a.getProbabilities().forEach((k, v) -> p.put(k, round(v)));
            out.put("p", p);
        }
        return out;
    }

    static @Nullable Double round(@Nullable Double v) {
        return v == null ? null : Math.round(v * 1000d) / 1000d;
    }
}
