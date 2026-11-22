package dev.knowflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.knowflow.ingestion.IngestionService;
import dev.knowflow.knowledge.KnowledgeStore;
import dev.knowflow.rag.RagService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Genuine provider embeddings/answers across two JVMs and real Docker dependency restart. */
@EnabledIfEnvironmentVariable(named="KNOWFLOW_REAL_RESTART_PHASE",matches="seed|verify")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
class RealRestartPersistenceIT {
    record Snapshot(UUID kb,UUID document,UUID chunk) {}
    static final Path SNAPSHOT=Path.of("target/real-restart-probe.json");
    @Autowired KnowledgeStore store; @Autowired IngestionService ingestion; @Autowired RagService rag;
    @Autowired JdbcClient jdbc; @Autowired ObjectMapper json;
    @Test void preservesRealModelIndexAcrossRestart() throws Exception {
        boolean seed="seed".equals(System.getenv("KNOWFLOW_REAL_RESTART_PHASE"));
        Snapshot saved;
        if(seed) {
            assertThat(Files.exists(SNAPSHOT)).as("Unfinished probe must be verified before another seed").isFalse();
            UUID kb=store.create("REAL-RESTART-"+UUID.randomUUID(),"Fictional fixture restart acceptance").id();
            var document=ingestion.upload(kb,"bluewhale.txt",Files.readAllBytes(Path.of("tests/fixtures/bluewhale.txt")));
            saved=new Snapshot(kb,document.id(),store.chunks(kb,document.id()).getFirst().id());
            json.writeValue(SNAPSHOT.toFile(),saved);
        } else saved=json.readValue(SNAPSHOT.toFile(),Snapshot.class);
        assertThat(store.file(saved.kb(),saved.document()).status()).isEqualTo("READY");
        assertThat(store.readyChunk(saved.kb(),saved.chunk())).isPresent();
        var answer=rag.ask(saved.kb(),"蓝鲸测试计划的版本代号是什么？");
        assertThat(answer.grounded()).isTrue(); assertThat(answer.answer()).contains("BW-4827");
        assertThat(answer.sources()).isNotEmpty().allSatisfy(source->{
            assertThat(source.documentId()).isEqualTo(saved.document());
            assertThat(source.chunkId()).isEqualTo(saved.chunk());
            assertThat(source.excerpt()).contains("BW-4827");
        });
        json.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/real-restart-"+(seed?"seed":"verified")+".json").toFile(),answer);
        if(!seed) {
            ingestion.delete(saved.kb(),saved.document());
            jdbc.sql("DELETE FROM knowledge_bases WHERE id=:id").param("id",saved.kb()).update();
            Files.delete(SNAPSHOT);
        }
        System.out.println("REAL_RESTART_PROBE: "+(seed?"seed":"verify")+" passed with actual embedding, Milvus, PostgreSQL, generation and citations.");
    }
}