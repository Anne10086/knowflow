package dev.knowflow.rag;

import java.util.*;

public interface AnswerGenerator {
    record Evidence(UUID chunkId, String text) {}
    record Citation(UUID chunkId, String quote) {}
    record Draft(boolean sufficient, String answer, List<Citation> citations) {}
    Draft generate(String question, List<Evidence> evidence);
}
