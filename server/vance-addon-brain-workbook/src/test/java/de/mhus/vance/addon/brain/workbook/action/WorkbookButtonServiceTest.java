package de.mhus.vance.addon.brain.workbook.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.toolpack.ToolException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link WorkbookButtonService} dispatcher: routing by
 * fence {@code type:} (with the parser's {@code script} default), the
 * fail-closed unknown type, and the fail-fast duplicate registration.
 */
class WorkbookButtonServiceTest {

    private static final class StubHandler implements ButtonActionHandler {
        private final String type;
        private final ButtonActionResult result;

        StubHandler(String type) {
            this.type = type;
            this.result = new ButtonActionResult("ran:" + type);
        }

        @Override
        public String type() {
            return type;
        }

        @Override
        public ButtonActionResult run(ButtonActionContext ctx) {
            return result;
        }
    }

    @Test
    void run_routesByType() {
        WorkbookButtonService service =
                new WorkbookButtonService(List.of(new StubHandler("form-resolve"), new StubHandler("script")));
        ButtonActionResult r = service.run(ctx(Map.of("type", "form-resolve")));
        assertThat(r.message()).isEqualTo("ran:form-resolve");
    }

    @Test
    void run_missingType_defaultsToScript() {
        WorkbookButtonService service =
                new WorkbookButtonService(List.of(new StubHandler("script"), new StubHandler("form-reset")));
        ButtonActionResult r = service.run(ctx(Map.of())); // no type key at all
        assertThat(r.message()).isEqualTo("ran:script");
    }

    @Test
    void run_unknownType_failsClosed() {
        WorkbookButtonService service = new WorkbookButtonService(List.of(new StubHandler("script")));
        assertThatThrownBy(() -> service.run(ctx(Map.of("type", "teleport"))))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("unknown button type 'teleport'")
                .hasMessageContaining("[script]");
    }

    @Test
    void constructor_duplicateType_failsFast() {
        assertThatThrownBy(() -> new WorkbookButtonService(
                        List.of(new StubHandler("form-resolve"), new StubHandler("form-resolve"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate button action type 'form-resolve'");
    }

    private static ButtonActionContext ctx(Map<String, Object> buttonConfig) {
        return new ButtonActionContext("t", "p", "user", "apps/g/page.workpage.md", buttonConfig);
    }
}
