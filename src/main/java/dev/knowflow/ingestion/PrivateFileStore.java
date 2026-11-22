package dev.knowflow.ingestion;

import dev.knowflow.api.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.io.IOException;
import java.util.UUID;

@Component
public class PrivateFileStore {
    private final Path root;
    public PrivateFileStore(@Value("${knowflow.upload-dir}") String root) { this.root = Path.of(root).toAbsolutePath().normalize(); }
    private Path path(UUID id) { return root.resolve(id + ".txt"); }
    public void write(UUID id, byte[] bytes) {
        try { Files.createDirectories(root); Files.write(path(id), bytes, StandardOpenOption.CREATE_NEW); }
        catch (IOException ex) { throw ApiException.unavailable("STORAGE_FAILED", "无法保存原始文件，请检查私有存储目录的权限与剩余空间"); }
    }
    public void delete(UUID id) {
        try { Files.deleteIfExists(path(id)); }
        catch (IOException ex) { throw ApiException.unavailable("STORAGE_FAILED", "无法清理原始文件，请检查存储目录权限后重试"); }
    }
}
