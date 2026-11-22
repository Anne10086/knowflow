package dev.knowflow.rag;

import dev.knowflow.api.ApiException;
import dev.knowflow.knowledge.KnowledgeStore;
import dev.knowflow.knowledge.KnowledgeStore.Chunk;
import dev.knowflow.retrieval.VectorIndex;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class RagService {
    private final KnowledgeStore store;
    private final VectorIndex index;
    private final AnswerGenerator generator;
    public RagService(KnowledgeStore store, VectorIndex index, AnswerGenerator generator) { this.store = store; this.index = index; this.generator = generator; }
    public record Source(UUID documentId, String filename, UUID chunkId, Integer pageNumber, int chunkIndex, String excerpt, int startChar, int endChar) {}
    public record Answer(String answer, List<Source> sources, boolean grounded, boolean insufficientEvidence, long elapsedMs) {}
    public Answer ask(UUID kb, String question) {
        long start = System.nanoTime();
        store.requireKb(kb);
        if (store.files(kb).stream().noneMatch(d -> d.status().equals("READY"))) return refuse(start);
        List<VectorIndex.Hit> hits;
        try { hits = index.search(kb, question); }
        catch (RuntimeException ex) { throw ApiException.unavailable("RETRIEVAL_FAILED", "检索服务或向量化模型不可用，请检查服务状态后重试"); }
        Map<UUID, Chunk> candidates = new LinkedHashMap<>();
        for (var hit : hits) {
            // Revalidate against authoritative storage. Never trust content/metadata supplied by the index alone.
            store.readyChunk(kb, hit.chunkId()).ifPresent(c -> candidates.put(c.id(), c));
        }
        if (candidates.isEmpty()) return refuse(start);
        var draft = generator.generate(question, candidates.values().stream().map(c -> new AnswerGenerator.Evidence(c.id(), c.content())).toList());
        if (draft == null) throw ApiException.unavailable("INVALID_MODEL_EVIDENCE", "模型未返回有效结果，请重试");
        if (!draft.sufficient()) return refuse(start);
        if (draft.answer() == null || draft.answer().isBlank() || draft.answer().length() > 8000 || draft.citations() == null || draft.citations().isEmpty() || draft.citations().size() > 10)
            throw ApiException.unavailable("INVALID_MODEL_EVIDENCE", "模型回答未附有效证据，已停止返回该答案");
        List<Source> sources = new ArrayList<>();
        for (var citation : draft.citations()) {
            if (citation == null) throw ApiException.unavailable("INVALID_MODEL_EVIDENCE", "模型引用格式无效，已停止返回该答案");
            var chunk = candidates.get(citation.chunkId());
            if (chunk == null || citation.quote() == null || citation.quote().strip().length() < 4 || !chunk.content().contains(citation.quote()))
                throw ApiException.unavailable("INVALID_MODEL_EVIDENCE", "模型引用与实际原文不符，已停止返回该答案");
            if (store.readyChunk(kb, chunk.id()).isEmpty()) return refuse(start);
            var file = store.file(kb, chunk.documentId());
            int offset = chunk.startChar() + chunk.content().indexOf(citation.quote());
            sources.add(new Source(file.id(), file.filename(), chunk.id(), null, chunk.ordinal(), citation.quote(), offset, offset + citation.quote().length()));
        }
        return new Answer(draft.answer(), List.copyOf(sources), true, false, elapsed(start));
    }
    private static Answer refuse(long start) { return new Answer("当前知识库没有足够依据回答这个问题。", List.of(), false, true, elapsed(start)); }
    private static long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }
}
