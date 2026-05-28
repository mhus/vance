package de.mhus.vance.brain.office;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OfficeFileTypeTest {

    @Test
    void extension_recognisedOoxmlMimes() {
        assertThat(OfficeFileType.extension(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .isEqualTo("docx");
        assertThat(OfficeFileType.extension(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .isEqualTo("xlsx");
        assertThat(OfficeFileType.extension(
                "application/vnd.openxmlformats-officedocument.presentationml.presentation"))
                .isEqualTo("pptx");
    }

    @Test
    void extension_openDocumentMimes() {
        assertThat(OfficeFileType.extension("application/vnd.oasis.opendocument.text"))
                .isEqualTo("odt");
        assertThat(OfficeFileType.extension("application/vnd.oasis.opendocument.spreadsheet"))
                .isEqualTo("ods");
    }

    @Test
    void extension_caseInsensitive() {
        assertThat(OfficeFileType.extension(
                "Application/VND.openxmlformats-officedocument.wordprocessingml.DOCUMENT"))
                .isEqualTo("docx");
    }

    @Test
    void extension_unknownFallsBackToDocx() {
        assertThat(OfficeFileType.extension("application/octet-stream"))
                .isEqualTo("docx");
        assertThat(OfficeFileType.extension(null)).isEqualTo("docx");
    }

    @Test
    void docType_pickerByExtension() {
        assertThat(OfficeFileType.docType("docx")).isEqualTo("word");
        assertThat(OfficeFileType.docType("odt")).isEqualTo("word");
        assertThat(OfficeFileType.docType("xlsx")).isEqualTo("cell");
        assertThat(OfficeFileType.docType("csv")).isEqualTo("cell");
        assertThat(OfficeFileType.docType("pptx")).isEqualTo("slide");
        assertThat(OfficeFileType.docType("unknown")).isEqualTo("word");
    }
}
