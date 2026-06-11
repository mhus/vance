package de.mhus.vance.brain.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sksamuel.scrimage.ImmutableImage;
import de.mhus.vance.brain.progress.ProgressEmitter;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link ImageManipulationService}. Mocks the
 * {@link DocumentService}, settings, progress + metrics; uses a
 * real Scrimage roundtrip on a small synthetic PNG so the crop
 * verifies the actual library wiring (not just the orchestration).
 */
class ImageManipulationServiceTest {

    private DocumentService documentService;
    private SettingService settingService;
    private ProgressEmitter progressEmitter;
    private ThinkProcessService thinkProcessService;
    private MetricService metricService;

    private ImageManipulationService service;

    private byte[] redPng40x40;

    @BeforeEach
    void setUp() throws IOException {
        documentService = mock(DocumentService.class);
        settingService = mock(SettingService.class);
        progressEmitter = mock(ProgressEmitter.class);
        thinkProcessService = mock(ThinkProcessService.class);
        metricService = mock(MetricService.class);

        when(settingService.getBooleanValueCascade(
                anyString(), any(), any(), eq(ImageManipulationService.SETTING_ENABLED), anyBoolean()))
                .thenReturn(true);
        when(settingService.getStringValueCascade(anyString(), any(), any(), anyString()))
                .thenReturn(null);

        service = new ImageManipulationService(
                documentService, settingService, progressEmitter,
                thinkProcessService, metricService);

        redPng40x40 = renderSolidPng(40, 40, Color.RED);
    }

    @Test
    void crop_writesSubregion_back_to_source_path() {
        DocumentDocument source = imageDoc("images/cat.png", "image/png", redPng40x40.length);
        stubFindByPath("images/cat.png", source);
        stubLoadContent(source, redPng40x40);
        stubCreateOrReplaceBinaryEcho();

        ImageOpResult result = service.crop(cropOf(source.getPath(), 5, 5, 20, 10));

        ArgumentCaptor<byte[]> bytesCap = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<String> pathCap = ArgumentCaptor.forClass(String.class);
        verify(documentService).createOrReplaceBinary(
                eq("acme"), eq("p1"), pathCap.capture(),
                bytesCap.capture(), eq("image/png"),
                any(), any(), any(), eq("alice"));

        assertThat(pathCap.getValue()).isEqualTo("images/cat.png");
        assertThat(result.path()).isEqualTo("images/cat.png");
        assertThat(result.mimeType()).isEqualTo("image/png");
        assertThat(result.width()).isEqualTo(20);
        assertThat(result.height()).isEqualTo(10);
        assertThat(result.sizeBytes()).isEqualTo(bytesCap.getValue().length);

        // Decode the written bytes as a final sanity check that they
        // form a real PNG of the expected dimensions.
        ImmutableImage roundtrip = decodePngOrFail(bytesCap.getValue());
        assertThat(roundtrip.width).isEqualTo(20);
        assertThat(roundtrip.height).isEqualTo(10);
    }

    @Test
    void crop_targetPath_creates_new_document() {
        DocumentDocument source = imageDoc("images/cat.png", "image/png", redPng40x40.length);
        stubFindByPath("images/cat.png", source);
        stubFindByPathEmpty("images/cat-crop.png");
        stubLoadContent(source, redPng40x40);
        stubCreateOrReplaceBinaryEcho();

        CropRequest request = CropRequest.builder()
                .tenantId("acme").userId("alice").projectId("p1").processId("proc-1")
                .path(source.getPath())
                .targetPath("images/cat-crop.png")
                .x(0).y(0).width(15).height(15)
                .build();
        service.crop(request);

        verify(documentService).createOrReplaceBinary(
                eq("acme"), eq("p1"), eq("images/cat-crop.png"),
                any(byte[].class), eq("image/png"),
                any(), any(), any(), eq("alice"));
    }

