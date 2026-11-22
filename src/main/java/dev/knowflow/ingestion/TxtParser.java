package dev.knowflow.ingestion;

import dev.knowflow.api.ApiException;
import dev.knowflow.knowledge.KnowledgeStore.Chunk;
import org.springframework.stereotype.Component;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

@Component
public class TxtParser {
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    public String parse(String filename, byte[] bytes) {
        if (filename == null || filename.length() > 180 || filename.contains("/") || filename.contains("\\")
                || filename.contains(":") || filename.equalsIgnoreCase(".txt")
                || filename.chars().anyMatch(Character::isISOControl) || !filename.toLowerCase(Locale.ROOT).endsWith(".txt"))
            throw ApiException.bad("M1 仅接收文件名合法的 UTF-8 TXT 文件");
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw ApiException.bad("文件为空或超过 2 MiB");
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            text = text.replace("\r\n", "\n").replace('\r', '\n');
            if (text.isBlank() || text.length() > 200_000 || text.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t'))
                throw ApiException.bad("TXT 必须为非空文本，不能包含二进制控制字符，且不超过 20 万字符");
            return text;
        } catch (CharacterCodingException ex) { throw ApiException.bad("文件不是有效的 UTF-8 编码，请转换为 UTF-8 后重试"); }
    }
    public List<Chunk> split(UUID kb, UUID file, String text) {
        List<Chunk> result = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + 800, text.length());
            if (end < text.length() && Character.isLowSurrogate(text.charAt(end))) end--;
            if (end < text.length()) {
                int newline = text.lastIndexOf('\n', end - 1);
                if (newline > start + 500) end = newline + 1;
            }
            String content = text.substring(start, end);
            if (!content.isBlank()) result.add(new Chunk(UUID.randomUUID(), file, kb, result.size(), content, start, end));
            if (end == text.length()) break;
            start = end - 120;
            if (Character.isLowSurrogate(text.charAt(start))) start--;
        }
        return List.copyOf(result);
    }
}
