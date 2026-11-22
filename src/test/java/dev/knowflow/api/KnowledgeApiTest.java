package dev.knowflow.api;

import dev.knowflow.ingestion.IngestionService;
import dev.knowflow.knowledge.KnowledgeStore;
import dev.knowflow.rag.RagService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import java.util.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(KnowledgeController.class)
@Import({ApiErrors.class,RequestIds.class})
class KnowledgeApiTest {
    @Autowired MockMvc http;
    @MockitoBean KnowledgeStore store; @MockitoBean IngestionService ingestion; @MockitoBean RagService rag;
    @Test void invalidQuestionHasStructuredErrorAndRequestId() throws Exception {
        http.perform(post("/api/knowledge-bases/"+UUID.randomUUID()+"/ask").contentType("application/json").content("{\"question\":\" \"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.requestId").isNotEmpty()).andExpect(header().exists("X-Request-Id"));
        verifyNoInteractions(rag);
    }
    @Test void invalidUuidAndUnknownJsonFieldAreRejected() throws Exception {
        http.perform(get("/api/knowledge-bases/invalid/documents")).andExpect(status().isBadRequest());
        http.perform(post("/api/knowledge-bases").contentType("application/json").content("{\"name\":\"a\",\"unexpected\":true}"))
                .andExpect(status().isBadRequest());
    }
    @Test void unsupportedMimeNeverReachesIngestion() throws Exception {
        var file=new MockMultipartFile("file","bad.txt","text/html","<script>bad</script>".getBytes());
        http.perform(multipart("/api/knowledge-bases/"+UUID.randomUUID()+"/documents").file(file))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isNotEmpty());
        verifyNoInteractions(ingestion);
    }
    @Test void providerFailureIs503AndNeverReturnsAnswer() throws Exception {
        when(rag.ask(any(),anyString())).thenThrow(ApiException.unavailable("MODEL_FAILED","模型调用失败"));
        http.perform(post("/api/knowledge-bases/"+UUID.randomUUID()+"/ask").contentType("application/json").content("{\"question\":\"版本？\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("MODEL_FAILED"))
                .andExpect(jsonPath("$.answer").doesNotExist());
    }
    @Test void unknownKnowledgeBaseIs404() throws Exception {
        when(store.files(any())).thenThrow(ApiException.missing());
        http.perform(get("/api/knowledge-bases/"+UUID.randomUUID()+"/documents"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
