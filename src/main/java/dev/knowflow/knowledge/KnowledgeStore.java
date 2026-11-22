package dev.knowflow.knowledge;

import dev.knowflow.api.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import java.time.OffsetDateTime;
import java.util.*;

@Repository
public class KnowledgeStore {
    private final JdbcClient db;
    public KnowledgeStore(JdbcClient db) { this.db = db; }
    public record KnowledgeBase(UUID id, String name, String description, OffsetDateTime createdAt) {}
    public record FileInfo(UUID id, UUID kbId, String filename, String sha256, String status, String errorCode, OffsetDateTime createdAt) {}
    public record Chunk(UUID id, UUID documentId, UUID kbId, int ordinal, String content, int startChar, int endChar) {}
    public KnowledgeBase create(String name, String description) {
        return db.sql("INSERT INTO knowledge_bases(id,name,description) VALUES (:id,:name,:description) RETURNING *")
                .param("id", UUID.randomUUID()).param("name", name.strip()).param("description", description == null ? "" : description.strip()).query(KnowledgeBase.class).single();
    }
    public List<KnowledgeBase> list() { return db.sql("SELECT * FROM knowledge_bases ORDER BY created_at,id").query(KnowledgeBase.class).list(); }
    public void requireKb(UUID kb) {
        if (db.sql("SELECT count(*) FROM knowledge_bases WHERE id=:id").param("id", kb).query(Long.class).single() == 0) throw ApiException.missing();
    }
    public FileInfo file(UUID kb, UUID id) {
        return db.sql("SELECT id,kb_id,filename,sha256,status,error_code,created_at FROM documents WHERE kb_id=:kb AND id=:id")
                .param("kb", kb).param("id", id).query(FileInfo.class).optional().orElseThrow(ApiException::missing);
    }
    public List<FileInfo> files(UUID kb) {
        requireKb(kb);
        return db.sql("SELECT id,kb_id,filename,sha256,status,error_code,created_at FROM documents WHERE kb_id=:kb ORDER BY created_at,id")
                .param("kb", kb).query(FileInfo.class).list();
    }
    public void insert(UUID kb, UUID file, String name, int bytes, String hash, List<Chunk> chunks) {
        requireKb(kb);
        db.sql("INSERT INTO documents(id,kb_id,filename,stored_filename,bytes,sha256,status) VALUES (:id,:kb,:name,:stored,:bytes,:hash,'UPLOADED')")
                .param("id", file).param("kb", kb).param("name", name).param("stored", file + ".txt").param("bytes", bytes).param("hash", hash).update();
        for (Chunk c : chunks) db.sql("INSERT INTO chunks(id,document_id,kb_id,ordinal,content,start_char,end_char) VALUES (:id,:doc,:kb,:ord,:text,:start,:end)")
                .param("id", c.id()).param("doc", file).param("kb", kb).param("ord", c.ordinal()).param("text", c.content()).param("start", c.startChar()).param("end", c.endChar()).update();
    }
    public List<Chunk> chunks(UUID kb, UUID file) {
        return db.sql("SELECT * FROM chunks WHERE kb_id=:kb AND document_id=:id ORDER BY ordinal")
                .param("kb", kb).param("id", file).query(Chunk.class).list();
    }
    public Optional<Chunk> readyChunk(UUID kb, UUID chunk) {
        return db.sql("SELECT c.* FROM chunks c JOIN documents d ON d.id=c.document_id AND d.kb_id=c.kb_id WHERE c.kb_id=:kb AND c.id=:id AND d.status='READY'")
                .param("kb", kb).param("id", chunk).query(Chunk.class).optional();
    }
    public void lockFile(UUID kb, UUID file) {
        db.sql("SELECT id FROM documents WHERE kb_id=:kb AND id=:id FOR UPDATE").param("kb", kb).param("id", file)
                .query(UUID.class).optional().orElseThrow(ApiException::missing);
    }
    public void status(UUID kb, UUID file, String status, String code) {
        db.sql("UPDATE documents SET status=:status,error_code=:code WHERE kb_id=:kb AND id=:id")
                .param("status", status).param("code", code, java.sql.Types.VARCHAR).param("kb", kb).param("id", file).update();
    }
    public void failProcessing(UUID kb, UUID file) {
        db.sql("UPDATE documents SET status='FAILED',error_code='INDEX_FAILED' WHERE kb_id=:kb AND id=:id AND status='INDEXING'")
                .param("kb", kb).param("id", file).update();
    }
    public void delete(UUID kb, UUID file) { db.sql("DELETE FROM documents WHERE kb_id=:kb AND id=:id").param("kb", kb).param("id", file).update(); }
    public UUID kbForDocument(UUID document) {
        return db.sql("SELECT kb_id FROM documents WHERE id=:id").param("id", document).query(UUID.class).optional().orElseThrow(ApiException::missing);
    }
    public void recoverInterrupted() {
        db.sql("UPDATE documents SET status='FAILED',error_code='INTERRUPTED' WHERE status='INDEXING'").update();
    }
}
