package dev.knowflow.config;

import dev.knowflow.api.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Prevent accidental reuse of an existing collection with a different embedding space. */
@Component
public class EmbeddingIdentity {
    private final JdbcClient db;
    public EmbeddingIdentity(JdbcClient db) { this.db = db; }
    public void bind(String collection, String model, int dimensions) {
        db.sql("INSERT INTO embedding_identity(collection_name,model_name,dimensions) VALUES (:c,:m,:d) ON CONFLICT DO NOTHING")
                .param("c", collection).param("m", model).param("d", dimensions).update();
        boolean matches = db.sql("SELECT count(*) FROM embedding_identity WHERE collection_name=:c AND model_name=:m AND dimensions=:d")
                .param("c", collection).param("m", model).param("d", dimensions).query(Long.class).single() == 1;
        if (!matches) throw ApiException.unavailable("EMBEDDING_MISMATCH", "向量集合已绑定其他模型或维度，请创建新集合并重新索引");
    }
}
