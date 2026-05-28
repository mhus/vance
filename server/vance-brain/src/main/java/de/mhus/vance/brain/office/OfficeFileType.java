package de.mhus.vance.brain.office;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Map document mime-types onto the {@code fileType} + {@code documentType}
 * pair that the ONLYOFFICE / Collabora editor config expects.
 *
 * <p>ONLYOFFICE groups files into three editor flavours
 * ({@code word}, {@code cell}, {@code slide}); the {@code fileType}
 * is the bare extension. Both go into the editor-config payload.
 */
public final class OfficeFileType {

    private OfficeFileType() {}

    /** Bare extension ({@code "docx"}, {@code "xlsx"}, …) for the
     *  given mime, lowercase. Falls back to {@code "docx"} when the
     *  mime is unknown — the editor will refuse to open it then,
     *  which surfaces the problem to the user cleanly. */
    public static String extension(@Nullable String mimeType) {
        if (mimeType == null) return "docx";
        String mt = mimeType.toLowerCase(Locale.ROOT);
        return switch (mt) {
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                    -> "docx";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    -> "xlsx";
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                    -> "pptx";
            case "application/vnd.oasis.opendocument.text" -> "odt";
            case "application/vnd.oasis.opendocument.spreadsheet" -> "ods";
            case "application/vnd.oasis.opendocument.presentation" -> "odp";
            case "application/msword" -> "doc";
            case "application/vnd.ms-excel" -> "xls";
            case "application/vnd.ms-powerpoint" -> "ppt";
            case "application/rtf", "text/rtf" -> "rtf";
            case "text/csv" -> "csv";
            case "text/plain" -> "txt";
            default -> "docx";
        };
    }

    /** ONLYOFFICE's high-level grouping: {@code word}, {@code cell},
     *  {@code slide}. Drives which editor UI the user sees. */
    public static String docType(String fileExtension) {
        return switch (fileExtension.toLowerCase(Locale.ROOT)) {
            case "docx", "doc", "odt", "rtf", "txt" -> "word";
            case "xlsx", "xls", "ods", "csv" -> "cell";
            case "pptx", "ppt", "odp" -> "slide";
            default -> "word";
        };
    }
}
