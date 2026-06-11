package de.mhus.vance.brain.image;

import com.sksamuel.scrimage.ImmutableImage;
import com.sksamuel.scrimage.filter.GammaFilter;
import com.sksamuel.scrimage.filter.HSBFilter;
import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * Pure-function "magic wand" enhancer: histogram percentile clip →
 * linear per-channel stretch → gamma correction → light saturation
 * boost. See {@code specification/image-manipulation.md} §5.
 *
 * <p>No external calls, no model — deterministic on the same input +
 * settings. Tuning lives in {@link Config}, which the service builds
 * fresh per call from the {@link de.mhus.vance.shared.settings.SettingService}
 * cascade.
 */
final class AutoEnhanceProcessor {

    private AutoEnhanceProcessor() {}

    /** Per-call configuration. All values come from
     *  {@code image.tools.auto_enhance.*} settings — see service §12. */
    record Config(double percentileClip, double gamma, double saturationBoost) {}

    /** Apply the full auto-enhance chain. Returns a fresh
     *  {@link ImmutableImage} backed by an independent
     *  {@link BufferedImage} — the input is never modified. */
    static ImmutableImage enhance(ImmutableImage input, Config config) throws IOException {
        BufferedImage stretched = applyHistogramStretch(input.awt(), config.percentileClip());
        ImmutableImage stage1 = ImmutableImage.wrapAwt(stretched);

        ImmutableImage stage2 = config.gamma() == 1.0
                ? stage1
                : stage1.filter(new GammaFilter(config.gamma()));

        return config.saturationBoost() == 0.0
                ? stage2
                : stage2.filter(new HSBFilter(0f, (float) config.saturationBoost(), 0f));
    }

    // ─────────────────── Histogram stretch ───────────────────

    /**
     * Build a 256-bin histogram per RGB channel, find the
     * {@code percentile}-quantile low / high cut-off per channel, and
     * stretch values from {@code [low, high]} to {@code [0, 255]}.
     *
     * <p>Channels with a tiny dynamic range ({@code high - low < 8})
     * are skipped to avoid posterising near-monochrome inputs.
     * Alpha is preserved unchanged.
     */
    private static BufferedImage applyHistogramStretch(BufferedImage source, double percentile) {
        int w = source.getWidth();
        int h = source.getHeight();
        int total = w * h;
        if (total == 0) {
            return source;
        }
        int[] argb = new int[total];
        source.getRGB(0, 0, w, h, argb, 0, w);

        int[] histR = new int[256];
        int[] histG = new int[256];
        int[] histB = new int[256];
        for (int p : argb) {
            histR[(p >> 16) & 0xff]++;
            histG[(p >> 8) & 0xff]++;
            histB[p & 0xff]++;
        }

        int targetLow = (int) Math.max(1, Math.round(total * percentile));
        int targetHigh = (int) Math.max(1, Math.round(total * (1.0 - percentile)));

        int rLow = findCutoff(histR, targetLow, true);
        int rHigh = findCutoff(histR, targetHigh, false);
        int gLow = findCutoff(histG, targetLow, true);
        int gHigh = findCutoff(histG, targetHigh, false);
        int bLow = findCutoff(histB, targetLow, true);
        int bHigh = findCutoff(histB, targetHigh, false);

        boolean stretchR = (rHigh - rLow) >= MIN_DYNAMIC_RANGE;
        boolean stretchG = (gHigh - gLow) >= MIN_DYNAMIC_RANGE;
        boolean stretchB = (bHigh - bLow) >= MIN_DYNAMIC_RANGE;

        int[] mapR = stretchR ? buildLut(rLow, rHigh) : null;
        int[] mapG = stretchG ? buildLut(gLow, gHigh) : null;
        int[] mapB = stretchB ? buildLut(bLow, bHigh) : null;

        for (int i = 0; i < argb.length; i++) {
            int p = argb[i];
            int a = (p >> 24) & 0xff;
            int r = (p >> 16) & 0xff;
            int g = (p >> 8) & 0xff;
            int b = p & 0xff;
            if (mapR != null) r = mapR[r];
            if (mapG != null) g = mapG[g];
            if (mapB != null) b = mapB[b];
            argb[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }

        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        out.setRGB(0, 0, w, h, argb, 0, w);
        return out;
    }

    /** Minimal {@code high - low} window after the percentile clip
     *  before stretching is considered worth it. Below this, applying
     *  the LUT would amplify quantisation noise on near-monochrome
     *  channels. */
    private static final int MIN_DYNAMIC_RANGE = 8;

    /** Walk the histogram from the chosen end and return the bin where
     *  the cumulative pixel count first crosses {@code target}. */
    private static int findCutoff(int[] hist, int target, boolean fromLow) {
        int sum = 0;
        if (fromLow) {
            for (int i = 0; i < 256; i++) {
                sum += hist[i];
                if (sum >= target) return i;
            }
            return 0;
        }
        for (int i = 255; i >= 0; i--) {
            sum += hist[i];
            if (sum >= target) return i;
        }
        return 255;
    }

    /** Build a 256-entry lookup table that maps {@code [low, high]}
     *  linearly to {@code [0, 255]}, clamping values outside the
     *  source window to the endpoints. */
    private static int[] buildLut(int low, int high) {
        int[] lut = new int[256];
        int span = high - low;
        for (int i = 0; i < 256; i++) {
            if (i <= low) {
                lut[i] = 0;
            } else if (i >= high) {
                lut[i] = 255;
            } else {
                lut[i] = Math.min(255, Math.max(0, ((i - low) * 255) / span));
            }
        }
        return lut;
    }
}
