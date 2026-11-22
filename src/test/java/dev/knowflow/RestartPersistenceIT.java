package dev.knowflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.knowflow.ingestion.IngestionService;
import dev.knowflow.knowledge.KnowledgeStore;
import dev.knowflow.retrieval.VectorIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.nio.file.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** Two separate JVM runs, with Docker service restart between seed and verify. Synthetic model only. */
@EnabledIfEnvironmentVariable(named="KNOWFLOW_RESTART_PHASE",matches="seed|verify")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
@Import(InfrastructureIT.TestModels.class)
class RestartPersistenceIT {
    static final Path SNAPSHOT=Path.of("target/restart-probe.json");
    static final String COLLECTION="knowflow_restart_probe_v1";
    record Snapshot(UUID kb,UUID document,UUID chunk,String content) {}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("knowflow.ai.api-key",()->"test-only-no-provider-calls");
        r.add("knowflow.ai.embedding-model",()->"test-only-character-hash-v1");
        r.add("knowflow.milvus.collection",()->COLLECTION);
        r.add("knowflow.upload-dir",()->"target/restart-uploads");
    }
    @Autowired KnowledgeStore store; @Autowired IngestionService ingestion; @Autowired VectorIndex index;
    @Autowired JdbcClient jdbc; @Autowired ObjectMapper json;
    @Test void persistsAcrossSeparateJvmAndDependencyRestart() throws Exception {
        if("seed".equals(System.getenv("KNOWFLOW_RESTART_PHASE"))) {
            assertThat(Files.exists(SNAPSHOT)).as("Do not overwrite an unfinished restart probe; run verify first").isFalse();
            var kb=store.create("RESTART-"+UUID.randomUUID(),"Synthetic embedding restart probe").id();
            var file=ingestion.upload(kb,"bluewhale.txt",Files.readAllBytes(Path.of("tests/fixtures/bluewhale.txt")));
            var chunk=store.chunks(kb,file.id()).getFirst();
            assertThat(index.search(kb,chunk.content())).extracting(VectorIndex.Hit::chunkId).contains(chunk.id());
            json.writeValue(SNAPSHOT.toFile(),new Snapshot(kb,file.id(),chunk.id(),chunk.content()));
            System.out.println("RESTART_PROBE: seed persisted in real PostgreSQL and Milvus; JVM may now exit.");
        } else {
            var saved=json.readValue(SNAPSHOT.toFile(),Snapshot.class);
            assertThat(store.file(saved.kb(),saved.document()).status()).isEqualTo("READY");
            assertThat(store.readyChunk(saved.kb(),saved.chunk())).isPresent();
            assertThat(index.search(saved.kb(),saved.content())).extracting(VectorIndex.Hit::chunkId).contains(saved.chunk());
            assertThat(Files.exists(Path.of("target/restart-uploads",saved.document()+".txt"))).isTrue();
            ingestion.delete(saved.kb(),saved.document());
            jdbc.sql("DELETE FROM knowledge_bases WHERE id=:id").param("id",saved.kb()).update();
            Files.delete(SNAPSHOT);
            System.out.println("RESTART_PROBE: verified metadata, original and real Milvus retrieval after JVM and dependency restart.");
        }
    }
}
