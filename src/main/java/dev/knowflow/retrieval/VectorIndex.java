package dev.knowflow.retrieval;

import dev.knowflow.knowledge.KnowledgeStore.Chunk;
import java.util.*;

public interface VectorIndex {
    record Hit(UUID chunkId, double score) {}
    default void requireConfigured() {}
    void add(List<Chunk> chunks);
    void delete(List<UUID> ids);
    List<Hit> search(UUID knowledgeBase, String question);
}
