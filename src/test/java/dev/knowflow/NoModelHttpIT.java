package dev.knowflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.knowflow.knowledge.KnowledgeStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.LinkedMultiValueMap;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Real HTTP server + real database/Milvus health, deliberately missing model key. */
@EnabledIfEnvironmentVariable(named="KNOWFLOW_INFRA_TESTS",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="knowflow.ai.api-key=")
class NoModelHttpIT {
    @Autowired TestRestTemplate http; @Autowired KnowledgeStore store; @Autowired JdbcClient jdbc; @Autowired ObjectMapper json;
    UUID kb;
    @BeforeEach void createBase() { kb=store.create("HTTP-"+UUID.randomUUID(),"").id(); }
    @AfterEach void clean() { jdbc.sql("DELETE FROM knowledge_bases WHERE id=:id").param("id",kb).update(); }
    @Test void actualHealthClearlyReportsMissingKeyAndHealthyDatabases() throws Exception {
        var result=http.getForEntity("/api/health",JsonNode.class);
        assertThat(result.getStatusCode().value()).isEqualTo(503);
        assertThat(result.getBody().path("model").asText()).isEqualTo("NOT_CONFIGURED");
        assertThat(result.getBody().path("database").asText()).isEqualTo("UP");
        assertThat(result.getBody().path("milvus").asText()).isEqualTo("UP");
        Files.createDirectories(Path.of("docs/evidence")); json.writerWithDefaultPrettyPrinter().writeValue(Path.of("docs/evidence/health-without-key.json").toFile(),result.getBody());
    }
    @Test void actualUploadRejectsMissingKeyWithoutCreatingFakeDocument() throws Exception {
        var content=Files.readAllBytes(Path.of("tests/fixtures/bluewhale.txt"));
        var resource=new ByteArrayResource(content) {@Override public String getFilename(){return "bluewhale.txt";}};
        var parts=new LinkedMultiValueMap<String,Object>(); parts.add("file",resource);
        var result=http.postForEntity("/api/knowledge-bases/"+kb+"/documents",parts,JsonNode.class);
        assertThat(result.getStatusCode().value()).isEqualTo(503);
        assertThat(result.getBody().path("code").asText()).isEqualTo("MODEL_NOT_CONFIGURED");
        assertThat(result.getBody().path("requestId").asText()).isNotBlank();
        assertThat(store.files(kb)).isEmpty();
        json.writerWithDefaultPrettyPrinter().writeValue(Path.of("docs/evidence/upload-without-key.json").toFile(),result.getBody());
    }
    @Test void actualEmptyKnowledgeBaseReturnsRefusalWithNoSources() {
        var result=http.postForEntity("/api/knowledge-bases/"+kb+"/ask",Map.of("question","蓝鲸版本代号是什么？"),JsonNode.class);
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody().path("grounded").asBoolean()).isFalse();
        assertThat(result.getBody().path("insufficientEvidence").asBoolean()).isTrue();
        assertThat(result.getBody().path("sources").isEmpty()).isTrue();
    }
    @Test void actualWrongOuterContentTypeIs415() {
        var headers=new HttpHeaders(); headers.setContentType(MediaType.TEXT_PLAIN);
        var question=http.postForEntity("/api/knowledge-bases/"+kb+"/ask",new HttpEntity<>("question",headers),JsonNode.class);
        assertThat(question.getStatusCode().value()).isEqualTo(415);
        assertThat(question.getBody().path("code").asText()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
        assertThat(question.getBody().path("requestId").asText()).isNotBlank();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var upload=http.postForEntity("/api/knowledge-bases/"+kb+"/documents",new HttpEntity<>("{}",headers),JsonNode.class);
        assertThat(upload.getStatusCode().value()).isEqualTo(415);
        assertThat(upload.getBody().path("code").asText()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
        assertThat(store.files(kb)).isEmpty();
    }
    @Test void actualOversizedMultipartIs413WithoutCreatingDocument() {
        var resource=new ByteArrayResource(new byte[2*1024*1024+1]) {@Override public String getFilename(){return "large.txt";}};
        var parts=new LinkedMultiValueMap<String,Object>(); parts.add("file",resource);
        var result=http.postForEntity("/api/knowledge-bases/"+kb+"/documents",parts,JsonNode.class);
        assertThat(result.getStatusCode().value()).isEqualTo(413);
        assertThat(result.getBody().path("code").asText()).isEqualTo("FILE_TOO_LARGE");
        assertThat(store.files(kb)).isEmpty();
    }
    @Test void actualKnowledgeBaseCreateIsPersistedAndListed() {
        var result=http.postForEntity("/api/knowledge-bases",Map.of("name","HTTP-created-"+kb),JsonNode.class);
        assertThat(result.getStatusCode().value()).isEqualTo(201);
        UUID created=UUID.fromString(result.getBody().path("knowledgeBase").path("id").asText());
        try {
            store.requireKb(created);
            var list=http.getForEntity("/api/knowledge-bases",JsonNode.class);
            assertThat(list.getStatusCode().value()).isEqualTo(200);
            assertThat(list.getBody().path("knowledgeBases").toString()).contains(created.toString());
        } finally {jdbc.sql("DELETE FROM knowledge_bases WHERE id=:id").param("id",created).update();}
    }
}
