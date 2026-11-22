package dev.knowflow.ingestion;

import dev.knowflow.api.ApiException;
import dev.knowflow.api.ModelFailures;
import dev.knowflow.knowledge.KnowledgeStore;
import dev.knowflow.knowledge.KnowledgeStore.*;
import dev.knowflow.retrieval.VectorIndex;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import java.security.*;
import java.util.*;

@Service
public class IngestionService {
    private final KnowledgeStore store;
    private final TxtParser parser;
    private final VectorIndex index;
    private final TransactionTemplate tx;
    private final PrivateFileStore files;
    public IngestionService(KnowledgeStore store, TxtParser parser, VectorIndex index, TransactionTemplate tx, PrivateFileStore files) {
        this.store = store; this.parser = parser; this.index = index; this.tx = tx; this.files = files;
    }
    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterrupted() { store.recoverInterrupted(); }
    public FileInfo upload(UUID kb, String filename, byte[] bytes) {
        store.requireKb(kb);
        String text = parser.parse(filename, bytes);
        UUID file = UUID.randomUUID();
        List<Chunk> chunks = parser.split(kb, file, text);
        index.requireConfigured();
        // Persist the complete vector ID journal before contacting external systems.
        files.write(file, bytes);
        try { tx.executeWithoutResult(s -> store.insert(kb, file, filename, bytes.length, hash(bytes), chunks)); }
        catch (DuplicateKeyException ex) { files.delete(file); throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_DOCUMENT", "该知识库已包含相同内容的文件，请查看现有文件状态或重试索引"); }
        catch (RuntimeException ex) { files.delete(file); throw ex; }
        return reindex(kb, file);
    }
    public FileInfo reindex(UUID kb, UUID file) {
        index.requireConfigured();
        tx.executeWithoutResult(s -> {
            store.lockFile(kb, file);
            String status = store.file(kb, file).status();
            if (status.equals("DELETING") || status.equals("INDEXING"))
                throw new ApiException(HttpStatus.CONFLICT, "DOCUMENT_BUSY", "文件正在处理或删除，请稍后重试");
            store.status(kb, file, "INDEXING", null);
        });
        try {
            return tx.execute(s -> {
                store.lockFile(kb, file);
                if (store.file(kb, file).status().equals("DELETING")) throw ApiException.bad("文件正在删除，请重试删除操作");
                List<Chunk> chunks = store.chunks(kb, file);
                index.delete(chunks.stream().map(Chunk::id).toList());
                index.add(chunks);
                store.status(kb, file, "READY", null);
                return store.file(kb, file);
            });
        } catch (ApiException ex) {
            tx.executeWithoutResult(s -> store.failProcessing(kb, file));
            throw ex;
        } catch (RuntimeException ex) {
            tx.executeWithoutResult(s -> store.failProcessing(kb, file));
            // The persisted file ID is obtained from GET /files; provider errors never become answers.
            throw ModelFailures.safe(ex,"INDEX_FAILED", "索引失败，原始文件和分块已保存，可在文件列表查看并重试索引");
        }
    }
    public void delete(UUID kb, UUID file) {
        tx.executeWithoutResult(s -> { store.lockFile(kb, file); store.status(kb, file, "DELETING", null); });
        try {
            tx.executeWithoutResult(s -> {
                store.lockFile(kb, file);
                index.delete(store.chunks(kb, file).stream().map(Chunk::id).toList());
                files.delete(file);
                store.delete(kb, file);
            });
        } catch (RuntimeException ex) {
            throw ApiException.unavailable("DELETE_PENDING", "文件已停止参与检索，向量清理尚未完成，请重试删除");
        }
    }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