    @Test
    void crop_rejects_when_rectangle_exceeds_bounds() {
        DocumentDocument source = imageDoc("images/cat.png", "image/png", redPng40x40.length);
        stubFindByPath("images/cat.png", source);
        stubLoadContent(source, redPng40x40);

        assertThatThrownBy(() -> service.crop(cropOf(source.getPath(), 30, 30, 20, 20)))
                .isInstanceOf(ImageManipulationException.class)
                .extracting(e -> ((ImageManipulationException) e).getReason())
                .isEqualTo(ImageManipulationException.Reason.PARAMETER_INVALID);

        verify(documentService, never()).createOrReplaceBinary(
                any(), any(), any(), any(byte[].class), any(), any(), any(), any(), any());
    }

    @Test
    void crop_rejects_negative_coordinates() {
        assertThatThrownBy(() -> service.crop(cropOf("images/cat.png", -1, 0, 5, 5)))
                .isInstanceOf(ImageManipulationException.class)
                .extracting(e -> ((ImageManipulationException) e).getReason())
                .isEqualTo(ImageManipulationException.Reason.PARAMETER_INVALID);
    }

    @Test
    void crop_source_not_found_when_document_missing() {
        when(documentService.findByPath("acme", "p1", "images/missing.png"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.crop(cropOf("images/missing.png", 0, 0, 5, 5)))
                .isInstanceOf(ImageManipulationException.class)
                .extracting(e -> ((ImageManipulationException) e).getReason())
                .isEqualTo(ImageManipulationException.Reason.SOURCE_NOT_FOUND);
    }

    @Test
    void crop_not_an_image_when_mime_is_text() {
        DocumentDocument text = imageDoc("docs/note.txt", "text/plain", 100);
        stubFindByPath(text.getPath(), text);

        assertThatThrownBy(() -> service.crop(cropOf(text.getPath(), 0, 0, 5, 5)))
                .isInstanceOf(ImageManipulationException.class)
                .extracting(e -> ((ImageManipulationException) e).getReason())
                .isEqualTo(ImageManipulationException.Reason.NOT_AN_IMAGE);
    }

    @Test
    void crop_format_unsupported_for_webp() {
        DocumentDocument webp = imageDoc("images/cat.webp", "image/webp", 1000);
        stubFindByPath(webp.getPath(), webp);

        assertThatThrownBy(() -> service.crop(cropOf(webp.getPath(), 0, 0, 5, 5)))
                .isInstanceOf(ImageManipulationException.class)
                .extracting(e -> ((ImageManipulationException) e).getReason())
                .isEqualTo(ImageManipulationException.Reason.FORMAT_UNSUPPORTED);
    }

    @Test
    void crop_disabled_when_setting_off() {
        when(settingService.getBooleanValueCascade(
                anyString(), any(), any(), eq(ImageManipulationService.SETTING_ENABLED), anyBoolean()))
                .thenReturn(false);

        assertThatThrownBy(() -> service.crop(cropOf("images/cat.png", 0, 0, 5, 5)))
                .isInstanceOf(ImageManipulationException.class)
                .extracting(e -> ((ImageManipulationException) e).getReason())
                .isEqualTo(ImageManipulationException.Reason.DISABLED);
    }

    @Test
    void crop_limit_exceeded_when_input_too_large() {
        when(settingService.getStringValueCascade(
                anyString(), any(), any(), eq(ImageManipulationService.SETTING_MAX_INPUT_BYTES)))
                .thenReturn("100");

        DocumentDocument big = imageDoc("images/big.png", "image/png", redPng40x40.length);
        stubFindByPath(big.getPath(), big);

        assertThatThrownBy(() -> service.crop(cropOf(big.getPath(), 0, 0, 5, 5)))
                .isInstanceOf(ImageManipulationException.class)
                .extracting(e -> ((ImageManipulationException) e).getReason())
                .isEqualTo(ImageManipulationException.Reason.LIMIT_EXCEEDED);
    }

    // ─────────────────── Resize ───────────────────

    @Test
    void resize_exact_writes_new_dimensions() {
        DocumentDocument source = imageDoc("images/cat.png", "image/png", redPng40x40.length);
        stubFindByPath(source.getPath(), source);
        stubLoadContent(source, redPng40x40);
        stubCreateOrReplaceBinaryEcho();

        ImageOpResult result = service.resize(ResizeRequest.builder()
                .tenantId("acme").userId("alice").projectId("p1").processId("proc-1")
                .path(source.getPath())
                .mode(ResizeMode.EXACT).width(80).height(20)
                .build());

        assertThat(result.width()).isEqualTo(80);
        assertThat(result.height()).isEqualTo(20);
        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(documentService).createOrReplaceBinary(
                eq("acme"), eq("p1"), eq("images/cat.png"),
                bytes.capture(), eq("image/png"),
                any(), any(), any(), any());
        ImmutableImage rt = decodePngOrFail(bytes.getValue());
        assertThat(rt.width).isEqualTo(80);
        assertThat(rt.height).isEqualTo(20);
    }

    @Test
    void resize_exact_requires_both_dimensions() {
        DocumentDocument source = imageDoc("images/cat.png", "image/png", redPng40x40.length);
        stubFindByPath(source.getPath(), source);

        assertThatThrownBy(() -> service.resize(ResizeRequest.builder()
                .tenantId("acme").projectId("p1").path(source.getPath())
                .mode(ResizeMode.EXACT).width(100)
                .build()))
                .isInstanceOf(ImageManipulationException.class)
                .extracting(e -> ((ImageManipulationException) e).getReason())
                .isEqualTo(ImageManipulationException.Reason.PARAMETER_INVALID);
    }

    @Test
    void resize_width_only_scales_proportionally() {
        DocumentDocument source = imageDoc("images/cat.png", "image/png", redPng40x40.length);
        stubFindByPath(source.getPath(), source);
        stubLoadContent(source, redPng40x40);
        stubCreateOrReplaceBinaryEcho();

        ImageOpResult result = service.resize(ResizeRequest.builder()
                .tenantId("acme").projectId("p1").path(source.getPath())
                .mode(ResizeMode.WIDTH).width(20)
                .build());

        // Source is 40x40, target width=20 → height should be 20 (preserves 1:1).
        assertThat(result.width()).isEqualTo(20);
        assertThat(result.height()).isEqualTo(20);
    }

    @Test
    void resize_rejects_when_target_dimension_exceeds_limit() {
        when(settingService.getStringValueCascade(
                anyString(), any(), any(),
                eq(ImageManipulationService.SETTING_MAX_OUTPUT_DIMENSION)))
                .thenReturn("50");
        DocumentDocument source = imageDoc("images/cat.png", "image/png", redPng40x40.length);
        stubFindByPath(source.getPath(), source);
        stubLoadContent(source, redPng40x40);

        assertThatThrownBy(() -> service.resize(ResizeRequest.builder()
                .tenantId("acme").projectId("p1").path(source.getPath())
                .mode(ResizeMode.EXACT).width(100).height(100)
                .build()))
                .isInstanceOf(ImageManipulationException.class)
                .extracting(e -> ((ImageManipulationException) e).getReason())
                .isEqualTo(ImageManipulationException.Reason.LIMIT_EXCEEDED);
    }

    // ─────────────────── Rotate ───────────────────

    @Test
    void rotate_90deg_swaps_dimensions() {
        // 40×20 → rotated 90° should yield ≈ 20×40 (with corner padding the
        // bounding box can be larger by 1 px when the rotation is computed
        // via Scrimage's general-purpose path; we don't care about exact dims).
        byte[] rect = renderSolidPngSafe(40, 20, Color.BLUE);
        DocumentDocument source = imageDoc("images/r.png", "image/png", rect.length);
        stubFindByPath(source.getPath(), source);
        stubLoadContent(source, rect);
        stubCreateOrReplaceBinaryEcho();

        ImageOpResult result = service.rotate(RotateRequest.builder()
                .tenantId("acme").projectId("p1").path(source.getPath())
                .degrees(90)
                .build());

        assertThat(result.width()).isLessThanOrEqualTo(rect.length);
        // Width and height should have swapped roles (allowing ±1 px slack).
        assertThat(result.height()).isBetween(38, 42);
        assertThat(result.width()).isBetween(18, 22);
    }

    // ─────────────────── Flip ───────────────────

    @Test
    void flip_horizontal_preserves_dimensions() {
        DocumentDocument source = imageDoc("images/cat.png", "image/png", redPng40x40.length);
        stubFindByPath(source.getPath(), source);
        stubLoadContent(source, redPng40x40);
        stubCreateOrReplaceBinaryEcho();

        ImageOpResult result = service.flip(FlipRequest.builder()
                .tenantId("acme").projectId("p1").path(source.getPath())
                .axis(FlipAxis.HORIZONTAL)
                .build());

        assertThat(result.width()).isEqualTo(40);
        assertThat(result.height()).isEqualTo(40);
    }

    @Test
    void flip_requires_axis() {
        DocumentDocument source = imageDoc("images/cat.png", "image/png", redPng40x40.length);
        stubFindByPath(source.getPath(), source);

        assertThatThrownBy(() -> service.flip(FlipRequest.builder()
                .tenantId("acme").projectId("p1").path(source.getPath())
                .build()))
                .isInstanceOf(ImageManipulationException.class)
                .extracting(e -> ((ImageManipulationException) e).getReason())
                .isEqualTo(ImageManipulationException.Reason.PARAMETER_INVALID);
    }

    // ─────────────────── Adjust ───────────────────

    @Test
    void adjust_requires_at_least_one_setting() {
        DocumentDocument source = imageDoc("images/cat.png", "image/png", redPng40x40.length);
        stubFindByPath(source.getPath(), source);

        assertThatThrownBy(() -> service.adjust(AdjustRequest.builder()
                .tenantId("acme").projectId("p1").path(source.getPath())
                .build()))
                .isInstanceOf(ImageManipulationException.class)
                .extracting(e -> ((ImageManipulationException) e).getReason())
                .isEqualTo(ImageManipulationException.Reason.PARAMETER_INVALID);
    }

    @Test
    void adjust_rejects_out_of_range_brightness() {
        DocumentDocument source = imageDoc("images/cat.png", "image/png", redPng40x40.length);
        stubFindByPath(source.getPath(), source);

        assertThatThrownBy(() -> service.adjust(AdjustRequest.builder()
                .tenantId("acme").projectId("p1").path(source.getPath())
                .brightness(2.5)
                .build()))
                .isInstanceOf(ImageManipulationException.class)
                .extracting(e -> ((ImageManipulationException) e).getReason())
                .isEqualTo(ImageManipulationException.Reason.PARAMETER_INVALID);
    }

    @Test
    void adjust_single_field_applies_and_writes_back() {
        DocumentDocument source = imageDoc("images/cat.png", "image/png", redPng40x40.length);
        stubFindByPath(source.getPath(), source);
        stubLoadContent(source, redPng40x40);
        stubCreateOrReplaceBinaryEcho();

        service.adjust(AdjustRequest.builder()
                .tenantId("acme").projectId("p1").path(source.getPath())
                .brightness(0.2)
                .build());

        verify(documentService).createOrReplaceBinary(
                eq("acme"), eq("p1"), eq("images/cat.png"),
                any(byte[].class), eq("image/png"),
                any(), any(), any(), any());
    }

    // ─────────────────── Filter ───────────────────

    @Test
    void filter_grayscale_works() {
        DocumentDocument source = imageDoc("images/cat.png", "image/png", redPng40x40.length);
        stubFindByPath(source.getPath(), source);
        stubLoadContent(source, redPng40x40);
        stubCreateOrReplaceBinaryEcho();

        ImageOpResult result = service.filter(FilterRequest.builder()
                .tenantId("acme").projectId("p1").path(source.getPath())
                .filter(FilterName.GRAYSCALE)
                .build());

        assertThat(result.width()).isEqualTo(40);
        assertThat(result.height()).isEqualTo(40);
    }

    @Test
    void filter_gaussian_radius_out_of_range_rejected() {
        DocumentDocument source = imageDoc("images/cat.png", "image/png", redPng40x40.length);
        stubFindByPath(source.getPath(), source);
        stubLoadContent(source, redPng40x40);

        assertThatThrownBy(() -> service.filter(FilterRequest.builder()
                .tenantId("acme").projectId("p1").path(source.getPath())
                .filter(FilterName.BLUR_GAUSSIAN)
                .param("radius", 100)
                .build()))
                .isInstanceOf(ImageManipulationException.class)
                .extracting(e -> ((ImageManipulationException) e).getReason())
                .isEqualTo(ImageManipulationException.Reason.PARAMETER_INVALID);
    }

    // ─────────────────── Auto-enhance ───────────────────

    @Test
    void auto_enhance_writes_back_same_dimensions() {
        // Use a low-contrast input so auto-enhance has something to stretch.
        byte[] grayish = renderSolidPngSafe(40, 40, new Color(100, 100, 100));
        DocumentDocument source = imageDoc("images/flat.png", "image/png", grayish.length);
        stubFindByPath(source.getPath(), source);
        stubLoadContent(source, grayish);
        stubCreateOrReplaceBinaryEcho();

        ImageOpResult result = service.autoEnhance(AutoEnhanceRequest.builder()
                .tenantId("acme").projectId("p1").path(source.getPath())
                .build());

        assertThat(result.width()).isEqualTo(40);
        assertThat(result.height()).isEqualTo(40);
        verify(documentService).createOrReplaceBinary(
                eq("acme"), eq("p1"), eq("images/flat.png"),
                any(byte[].class), eq("image/png"),
                any(), any(), any(), any());
    }

    // ─────────────────── Helpers ───────────────────

    private CropRequest cropOf(String path, int x, int y, int w, int h) {
        return CropRequest.builder()
                .tenantId("acme")
                .userId("alice")
                .projectId("p1")
                .processId("proc-1")
                .path(path)
                .x(x).y(y).width(w).height(h)
                .build();
    }

    private DocumentDocument imageDoc(String path, String mime, long size) {
        DocumentDocument doc = new DocumentDocument();
        doc.setId("doc-" + path.hashCode());
        doc.setPath(path);
        doc.setMimeType(mime);
        doc.setSize(size);
        doc.setStorageId("storage-" + path.hashCode());
        return doc;
    }

    private void stubFindByPath(String path, DocumentDocument doc) {
        when(documentService.findByPath("acme", "p1", path))
                .thenReturn(Optional.of(doc));
    }

    private void stubFindByPathEmpty(String path) {
        when(documentService.findByPath("acme", "p1", path))
                .thenReturn(Optional.empty());
    }

    private void stubLoadContent(DocumentDocument source, byte[] bytes) {
        when(documentService.loadContent(source))
                .thenReturn(new ByteArrayInputStream(bytes));
    }

    private void stubCreateOrReplaceBinaryEcho() {
        when(documentService.createOrReplaceBinary(
                any(), any(), any(), any(byte[].class), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    String path = inv.getArgument(2);
                    byte[] bytes = inv.getArgument(3);
                    String mime = inv.getArgument(4);
                    DocumentDocument doc = new DocumentDocument();
                    doc.setId("written-" + path.hashCode());
                    doc.setPath(path);
                    doc.setMimeType(mime);
                    doc.setSize(bytes.length);
                    return doc;
                });
    }

    private static byte[] renderSolidPng(int width, int height, Color color) throws IOException {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(color);
            g.fillRect(0, 0, width, height);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /** Wraps {@link #renderSolidPng} so call-sites in test bodies don't
     *  have to declare {@code throws IOException}. */
    private static byte[] renderSolidPngSafe(int w, int h, Color c) {
        try {
            return renderSolidPng(w, h, c);
        } catch (IOException e) {
            throw new AssertionError("Failed to render test PNG", e);
        }
    }

    private static ImmutableImage decodePngOrFail(byte[] bytes) {
        try {
            return ImmutableImage.loader().fromBytes(bytes);
        } catch (IOException e) {
            throw new AssertionError("Failed to decode output bytes as PNG", e);
        }
    }
}
