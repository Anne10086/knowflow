package dev.knowflow.retrieval;

import dev.knowflow.knowledge.KnowledgeStore.Chunk;
import dev.knowflow.api.ApiException;
import dev.knowflow.knowledge.KnowledgeStore;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class MilvusIndex implements VectorIndex {
    private final ObjectProvider<MilvusVectorStore> provider;
    private final int topK;
    private final double minimumScore;
    private final KnowledgeStore store;
    private final String apiKey;
    public MilvusIndex(ObjectProvider<MilvusVectorStore> provider,
            @Value("${knowflow.retrieval.top-k}") int topK, @Value("${knowflow.retrieval.minimum-score}") double minimumScore,
            KnowledgeStore store, @Value("${knowflow.ai.api-key}") String apiKey) {
        this.provider = provider; this.topK = topK; this.minimumScore = minimumScore;
        this.store = store; this.apiKey = apiKey;
    }
    @Override public void requireConfigured() {
        if (apiKey.isBlank()) throw ApiException.unavailable("MODEL_NOT_CONFIGURED", "未配置 DASHSCOPE_API_KEY，请在本机设置密钥后重启后端");
    }
    public void add(List<Chunk> chunks) {
        requireConfigured();
        // DashScope caps embedding input batches. Do not let default token batching exceed it.
        for (int start = 0; start < chunks.size(); start += 10) {
            var batch = chunks.subList(start, Math.min(start + 10, chunks.size())).stream().map(c ->
                    Document.builder().id(c.id().toString()).text(c.content()).metadata(Map.of(
                            "kbId", c.kbId().toString(), "documentId", c.documentId().toString(),
                            "chunkId", c.id().toString(), "chunkIndex", c.ordinal(),
                            "filename", store.file(c.kbId(), c.documentId()).filename())).build()).toList();
            provider.getObject().add(batch);
        }
    }
    public void delete(List<UUID> ids) {
        if (!ids.isEmpty()) provider.getObject().delete(ids.stream().map(UUID::toString).toList());
    }
    public List<Hit> search(UUID kb, String question) {
        requireConfigured();
        var filter = new FilterExpressionBuilder();
        var results = provider.getObject().similaritySearch(SearchRequest.builder().query(question).topK(topK)
                .similarityThreshold(minimumScore).filterExpression(filter.eq("kbId", kb.toString()).build()).build());
        return results.stream().filter(d -> d.getScore() != null && Double.isFinite(d.getScore()) && d.getScore() >= minimumScore)
                .map(d -> new Hit(UUID.fromString(d.getId()), d.getScore())).toList();
    }
}
