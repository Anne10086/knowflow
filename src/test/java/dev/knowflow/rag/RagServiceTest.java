package dev.knowflow.rag;

import dev.knowflow.api.ApiException;
import dev.knowflow.knowledge.KnowledgeStore;
import dev.knowflow.knowledge.KnowledgeStore.*;
import dev.knowflow.retrieval.VectorIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RagServiceTest {
    KnowledgeStore store = mock(KnowledgeStore.class);
    VectorIndex index = mock(VectorIndex.class);
    AnswerGenerator generator = mock(AnswerGenerator.class);
    RagService rag = new RagService(store, index, generator);
    UUID kb = UUID.randomUUID(), file = UUID.randomUUID(), chunkId = UUID.randomUUID();
    Chunk chunk = new Chunk(chunkId, file, kb, 0, "版本代号：BW-4827\n归档星期：星期三", 100, 126);
    @BeforeEach void readyDocument() {
        var document = new FileInfo(file,kb,"bluewhale.txt","hash","READY",null,OffsetDateTime.now());
        when(store.files(kb)).thenReturn(List.of(document)); when(store.file(kb,file)).thenReturn(document);
        when(store.readyChunk(kb,chunkId)).thenReturn(Optional.of(chunk));
        when(index.search(eq(kb),anyString())).thenReturn(List.of(new VectorIndex.Hit(chunkId,0.9)));
    }
    @Test void emptyKnowledgeBaseNeverCallsModels() {
        when(store.files(kb)).thenReturn(List.of());
        assertRefusal(rag.ask(kb,"版本？")); verifyNoInteractions(index,generator);
    }
    @Test void noRetrievedEvidenceNeverCallsGenerator() {
        when(index.search(eq(kb),anyString())).thenReturn(List.of());
        assertRefusal(rag.ask(kb,"预算？")); verifyNoInteractions(generator);
    }
    @Test void foreignOrDeletedChunkNeverReachesModel() {
        when(store.readyChunk(kb,chunkId)).thenReturn(Optional.empty());
        assertRefusal(rag.ask(kb,"版本？")); verifyNoInteractions(generator);
    }
    @Test void modelInsufficientEvidenceIsRefusedWithoutCitations() {
        when(generator.generate(anyString(),anyList())).thenReturn(new AnswerGenerator.Draft(false,"invented",List.of()));
        assertRefusal(rag.ask(kb,"预算？"));
    }
    @Test void citationsComeFromPersistedSourceWithExactOffsets() {
        when(generator.generate(anyString(),anyList())).thenReturn(draft(chunkId,"版本代号：BW-4827"));
        var answer = rag.ask(kb,"版本？");
        assertThat(answer.grounded()).isTrue(); assertThat(answer.insufficientEvidence()).isFalse();
        var source = answer.sources().getFirst();
        assertThat(source.filename()).isEqualTo("bluewhale.txt"); assertThat(source.pageNumber()).isNull();
        assertThat(source.startChar()).isEqualTo(100);
        assertThat(source.endChar()).isEqualTo(100+source.excerpt().length());
        verify(generator).generate("版本？",List.of(new AnswerGenerator.Evidence(chunkId,chunk.content())));
    }
    @Test void rejectsFabricatedQuoteAndForeignCitationId() {
        for (var invalid : List.of(draft(chunkId,"项目预算：123万元"),draft(UUID.randomUUID(),"版本代号：BW-4827"))) {
            when(generator.generate(anyString(),anyList())).thenReturn(invalid);
            assertThatThrownBy(() -> rag.ask(kb,"版本？")).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.code()).isEqualTo("INVALID_MODEL_EVIDENCE"));
        }
    }
    @Test void deletionDuringGenerationDiscardsAnswer() {
        when(generator.generate(anyString(),anyList())).thenAnswer(invocation -> {
            when(store.readyChunk(kb,chunkId)).thenReturn(Optional.empty()); return draft(chunkId,"版本代号：BW-4827");
        });
        assertRefusal(rag.ask(kb,"版本？"));
    }
    @Test void retrievalFailureIsAnErrorNotAnAnswer() {
        when(index.search(eq(kb),anyString())).thenThrow(new RuntimeException("sensitive provider payload"));
        assertThatThrownBy(() -> rag.ask(kb,"版本？")).isInstanceOfSatisfying(ApiException.class,
                e -> {assertThat(e.code()).isEqualTo("RETRIEVAL_FAILED"); assertThat(e.getMessage()).doesNotContain("sensitive");});
        verifyNoInteractions(generator);
    }
    private AnswerGenerator.Draft draft(UUID id,String quote) {
        return new AnswerGenerator.Draft(true,"BW-4827",List.of(new AnswerGenerator.Citation(id,quote)));
    }
    private static void assertRefusal(RagService.Answer answer) {
        assertThat(answer.grounded()).isFalse(); assertThat(answer.insufficientEvidence()).isTrue(); assertThat(answer.sources()).isEmpty();
        assertThat(answer.answer()).contains("没有足够依据");
    }
}
