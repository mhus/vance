package de.mhus.vance.brain.zem;

import de.mhus.vance.api.zem.ZemAnswerDto;
import de.mhus.vance.api.zem.ZemAnswerType;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Fills {@code confidence} where the provider sent none, so every
 * consumer reads the same field. Formulas from the TypeSafe docs
 * ({@code docs.typesafe.ai/confidence}); they reproduce Jev's own values
 * (choice {@code .94/.06/0} → {@code .91}, score {@code 0/.82/.18} →
 * {@code .73}). A provider-sent value is never overwritten — Ollama
 * computes score confidence differently, and its number is its own.
 *
 * <ul>
 *   <li>NOUL: {@code |2p − 1|}</li>
 *   <li>CHOICE: {@code (p_max − 1/n) / (1 − 1/n)}</li>
 *   <li>SCORE: {@code max(0, 1 − Σ p_i·|i − m| / MAD_unif)} with m the most
 *       likely level and {@code MAD_unif = (1/n)·Σ |i − (n−1)/2|}</li>
 * </ul>
 */
final class ZemConfidence {

    private ZemConfidence() {}

    static ZemAnswerDto fill(ZemAnswerDto answer) {
        if (answer.getConfidence() != null || answer.getType() == ZemAnswerType.REFUSAL) {
            return answer;
        }
        Double computed =
                switch (answer.getType()) {
                    case NOUL -> answer.getNoul() == null ? null : Math.abs(2 * answer.getNoul() - 1);
                    case CHOICE -> choice(answer.getProbabilities());
                    case SCORE -> score(answer.getProbabilities());
                    case REFUSAL -> null;
                };
        if (computed == null) {
            return answer;
        }
        answer.setConfidence(clamp(computed));
        return answer;
    }

    static @Nullable Double choice(@Nullable Map<String, Double> probabilities) {
        if (probabilities == null || probabilities.size() < 2) {
            return null;
        }
        int n = probabilities.size();
        double max = probabilities.values().stream()
                .mapToDouble(Double::doubleValue)
                .max()
                .orElse(0);
        return (max - 1.0 / n) / (1 - 1.0 / n);
    }

    static @Nullable Double score(@Nullable Map<String, Double> probabilities) {
        if (probabilities == null || probabilities.size() < 2) {
            return null;
        }
        int n = probabilities.size();
        double[] p = new double[n];
        for (Map.Entry<String, Double> e : probabilities.entrySet()) {
            int level;
            try {
                level = Integer.parseInt(e.getKey());
            } catch (NumberFormatException ex) {
                return null;
            }
            if (level < 0 || level >= n) {
                return null;
            }
            p[level] = e.getValue();
        }
        int mode = 0;
        for (int i = 1; i < n; i++) {
            if (p[i] > p[mode]) {
                mode = i;
            }
        }
        double mad = 0;
        double madUniform = 0;
        double center = (n - 1) / 2.0;
        for (int i = 0; i < n; i++) {
            mad += p[i] * Math.abs(i - mode);
            madUniform += Math.abs(i - center);
        }
        madUniform /= n;
        return madUniform == 0 ? null : Math.max(0, 1 - mad / madUniform);
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
