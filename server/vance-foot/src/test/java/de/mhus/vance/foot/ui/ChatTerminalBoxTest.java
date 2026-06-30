package de.mhus.vance.foot.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ChatTerminalBoxTest {

    @Test
    void buildBox_singleLine_hasThreeLinesWithAlignedBorders() {
        List<String> box = ChatTerminal.buildBox(List.of("SANDBOX DISABLED"), 200);

        assertThat(box).hasSize(3);
        int width = box.get(0).length();
        assertThat(box).allSatisfy(line -> assertThat(line.length()).isEqualTo(width));
        assertThat(box.get(0)).startsWith("┌").endsWith("┐");
        assertThat(box.get(1)).startsWith("│").endsWith("│").contains("SANDBOX DISABLED");
        assertThat(box.get(2)).startsWith("└").endsWith("┘");
    }

    @Test
    void buildBox_innerWidthFollowsLongestLine() {
        List<String> box = ChatTerminal.buildBox(List.of("short", "a longer line"), 200);

        // "a longer line" = 13 chars, + 1 space each side + 2 borders = 17
        assertThat(box.get(0).length()).isEqualTo(17);
        assertThat(box).hasSize(4); // top + 2 content + bottom
        assertThat(box).allSatisfy(line -> assertThat(line.length()).isEqualTo(17));
    }

    @Test
    void buildBox_capsInnerWidthAtMax() {
        List<String> box = ChatTerminal.buildBox(List.of("x".repeat(100)), 10);

        // capped inner 10 → "┌" + 12 dashes + "┐" = 14
        assertThat(box.get(0).length()).isEqualTo(14);
    }
}
