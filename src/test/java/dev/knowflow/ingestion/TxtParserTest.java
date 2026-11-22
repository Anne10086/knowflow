package dev.knowflow.ingestion;

import dev.knowflow.api.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class TxtParserTest {
    private final TxtParser parser = new TxtParser();
    @Test void normalizesBomAndNewlines() {
        assertThat(parser.parse("课程.TXT", "\uFEFF第一行\r\n第二行\r第三行".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("第一行\n第二行\n第三行");
    }
    @ParameterizedTest @ValueSource(strings={"../secret.txt", "C:\\secret.txt", "name.txt:stream", "test.pdf", ".txt", "x\n.txt"})
    void rejectsUnsafeNames(String name) {
        assertThatThrownBy(() -> parser.parse(name, new byte[]{65})).isInstanceOf(ApiException.class);
    }
    @Test void rejectsInvalidUtf8BinaryEmptyAndOversizedContent() {
        for (byte[] bytes : new byte[][]{new byte[0], {(byte)0xC3, 0x28}, {65,0,66}, new byte[TxtParser.MAX_BYTES+1]})
            assertThatThrownBy(() -> parser.parse("test.txt", bytes)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> parser.parse("test.txt", " \n\t".getBytes(StandardCharsets.UTF_8))).isInstanceOf(ApiException.class);
    }
    @Test void chunksPreserveAllTextOffsetsAndUnicode() {
        var kb = UUID.randomUUID(); var file = UUID.randomUUID();
        String text = ("网络协议与分组交换😀\n").repeat(220);
        var chunks = parser.split(kb, file, text);
        assertThat(chunks.size()).isGreaterThan(2);
        StringBuilder restored = new StringBuilder(); int covered = 0;
        for (var chunk : chunks) {
            assertThat(chunk.kbId()).isEqualTo(kb); assertThat(chunk.documentId()).isEqualTo(file);
            assertThat(chunk.content()).isEqualTo(text.substring(chunk.startChar(), chunk.endChar()));
            assertThat(chunk.startChar()).isLessThanOrEqualTo(covered);
            assertThat(Character.isLowSurrogate(chunk.content().charAt(0))).isFalse();
            assertThat(Character.isHighSurrogate(chunk.content().charAt(chunk.content().length()-1))).isFalse();
            restored.append(text, covered, chunk.endChar()); covered = chunk.endChar();
        }
        assertThat(restored.toString()).isEqualTo(text);
        assertThat(chunks).extracting(c -> c.id()).doesNotHaveDuplicates();
    }
}
