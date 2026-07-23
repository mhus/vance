package de.mhus.vance.brain.image;

import com.sksamuel.scrimage.ImmutableImage;
import com.sksamuel.scrimage.angles.Radians;
import com.sksamuel.scrimage.filter.BrightnessFilter;
import com.sksamuel.scrimage.filter.ContrastFilter;
import com.sksamuel.scrimage.filter.EdgeFilter;
import com.sksamuel.scrimage.filter.EmbossFilter;
import com.sksamuel.scrimage.filter.GammaFilter;
import com.sksamuel.scrimage.filter.GaussianBlurFilter;
import com.sksamuel.scrimage.filter.GrayscaleFilter;
import com.sksamuel.scrimage.filter.HSBFilter;
import com.sksamuel.scrimage.filter.InvertFilter;
import com.sksamuel.scrimage.filter.PosterizeFilter;
import com.sksamuel.scrimage.filter.SepiaFilter;
import com.sksamuel.scrimage.filter.SharpenFilter;
import com.sksamuel.scrimage.filter.SolarizeFilter;
import com.sksamuel.scrimage.filter.ThresholdFilter;
import com.sksamuel.scrimage.nio.GifWriter;
import com.sksamuel.scrimage.nio.JpegWriter;
import com.sksamuel.scrimage.nio.PngWriter;
import de.mhus.vance.api.progress.StatusTag;
import de.mhus.vance.brain.progress.ProgressEmitter;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.imageio.ImageIO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Pure-Java image manipulation on existing document assets. Loads a
 * source document from the {@link DocumentService}, applies a Scrimage
 * operation, and writes the bytes back — either overwriting the source
 * (document-versioning archives the prior fassung) or creating a new
 * document under a caller-supplied target path.
 *
 * <p>Spec: {@code specification/image-manipulation.md}. The service is
 * synchronous and blocks the caller's lane; operations on day-1 limits
 * (≤8192 px, ≤20 MB) run in well under a second on a typical pod.
 *
 * <p>Each public op-method delegates to
 * {@link #executeOp(String, String, String, String, String, String, String, UnaryOperator)}
 * which centralises the load → validate → emit-status → apply →
 * validate → encode → write pipeline. Per-op classes only contribute
 * the {@link UnaryOperator} that turns the input image into the
 * output and their op-specific parameter validation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImageManipulationService {

    public static final String SETTING_ENABLED = "image.tools.enabled";
    public static final String SETTING_MAX_INPUT_BYTES = "image.tools.max_input_bytes";
    public static final String SETTING_MAX_INPUT_DIMENSION = "image.tools.max_input_dimension";
    public static final String SETTING_MAX_OUTPUT_DIMENSION = "image.tools.max_output_dimension";
    public static final String SETTING_MAX_OUTPUT_BYTES = "image.tools.max_output_bytes";

    public static final String SETTING_AUTO_PERCENTILE = "image.tools.auto_enhance.percentile_clip";
    public static final String SETTING_AUTO_GAMMA = "image.tools.auto_enhance.gamma";
    public static final String SETTING_AUTO_SATURATION = "image.tools.auto_enhance.saturation_boost";

    public static final long DEFAULT_MAX_INPUT_BYTES = 20_000_000L;
    public static final int DEFAULT_MAX_INPUT_DIMENSION = 8192;
    public static final int DEFAULT_MAX_OUTPUT_DIMENSION = 8192;
    public static final long DEFAULT_MAX_OUTPUT_BYTES = 30_000_000L;

    public static final double DEFAULT_AUTO_PERCENTILE = 0.005;
    public static final double DEFAULT_AUTO_GAMMA = 0.95;
    public static final double DEFAULT_AUTO_SATURATION = 0.10;

    /** JPEG quality used for re-encode. Fixed at 92 in v1; per-call
     *  control is out of scope (spec §6). */
    public static final int JPEG_QUALITY = 92;

    /** MIME types that round-trip through Scrimage + ImageIO without
     *  native libs. {@code image/jpg} is accepted as a legacy alias of
     *  {@code image/jpeg} on the read side; the write side normalises
     *  to {@code image/jpeg}. */
    private static final Set<String> SUPPORTED_MIMES = Set.of(
            "image/png", "image/jpeg", "image/jpg", "image/gif", "image/bmp");

    private final DocumentService documentService;
    private final SettingService settingService;
    private final ProgressEmitter progressEmitter;
    private final ThinkProcessService thinkProcessService;
    private final MetricService metricService;

    // ─────────────────── Public op surface ───────────────────

    /** Rectangular crop. Coordinates 0-based, top-left origin. */
    public ImageOpResult crop(CropRequest req) {
        validateScope(req.getTenantId(), req.getPath());
        ensureEnabled(req.getTenantId(), req.getProjectId(), req.getProcessId());
        if (req.getX() < 0 || req.getY() < 0 || req.getWidth() <= 0 || req.getHeight() <= 0) {
            throw new ImageManipulationException(
                    ImageManipulationException.Reason.PARAMETER_INVALID,
                    "crop requires x>=0, y>=0, width>0, height>0; got x="
                            + req.getX() + " y=" + req.getY()
                            + " width=" + req.getWidth() + " height=" + req.getHeight());
        }
        return executeOp(
                "image_crop",
                req.getTenantId(), req.getProjectId(), req.getProcessId(), req.getUserId(),
                req.getPath(), req.getTargetPath(),
                (img, mime) -> {
                    if (req.getX() + req.getWidth() > img.width
                            || req.getY() + req.getHeight() > img.height) {
                        throw new ImageManipulationException(
                                ImageManipulationException.Reason.PARAMETER_INVALID,
                                "Crop rectangle " + req.getWidth() + "x" + req.getHeight()
                                        + " at (" + req.getX() + "," + req.getY()
                                        + ") exceeds image bounds "
                                        + img.width + "x" + img.height);
                    }
                    return img.subimage(req.getX(), req.getY(), req.getWidth(), req.getHeight());
                });
    }

    /** Five-mode resize. Mode determines which dimension fields are
     *  required + the strategy ({@link ResizeMode}). */
    public ImageOpResult resize(ResizeRequest req) {
        validateScope(req.getTenantId(), req.getPath());
        ensureEnabled(req.getTenantId(), req.getProjectId(), req.getProcessId());
        ResizeMode mode = req.getMode() == null ? ResizeMode.EXACT : req.getMode();
        validateResizeDimensions(mode, req.getWidth(), req.getHeight());
        return executeOp(
                "image_resize",
                req.getTenantId(), req.getProjectId(), req.getProcessId(), req.getUserId(),
                req.getPath(), req.getTargetPath(),
                (img, mime) -> doResize(img, mime, mode, req.getWidth(), req.getHeight(),
                        req.getBackground()));
    }

    /** Rotate clockwise by an arbitrary number of degrees. */
    public ImageOpResult rotate(RotateRequest req) {
        validateScope(req.getTenantId(), req.getPath());
        ensureEnabled(req.getTenantId(), req.getProjectId(), req.getProcessId());
        return executeOp(
                "image_rotate",
                req.getTenantId(), req.getProjectId(), req.getProcessId(), req.getUserId(),
                req.getPath(), req.getTargetPath(),
                (img, mime) -> img.rotate(
                        new Radians(Math.toRadians(req.getDegrees())),
                        defaultBackgroundForMime(req.getBackground(), mime)));
    }

    /** Horizontal or vertical flip. */
    public ImageOpResult flip(FlipRequest req) {
        validateScope(req.getTenantId(), req.getPath());
        ensureEnabled(req.getTenantId(), req.getProjectId(), req.getProcessId());
        FlipAxis axis = req.getAxis();
        if (axis == null) {
            throw new ImageManipulationException(
                    ImageManipulationException.Reason.PARAMETER_INVALID,
                    "'axis' is required: horizontal or vertical");
        }
        return executeOp(
                "image_flip",
                req.getTenantId(), req.getProjectId(), req.getProcessId(), req.getUserId(),
                req.getPath(), req.getTargetPath(),
                (img, mime) -> axis == FlipAxis.HORIZONTAL ? img.flipX() : img.flipY());
    }

    /** Combined gamma / brightness / contrast / saturation. Order is
     *  fixed ({@code gamma → brightness → contrast → saturation}). */
    public ImageOpResult adjust(AdjustRequest req) {
        validateScope(req.getTenantId(), req.getPath());
        ensureEnabled(req.getTenantId(), req.getProjectId(), req.getProcessId());
        if (req.getBrightness() == null && req.getContrast() == null
                && req.getSaturation() == null && req.getGamma() == null) {
            throw new ImageManipulationException(
                    ImageManipulationException.Reason.PARAMETER_INVALID,
                    "adjust requires at least one of brightness, contrast, saturation, gamma");
        }
        validateAdjustRanges(req);
        return executeOp(
                "image_adjust",
                req.getTenantId(), req.getProjectId(), req.getProcessId(), req.getUserId(),
                req.getPath(), req.getTargetPath(),
                (img, mime) -> doAdjust(img, req));
    }

    /** Apply one named filter. {@code filter} chooses the effect;
     *  {@code params} carries per-filter knobs (see §4.6). */
    public ImageOpResult filter(FilterRequest req) {
        validateScope(req.getTenantId(), req.getPath());
        ensureEnabled(req.getTenantId(), req.getProjectId(), req.getProcessId());
        FilterName filter = req.getFilter();
        if (filter == null) {
            throw new ImageManipulationException(
                    ImageManipulationException.Reason.PARAMETER_INVALID,
                    "'filter' is required");
        }
        Map<String, Object> params = req.getParams() == null ? Map.of() : req.getParams();
        return executeOp(
                "image_filter:" + filter.wire(),
                req.getTenantId(), req.getProjectId(), req.getProcessId(), req.getUserId(),
                req.getPath(), req.getTargetPath(),
                (img, mime) -> doFilter(img, filter, params));
    }

    /** The "magic wand" — classic histogram-stretch auto-enhance.
     *  See §5 for the algorithm. */
    public ImageOpResult autoEnhance(AutoEnhanceRequest req) {
        validateScope(req.getTenantId(), req.getPath());
        ensureEnabled(req.getTenantId(), req.getProjectId(), req.getProcessId());
        AutoEnhanceProcessor.Config config = readAutoEnhanceConfig(
                req.getTenantId(), req.getProjectId(), req.getProcessId());
        return executeOp(
                "image_auto_enhance",
                req.getTenantId(), req.getProjectId(), req.getProcessId(), req.getUserId(),
                req.getPath(), req.getTargetPath(),
                (img, mime) -> {
                    try {
                        return AutoEnhanceProcessor.enhance(img, config);
                    } catch (IOException e) {
                        throw new ImageManipulationException(
                                ImageManipulationException.Reason.PROCESSING_ERROR,
                                "auto_enhance failed: " + e.getMessage(), e);
                    }
                });
    }

    // ─────────────────── Per-op helpers ───────────────────

    private static void validateResizeDimensions(
            ResizeMode mode, @Nullable Integer width, @Nullable Integer height) {
        switch (mode) {
            case EXACT, COVER, CONTAIN -> {
                if (width == null || height == null || width <= 0 || height <= 0) {
                    throw new ImageManipulationException(
                            ImageManipulationException.Reason.PARAMETER_INVALID,
                            "resize mode '" + mode.wire() + "' requires width>0 AND height>0");
                }
            }
            case WIDTH -> {
                if (width == null || width <= 0) {
                    throw new ImageManipulationException(
                            ImageManipulationException.Reason.PARAMETER_INVALID,
                            "resize mode 'width' requires width>0");
                }
            }
            case HEIGHT -> {
                if (height == null || height <= 0) {
                    throw new ImageManipulationException(
                            ImageManipulationException.Reason.PARAMETER_INVALID,
                            "resize mode 'height' requires height>0");
                }
            }
        }
    }

    private static ImmutableImage doResize(
            ImmutableImage img, String sourceMime, ResizeMode mode,
            @Nullable Integer width, @Nullable Integer height, @Nullable String background) {
        return switch (mode) {
            case EXACT -> img.scaleTo(width, height);
            case WIDTH -> img.scaleToWidth(width);
            case HEIGHT -> img.scaleToHeight(height);
            case COVER -> img.cover(width, height);
            case CONTAIN -> img.fit(width, height, defaultBackgroundForMime(background, sourceMime));
        };
    }

    private static void validateAdjustRanges(AdjustRequest req) {
        if (req.getBrightness() != null && (req.getBrightness() < -1.0 || req.getBrightness() > 1.0)) {
            throw new ImageManipulationException(
                    ImageManipulationException.Reason.PARAMETER_INVALID,
                    "brightness must be in [-1.0, 1.0], got " + req.getBrightness());
        }
        if (req.getContrast() != null && (req.getContrast() < -1.0 || req.getContrast() > 1.0)) {
            throw new ImageManipulationException(
                    ImageManipulationException.Reason.PARAMETER_INVALID,
                    "contrast must be in [-1.0, 1.0], got " + req.getContrast());
        }
        if (req.getSaturation() != null && (req.getSaturation() < -1.0 || req.getSaturation() > 1.0)) {
            throw new ImageManipulationException(
                    ImageManipulationException.Reason.PARAMETER_INVALID,
                    "saturation must be in [-1.0, 1.0], got " + req.getSaturation());
        }
        if (req.getGamma() != null && (req.getGamma() < 0.1 || req.getGamma() > 5.0)) {
            throw new ImageManipulationException(
                    ImageManipulationException.Reason.PARAMETER_INVALID,
                    "gamma must be in [0.1, 5.0], got " + req.getGamma());
        }
    }

    private static ImmutableImage doAdjust(ImmutableImage img, AdjustRequest req) {
        ImmutableImage out = img;
        try {
            if (req.getGamma() != null) {
                out = out.filter(new GammaFilter(req.getGamma()));
            }
            if (req.getBrightness() != null) {
                float factor = (float) (1.0 + req.getBrightness());
                out = out.filter(new BrightnessFilter(factor));
            }
            if (req.getContrast() != null) {
                double factor = 1.0 + req.getContrast();
                out = out.filter(new ContrastFilter(factor));
            }
            if (req.getSaturation() != null) {
                out = out.filter(new HSBFilter(0f, req.getSaturation().floatValue(), 0f));
            }
            return out;
        } catch (IOException e) {
            throw new ImageManipulationException(
                    ImageManipulationException.Reason.PROCESSING_ERROR,
                    "adjust filter failed: " + e.getMessage(), e);
        }
    }

    private static ImmutableImage doFilter(
            ImmutableImage img, FilterName filter, Map<String, Object> params) {
        try {
            return switch (filter) {
                case BLUR_GAUSSIAN -> img.filter(
                        new GaussianBlurFilter(readIntParam(params, "radius", 5, 1, 50)));
                case SHARPEN -> img.filter(new SharpenFilter());
                case GRAYSCALE -> img.filter(new GrayscaleFilter());
                case SEPIA -> img.filter(new SepiaFilter());
                case INVERT -> img.filter(new InvertFilter());
                case EDGE -> img.filter(new EdgeFilter());
                case EMBOSS -> img.filter(new EmbossFilter());
                case POSTERIZE -> img.filter(
                        new PosterizeFilter(readIntParam(params, "levels", 4, 2, 8)));
                case SOLARIZE -> img.filter(new SolarizeFilter());
                case THRESHOLD -> img.filter(
                        new ThresholdFilter(readIntParam(params, "threshold", 128, 0, 255)));
            };
        } catch (IOException e) {
            throw new ImageManipulationException(
                    ImageManipulationException.Reason.PROCESSING_ERROR,
                    "filter '" + filter.wire() + "' failed: " + e.getMessage(), e);
        }
    }

    private static int readIntParam(
            Map<String, Object> params, String key, int defaultValue, int min, int max) {
        Object raw = params.get(key);
        int value;
        if (raw == null) {
            value = defaultValue;
        } else if (raw instanceof Number n) {
            value = n.intValue();
        } else {
            try {
                value = Integer.parseInt(raw.toString().trim());
            } catch (NumberFormatException e) {
                throw new ImageManipulationException(
                        ImageManipulationException.Reason.PARAMETER_INVALID,
                        "filter param '" + key + "' must be an integer, got '" + raw + "'");
            }
        }
        if (value < min || value > max) {
            throw new ImageManipulationException(
                    ImageManipulationException.Reason.PARAMETER_INVALID,
                    "filter param '" + key + "' must be in [" + min + ", " + max
                            + "], got " + value);
        }
        return value;
    }

    /** Choose a sensible default background for the source MIME: transparent
     *  for PNG/GIF, white for JPEG/BMP (which have no alpha). Caller's hex
     *  string always wins when set. */
    private static Color defaultBackgroundForMime(@Nullable String backgroundHex, String sourceMime) {
        if (backgroundHex != null && !backgroundHex.isBlank()) {
            return ColorParser.parseOrDefault(backgroundHex, Color.WHITE);
        }
        return supportsTransparency(sourceMime) ? new Color(0, 0, 0, 0) : Color.WHITE;
    }

    private static boolean supportsTransparency(String mime) {
        return "image/png".equals(mime) || "image/gif".equals(mime);
    }

    private AutoEnhanceProcessor.Config readAutoEnhanceConfig(
            String tenantId, @Nullable String projectId, @Nullable String processId) {
        return new AutoEnhanceProcessor.Config(
                doubleSetting(tenantId, projectId, processId,
                        SETTING_AUTO_PERCENTILE, DEFAULT_AUTO_PERCENTILE, 0.0, 0.25),
                doubleSetting(tenantId, projectId, processId,
                        SETTING_AUTO_GAMMA, DEFAULT_AUTO_GAMMA, 0.1, 5.0),
                doubleSetting(tenantId, projectId, processId,
                        SETTING_AUTO_SATURATION, DEFAULT_AUTO_SATURATION, -1.0, 1.0));
    }

    // ─────────────────── Shared pipeline ───────────────────

    /** Per-call op signature. Takes the loaded image plus the source
     *  MIME (so e.g. resize/rotate can pick a transparency-friendly
     *  default background only when the output format actually supports
     *  it). Implementations may throw {@link ImageManipulationException}
     *  for parameter-validation problems detected only after the image
     *  is decoded. */
    @FunctionalInterface
    private interface ImageOp {
        ImmutableImage apply(ImmutableImage input, String sourceMime);
    }

    private ImageOpResult executeOp(
            String opName,
            String tenantId,
            @Nullable String projectId,
            @Nullable String processId,
            @Nullable String userId,
            String sourcePath,
            @Nullable String targetPath,
            ImageOp op) {
        long startMs = System.currentTimeMillis();
        Limits limits = readLimits(tenantId, projectId, processId);
        String effectiveProject = projectId == null ? "" : projectId;

        DocumentDocument source = loadSource(tenantId, effectiveProject, sourcePath, limits);
        String normalizedMime = source.getMimeType().toLowerCase(Locale.ROOT);

        byte[] sourceBytes = readBytes(source, limits);
        ImmutableImage input = decode(sourceBytes);
        ensureInputDimensions(input, limits);

        emitInitialStatus(processId, opName);

        ImmutableImage output = applyOp(opName, startMs, op, input, normalizedMime);

        if (output.width > limits.maxOutputDimension() || output.height > limits.maxOutputDimension()) {
            recordOutcome(opName, "limit_exceeded", startMs);
            throw new ImageManipulationException(
                    ImageManipulationException.Reason.LIMIT_EXCEEDED,
                    "Output dimensions " + output.width + "x" + output.height
                            + " exceed " + limits.maxOutputDimension()
                            + " (" + SETTING_MAX_OUTPUT_DIMENSION + ")");
        }

        String outputMime = normaliseOutputMime(normalizedMime);
        byte[] outBytes = encode(opName, startMs, output, outputMime);
        if (outBytes.length > limits.maxOutputBytes()) {
            recordOutcome(opName, "limit_exceeded", startMs);
            throw new ImageManipulationException(
                    ImageManipulationException.Reason.LIMIT_EXCEEDED,
                    "Output size " + outBytes.length + " bytes exceeds "
                            + limits.maxOutputBytes() + " (" + SETTING_MAX_OUTPUT_BYTES + ")");
        }

        String effectiveTarget = resolveTarget(sourcePath, targetPath);
        ensureTargetIsWritable(opName, startMs, tenantId, effectiveProject, sourcePath, effectiveTarget);

        DocumentDocument written = documentService.createOrReplaceBinary(
                tenantId, effectiveProject, effectiveTarget,
                outBytes, outputMime,
                null, null, null, userId,
                de.mhus.vance.shared.permission.WriteActor.SYSTEM);

        long durationMs = System.currentTimeMillis() - startMs;
        recordOutcome(opName, "success", startMs);
        return new ImageOpResult(
                written.getPath(),
                written.getMimeType() == null ? outputMime : written.getMimeType(),
                output.width,
                output.height,
                written.getSize() > 0 ? written.getSize() : outBytes.length,
                durationMs);
    }

    private DocumentDocument loadSource(
            String tenantId, String projectId, String sourcePath, Limits limits) {
        DocumentDocument source = documentService.findByPath(tenantId, projectId, sourcePath)
                .orElseThrow(() -> new ImageManipulationException(
                        ImageManipulationException.Reason.SOURCE_NOT_FOUND,
                        "No document at path '" + sourcePath + "'"));
        String mime = source.getMimeType();
        if (mime == null || !mime.toLowerCase(Locale.ROOT).startsWith("image/")) {
            throw new ImageManipulationException(
                    ImageManipulationException.Reason.NOT_AN_IMAGE,
                    "Document '" + sourcePath + "' is not an image (mimeType="
                            + (mime == null ? "null" : mime) + ")");
        }
        if (!SUPPORTED_MIMES.contains(mime.toLowerCase(Locale.ROOT))) {
            throw new ImageManipulationException(
                    ImageManipulationException.Reason.FORMAT_UNSUPPORTED,
                    "Image format '" + mime + "' is not supported on day 1. "
                            + "Supported: png, jpeg, gif, bmp. WebP / HEIC / TIFF are not enabled "
                            + "(see specification/image-manipulation.md §3).");
        }
        if (source.getSize() > limits.maxInputBytes()) {
            throw new ImageManipulationException(
                    ImageManipulationException.Reason.LIMIT_EXCEEDED,
                    "Source image is " + source.getSize() + " bytes; limit is "
                            + limits.maxInputBytes() + " (" + SETTING_MAX_INPUT_BYTES + ")");
        }
        return source;
    }

    private byte[] readBytes(DocumentDocument source, Limits limits) {
        try (InputStream in = documentService.loadContent(source)) {
            byte[] bytes = in.readAllBytes();
            if (bytes.length > limits.maxInputBytes()) {
                throw new ImageManipulationException(
                        ImageManipulationException.Reason.LIMIT_EXCEEDED,
                        "Source image is " + bytes.length + " bytes; limit is "
                                + limits.maxInputBytes() + " (" + SETTING_MAX_INPUT_BYTES + ")");
            }
            return bytes;
        } catch (IOException e) {
            throw new ImageManipulationException(
                    ImageManipulationException.Reason.PROCESSING_ERROR,
                    "Failed to read source image bytes: " + e.getMessage(), e);
        }
    }

    private ImmutableImage decode(byte[] bytes) {
        try {
            return ImmutableImage.loader().fromBytes(bytes);
        } catch (IOException e) {
            throw new ImageManipulationException(
                    ImageManipulationException.Reason.PROCESSING_ERROR,
                    "Failed to decode image: " + e.getMessage(), e);
        }
    }

    private void ensureInputDimensions(ImmutableImage img, Limits limits) {
        if (img.width > limits.maxInputDimension() || img.height > limits.maxInputDimension()) {
            throw new ImageManipulationException(
                    ImageManipulationException.Reason.LIMIT_EXCEEDED,
                    "Source dimensions " + img.width + "x" + img.height
                            + " exceed " + limits.maxInputDimension()
                            + " (" + SETTING_MAX_INPUT_DIMENSION + ")");
        }
    }

    private ImmutableImage applyOp(
            String opName, long startMs,
            ImageOp op, ImmutableImage input, String sourceMime) {
        try {
            return op.apply(input, sourceMime);
        } catch (ImageManipulationException e) {
            recordOutcome(opName, e.getReason().wire(), startMs);
            throw e;
        } catch (RuntimeException e) {
            recordOutcome(opName, "processing_error", startMs);
            throw new ImageManipulationException(
                    ImageManipulationException.Reason.PROCESSING_ERROR,
                    "Operation '" + opName + "' failed: " + e.getMessage(), e);
        }
    }

    private byte[] encode(
            String opName, long startMs, ImmutableImage img, String mime) {
        try {
            return switch (mime) {
                case "image/png" -> img.bytes(new PngWriter());
                case "image/jpeg" -> img.bytes(new JpegWriter().withCompression(JPEG_QUALITY));
                case "image/gif" -> img.bytes(GifWriter.Default);
                case "image/bmp" -> encodeViaImageIO(img, "bmp");
                default -> throw new ImageManipulationException(
                        ImageManipulationException.Reason.FORMAT_UNSUPPORTED,
                        "Unsupported output mime " + mime);
            };
        } catch (IOException e) {
            recordOutcome(opName, "processing_error", startMs);
            throw new ImageManipulationException(
                    ImageManipulationException.Reason.PROCESSING_ERROR,
                    "Failed to encode output as " + mime + ": " + e.getMessage(), e);
        }
    }

    private static byte[] encodeViaImageIO(ImmutableImage img, String formatName) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean ok = ImageIO.write(img.awt(), formatName, out);
        if (!ok) {
            throw new IOException("No ImageIO writer registered for format " + formatName);
        }
        return out.toByteArray();
    }

    /** {@code image/jpg} is a long-standing legacy alias users still
     *  write into MIME fields. We accept it on read but always write
     *  back as the IANA-registered {@code image/jpeg}. */
    private static String normaliseOutputMime(String inputMime) {
        return "image/jpg".equals(inputMime) ? "image/jpeg" : inputMime;
    }

    private static String resolveTarget(String sourcePath, @Nullable String targetPath) {
        if (targetPath == null || targetPath.isBlank()) {
            return sourcePath;
        }
        String trimmed = targetPath.trim();
        return trimmed.equals(sourcePath) ? sourcePath : trimmed;
    }

    private void ensureTargetIsWritable(
            String opName, long startMs,
            String tenantId, String projectId,
            String sourcePath, String effectiveTarget) {
        if (effectiveTarget.equals(sourcePath)) {
            return;
        }
        Optional<DocumentDocument> existing = documentService.findByPath(
                tenantId, projectId, effectiveTarget);
        if (existing.isEmpty()) {
            return;
        }
        String existingMime = existing.get().getMimeType();
        if (existingMime == null
                || !SUPPORTED_MIMES.contains(existingMime.toLowerCase(Locale.ROOT))) {
            recordOutcome(opName, "target_blocked", startMs);
            throw new ImageManipulationException(
                    ImageManipulationException.Reason.TARGET_BLOCKED,
                    "Target '" + effectiveTarget + "' exists but is not a supported image "
                            + "(mimeType=" + existingMime + ")");
        }
    }

    private void emitInitialStatus(@Nullable String processId, String opName) {
        if (processId == null || processId.isBlank()) {
            return;
        }
        ThinkProcessDocument process = thinkProcessService.findById(processId).orElse(null);
        if (process == null) {
            return;
        }
        try {
            progressEmitter.emitStatus(process, StatusTag.WAITING,
                    opName.replace('_', ' ') + " …");
        } catch (RuntimeException e) {
            log.debug("ImageManipulationService: status emit failed: {}", e.toString());
        }
    }

    private void recordOutcome(String opName, String outcome, long startMs) {
        long duration = System.currentTimeMillis() - startMs;
        try {
            metricService.counter("vance.image.tools.calls",
                    "tool", opName, "outcome", outcome).increment();
            metricService.timer("vance.image.tools.duration",
                    "tool", opName).record(Duration.ofMillis(duration));
        } catch (RuntimeException e) {
            log.debug("ImageManipulationService: metric record failed: {}", e.toString());
        }
    }

    // ─────────────────── Validation / gating ───────────────────

    private static void validateScope(String tenantId, String path) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (path == null || path.isBlank()) {
            throw new ImageManipulationException(
                    ImageManipulationException.Reason.PARAMETER_INVALID,
                    "'path' is required");
        }
    }

    private void ensureEnabled(
            String tenantId, @Nullable String projectId, @Nullable String processId) {
        boolean enabled = settingService.getBooleanValueCascade(
                tenantId, projectId, processId, SETTING_ENABLED, true);
        if (!enabled) {
            throw new ImageManipulationException(
                    ImageManipulationException.Reason.DISABLED,
                    "Image manipulation tools are disabled in this scope ("
                            + SETTING_ENABLED + " = false)");
        }
    }

    // ─────────────────── Settings ───────────────────

    private record Limits(
            long maxInputBytes,
            int maxInputDimension,
            int maxOutputDimension,
            long maxOutputBytes) {}

    private Limits readLimits(
            String tenantId, @Nullable String projectId, @Nullable String processId) {
        return new Limits(
                longSetting(tenantId, projectId, processId,
                        SETTING_MAX_INPUT_BYTES, DEFAULT_MAX_INPUT_BYTES),
                intSetting(tenantId, projectId, processId,
                        SETTING_MAX_INPUT_DIMENSION, DEFAULT_MAX_INPUT_DIMENSION),
                intSetting(tenantId, projectId, processId,
                        SETTING_MAX_OUTPUT_DIMENSION, DEFAULT_MAX_OUTPUT_DIMENSION),
                longSetting(tenantId, projectId, processId,
                        SETTING_MAX_OUTPUT_BYTES, DEFAULT_MAX_OUTPUT_BYTES));
    }

    private long longSetting(
            String tenantId, @Nullable String projectId, @Nullable String processId,
            String key, long defaultValue) {
        String raw = settingService.getStringValueCascade(tenantId, projectId, processId, key);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            long parsed = Long.parseLong(raw.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            log.warn("ImageManipulationService: non-numeric setting '{}'='{}', using default {}",
                    key, raw, defaultValue);
            return defaultValue;
        }
    }

    private int intSetting(
            String tenantId, @Nullable String projectId, @Nullable String processId,
            String key, int defaultValue) {
        String raw = settingService.getStringValueCascade(tenantId, projectId, processId, key);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            log.warn("ImageManipulationService: non-numeric setting '{}'='{}', using default {}",
                    key, raw, defaultValue);
            return defaultValue;
        }
    }

    private double doubleSetting(
            String tenantId, @Nullable String projectId, @Nullable String processId,
            String key, double defaultValue, double min, double max) {
        String raw = settingService.getStringValueCascade(tenantId, projectId, processId, key);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            double parsed = Double.parseDouble(raw.trim());
            if (parsed < min || parsed > max) {
                log.warn("ImageManipulationService: setting '{}'={} outside [{}, {}], using default {}",
                        key, parsed, min, max, defaultValue);
                return defaultValue;
            }
            return parsed;
        } catch (NumberFormatException e) {
            log.warn("ImageManipulationService: non-numeric setting '{}'='{}', using default {}",
                    key, raw, defaultValue);
            return defaultValue;
        }
    }
}
