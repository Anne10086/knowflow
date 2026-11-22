package dev.knowflow;

import dev.knowflow.ingestion.PrivateFileStore;
import dev.knowflow.ingestion.TxtParser;
import dev.knowflow.knowledge.KnowledgeStore;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.collection.DropCollectionParam;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Real HTTP/PostgreSQL/Milvus deletion after a cold start without any provider key.
 * Test embeddings seed an existing vector outside Spring; production AI beans stay untouched. */
@EnabledIfEnvironmentVariable(named="KNOWFLOW_INFRA_TESTS",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class NoKeyDeletionIT {
    static final String COLLECTION="knowflow_nokey_"+UUID.randomUUID().toString().replace("-","");
    static final String UPLOADS="target/no-key-uploads/"+COLLECTION;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("knowflow.ai.api-key",()->"");
        r.add("knowflow.milvus.collection",()->COLLECTION);
        r.add("knowflow.upload-dir",()->UPLOADS);
    }
    @Autowired KnowledgeStore store; @Autowired PrivateFileStore files; @Autowired TxtParser parser;
    @Autowired JdbcClient jdbc; @Autowired TransactionTemplate tx;
    @Autowired MilvusServiceClient client; @Autowired ConfigurableApplicationContext context;
    @Autowired org.springframework.boot.test.web.client.TestRestTemplate http;
    @Test void coldStartWithoutKeyDeletesExistingDocumentAndRealVectors() throws Exception {
        var kb=store.create("NO-KEY-DELETE-"+UUID.randomUUID(),"").id(); UUID file=UUID.randomUUID();
        var bytes=Files.readAllBytes(Path.of("tests/fixtures/bluewhale.txt"));
        var chunks=parser.split(kb,file,parser.parse("bluewhale.txt",bytes));
        var seeded=MilvusVectorStore.builder(client,new InfrastructureIT.TestModels().testEmbedding())
                .collectionName(COLLECTION).embeddingDimension(1024).indexType(IndexType.FLAT).metricType(MetricType.COSINE).initializeSchema(true).build();
        try {
            files.write(file,bytes);
            tx.executeWithoutResult(s->{store.insert(kb,file,"bluewhale.txt",bytes.length,"0".repeat(64),chunks);store.status(kb,file,"READY",null);});
            seeded.afterPropertiesSet();
            seeded.add(chunks.stream().map(c->Document.builder().id(c.id().toString()).text(c.content()).metadata(Map.of("kbId",kb.toString())).build()).toList());
            assertThat(seeded.similaritySearch(SearchRequest.builder().query(chunks.getFirst().content()).build())).isNotEmpty();
            assertThat(context.getBeanFactory().containsSingleton("embeddingModel")).isFalse();
            assertThat(context.getBeanFactory().containsSingleton("milvusVectorStore")).isFalse();
            var result=http.exchange("/api/documents/"+file,org.springframework.http.HttpMethod.DELETE,null,String.class);
            assertThat(result.getStatusCode().value()).isEqualTo(204);
            assertThat(store.files(kb)).isEmpty();
            assertThat(store.chunks(kb,file)).isEmpty();
            assertThat(Files.exists(Path.of(UPLOADS,file+".txt"))).isFalse();
            assertThat(seeded.similaritySearch(SearchRequest.builder().query(chunks.getFirst().content()).build())).isEmpty();
            assertThat(context.getBeanFactory().containsSingleton("embeddingModel")).isFalse();
            assertThat(context.getBeanFactory().containsSingleton("dashScopeApi")).isFalse();
            assertThat(context.getBeanFactory().containsSingleton("milvusVectorStore")).isFalse();
        } finally {
            client.dropCollection(DropCollectionParam.newBuilder().withCollectionName(COLLECTION).build());
            tx.executeWithoutResult(s->{store.delete(kb,file);jdbc.sql("DELETE FROM knowledge_bases WHERE id=:id").param("id",kb).update();
                jdbc.sql("DELETE FROM embedding_identity WHERE collection_name=:c").param("c",COLLECTION).update();});
            files.delete(file);
        }
    }
}
