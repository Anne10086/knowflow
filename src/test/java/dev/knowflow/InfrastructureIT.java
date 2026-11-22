package dev.knowflow;

import dev.knowflow.api.ApiException;
import dev.knowflow.config.EmbeddingIdentity;
import dev.knowflow.ingestion.IngestionService;
import dev.knowflow.knowledge.KnowledgeStore;
import dev.knowflow.rag.AnswerGenerator;
import dev.knowflow.rag.RagService;
import dev.knowflow.retrieval.VectorIndex;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.dao.DataIntegrityViolationException;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL + real Milvus. Synthetic embeddings and mocked generation; NOT model acceptance. */
@EnabledIfEnvironmentVariable(named="KNOWFLOW_INFRA_TESTS", matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
@Import(InfrastructureIT.TestModels.class)
class InfrastructureIT {
    static final String COLLECTION="knowflow_infra_"+UUID.randomUUID().toString().replace("-","");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("knowflow.ai.api-key",()->"test-only-no-provider-calls");
        r.add("knowflow.ai.embedding-model",()->"test-only-character-hash-v1");
        r.add("knowflow.milvus.collection",()->COLLECTION);
        r.add("knowflow.upload-dir",()->"target/infra-uploads/"+COLLECTION);
    }
    @TestConfiguration static class TestModels {
        @Bean @Primary AnswerGenerator testGenerator() { return mock(AnswerGenerator.class); }
        @Bean @Primary EmbeddingModel testEmbedding() {
            return new EmbeddingModel() {
                public float[] embed(Document doc) { return vector(doc.getText()); }
                public EmbeddingResponse call(EmbeddingRequest request) {
                    List<Embedding> embeddings=new ArrayList<>(); int i=0;
                    for(String s:request.getInstructions()) embeddings.add(new Embedding(vector(s),i++));
                    return new EmbeddingResponse(embeddings);
                }
                public int dimensions() { return 1024; }
                private float[] vector(String text) {
                    float[] values=new float[1024];
                    for(int c:text.codePoints().toArray()) if(!Character.isWhitespace(c)) values[Math.floorMod(Integer.hashCode(c)*31,1024)]++;
                    return values;
                }
            };
        }
    }
    @Autowired KnowledgeStore store; @Autowired IngestionService ingestion; @MockitoSpyBean VectorIndex index;
    @Autowired RagService rag; @Autowired AnswerGenerator generator; @Autowired JdbcClient jdbc;
    @Autowired EmbeddingIdentity identity;
    List<UUID> documents=new ArrayList<>(), bases=new ArrayList<>();
    @BeforeEach void resetModel() { reset(generator); reset(index); }
    UUID kb(String name) { UUID id=store.create("IT-"+name+UUID.randomUUID(),"").id(); bases.add(id); return id; }
    KnowledgeStore.FileInfo upload(UUID kb,String name) throws Exception {
        var file=ingestion.upload(kb,name,Files.readAllBytes(Path.of("tests/fixtures",name))); documents.add(file.id()); return file;
    }
    @AfterEach void cleanOwnData() {
        for(UUID id:documents) {try {ingestion.delete(store.kbForDocument(id),id);} catch(ApiException e) {if(e.status().value()!=404)throw e;}}
        for(UUID id:bases) jdbc.sql("DELETE FROM knowledge_bases WHERE id=:id").param("id",id).update();
    }
    @Test void realMilvusFiltersKnowledgeBasesBeforeReturningHits() throws Exception {
        UUID a=kb("A"),b=kb("B"); var fa=upload(a,"bluewhale.txt"); var fb=upload(b,"orangelamp.txt");
        var ca=store.chunks(a,fa.id()).getFirst(); var cb=store.chunks(b,fb.id()).getFirst();
        assertThat(index.search(a,ca.content())).extracting(VectorIndex.Hit::chunkId).contains(ca.id()).doesNotContain(cb.id());
        assertThat(index.search(b,ca.content())).extracting(VectorIndex.Hit::chunkId).doesNotContain(ca.id());
        assertThat(index.search(b,cb.content())).extracting(VectorIndex.Hit::chunkId).contains(cb.id()).doesNotContain(ca.id());
        assertThat(index.search(a,cb.content())).extracting(VectorIndex.Hit::chunkId).doesNotContain(cb.id());
    }
    @Test void emptyKnowledgeBaseRefusesWithoutGeneration() {
        var answer=rag.ask(kb("empty"),"蓝鲸计划代号？");
        assertThat(answer.insufficientEvidence()).isTrue(); assertThat(answer.sources()).isEmpty(); verifyNoInteractions(generator);
    }
    @Test void insufficientDraftCannotBecomeGroundedAnswer() throws Exception {
        UUID a=kb("unknown"); var f=upload(a,"bluewhale.txt");
        when(generator.generate(anyString(),anyList())).thenReturn(new AnswerGenerator.Draft(false,"",List.of()));
        assertThat(rag.ask(a,store.chunks(a,f.id()).getFirst().content()).insufficientEvidence()).isTrue();
        verify(generator).generate(anyString(),anyList());
    }
    @Test void deletionRemovesRealVectorsAndPreventsSources() throws Exception {
        UUID a=kb("delete"); var f=upload(a,"bluewhale.txt"); var chunk=store.chunks(a,f.id()).getFirst();
        ingestion.delete(a,f.id());
        assertThat(index.search(a,chunk.content())).isEmpty();
        assertThat(rag.ask(a,"蓝鲸版本？").sources()).isEmpty(); verifyNoInteractions(generator);
    }
    @Test void duplicateIsRejectedWithoutExtraRowsOrVectors() throws Exception {
        UUID a=kb("duplicate"); var f=upload(a,"bluewhale.txt");
        assertThatThrownBy(()->upload(a,"bluewhale.txt")).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("DUPLICATE_DOCUMENT"));
        assertThat(store.files(a)).hasSize(1); assertThat(store.file(a,f.id()).status()).isEqualTo("READY");
    }
    @Test void newDatabaseConnectionReadsPersistedDataAndRealIndex() throws Exception {
        UUID a=kb("persistence"); var f=upload(a,"bluewhale.txt");
        var ds=new DriverManagerDataSource(System.getenv().getOrDefault("DATABASE_URL","jdbc:postgresql://127.0.0.1:15432/knowflow"),
                System.getenv().getOrDefault("POSTGRES_USER","knowflow"),System.getenv("POSTGRES_PASSWORD"));
        var independent=new KnowledgeStore(JdbcClient.create(ds));
        assertThat(independent.file(a,f.id()).status()).isEqualTo("READY");
        var chunk=independent.chunks(a,f.id()).getFirst();
        assertThat(index.search(a,chunk.content())).extracting(VectorIndex.Hit::chunkId).contains(chunk.id());
    }
    @Test void interruptedIndexingIsFailedAndExcludedUntilExplicitRetry() throws Exception {
        UUID a=kb("recover"); var f=upload(a,"bluewhale.txt"); var c=store.chunks(a,f.id()).getFirst();
        store.status(a,f.id(),"INDEXING",null); ingestion.recoverInterrupted();
        assertThat(store.file(a,f.id()).errorCode()).isEqualTo("INTERRUPTED"); assertThat(store.readyChunk(a,c.id())).isEmpty();
        ingestion.reindex(a,f.id()); assertThat(store.file(a,f.id()).status()).isEqualTo("READY");
    }
    @Test void changedEmbeddingSpaceCannotReuseCollection() {
        String collection="identity-test-"+UUID.randomUUID(); identity.bind(collection,"model-a",1024);
        identity.bind(collection,"model-a",1024);
        assertThatThrownBy(()->identity.bind(collection,"model-b",1024)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->identity.bind(collection,"model-a",768)).isInstanceOf(ApiException.class);
        jdbc.sql("DELETE FROM embedding_identity WHERE collection_name=:c").param("c",collection).update();
    }
    @Test void failedVectorDeletionKeepsTombstoneAndCanBeRetried() throws Exception {
        UUID a=kb("delete-failure"); var f=upload(a,"bluewhale.txt"); var c=store.chunks(a,f.id()).getFirst();
        var ids=List.of(c.id()); doThrow(new IllegalStateException("simulated Milvus outage")).when(index).delete(ids);
        assertThatThrownBy(()->ingestion.delete(a,f.id())).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("DELETE_PENDING"));
        assertThat(store.file(a,f.id()).status()).isEqualTo("DELETING"); assertThat(store.readyChunk(a,c.id())).isEmpty();
        assertThat(rag.ask(a,"版本？").sources()).isEmpty();
        doCallRealMethod().when(index).delete(ids); ingestion.delete(a,f.id());
        assertThat(index.search(a,c.content())).isEmpty();
    }
    @Test void partialIndexFailureIsHiddenAndRetryReusesJournaledIds() throws Exception {
        UUID a=kb("index-failure");
        doAnswer(invocation->{invocation.callRealMethod(); throw new IllegalStateException("simulated crash after vector insert");}).when(index).add(anyList());
        assertThatThrownBy(()->ingestion.upload(a,"bluewhale.txt",Files.readAllBytes(Path.of("tests/fixtures/bluewhale.txt"))))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("INDEX_FAILED"));
        var f=store.files(a).getFirst(); documents.add(f.id()); var c=store.chunks(a,f.id()).getFirst();
        assertThat(f.status()).isEqualTo("FAILED"); assertThat(store.readyChunk(a,c.id())).isEmpty();
        assertThat(rag.ask(a,"版本？").sources()).isEmpty(); verifyNoInteractions(generator);
        doCallRealMethod().when(index).add(anyList()); ingestion.reindex(a,f.id());
        assertThat(index.search(a,c.content())).extracting(VectorIndex.Hit::chunkId).containsExactly(c.id());
    }
    @Test void databaseRejectsChunkLinkedToAnotherKnowledgeBase() throws Exception {
        UUID a=kb("constraint-a"),b=kb("constraint-b"); var f=upload(a,"bluewhale.txt");
        assertThatThrownBy(()->jdbc.sql("INSERT INTO chunks(id,document_id,kb_id,ordinal,content,start_char,end_char) VALUES (:id,:doc,:kb,99,'bad',0,3)")
                .param("id",UUID.randomUUID()).param("doc",f.id()).param("kb",b).update()).isInstanceOf(DataIntegrityViolationException.class);
    }
}
