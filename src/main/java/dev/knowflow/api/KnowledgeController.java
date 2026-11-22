package dev.knowflow.api;

import dev.knowflow.ingestion.IngestionService;
import dev.knowflow.knowledge.KnowledgeStore;
import dev.knowflow.rag.RagService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/api")
public class KnowledgeController {
    private final KnowledgeStore store;
    private final IngestionService ingestion;
    private final RagService rag;
    public KnowledgeController(KnowledgeStore store, IngestionService ingestion, RagService rag) { this.store = store; this.ingestion = ingestion; this.rag = rag; }
    public record CreateKnowledgeBase(@NotBlank @Size(max=120) String name, @Size(max=1000) String description) {}
    public record Question(@NotBlank @Size(max=2000) String question) {}
    @PostMapping("/knowledge-bases") @ResponseStatus(HttpStatus.CREATED)
    Map<String,Object> create(@Valid @RequestBody CreateKnowledgeBase body, HttpServletRequest request) {
        return Map.of("knowledgeBase", store.create(body.name(), body.description()), "requestId", RequestIds.get(request));
    }
    @GetMapping("/knowledge-bases")
    Map<String,Object> list(HttpServletRequest request) { return Map.of("knowledgeBases", store.list(), "requestId", RequestIds.get(request)); }
    @PostMapping(value="/knowledge-bases/{kb}/documents", consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.CREATED)
    Map<String,Object> upload(@PathVariable UUID kb, @RequestPart MultipartFile file, HttpServletRequest request) throws IOException {
        String type = file.getContentType();
        if (type != null && !Set.of("text/plain", "application/octet-stream").contains(type.toLowerCase(Locale.ROOT)))
            throw ApiException.bad("M1 只接收 text/plain 或 application/octet-stream 的 UTF-8 TXT 文件");
        var document = ingestion.upload(kb, file.getOriginalFilename(), file.getBytes());
        return Map.of("document", document, "chunkCount", store.chunks(kb, document.id()).size(), "requestId", RequestIds.get(request));
    }
    @GetMapping("/knowledge-bases/{kb}/documents")
    Map<String,Object> files(@PathVariable UUID kb, HttpServletRequest request) { return Map.of("documents", store.files(kb), "requestId", RequestIds.get(request)); }
    @GetMapping("/documents/{id}/status")
    Map<String,Object> status(@PathVariable UUID id, HttpServletRequest request) {
        var kb = store.kbForDocument(id);
        return Map.of("document", store.file(kb, id), "chunkCount", store.chunks(kb, id).size(), "requestId", RequestIds.get(request));
    }
    @PostMapping("/documents/{id}/reindex")
    Map<String,Object> reindex(@PathVariable UUID id, HttpServletRequest request) {
        return Map.of("document", ingestion.reindex(store.kbForDocument(id), id), "requestId", RequestIds.get(request));
    }
    @DeleteMapping("/documents/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) { ingestion.delete(store.kbForDocument(id), id); }
    @PostMapping("/knowledge-bases/{kb}/ask")
    Map<String,Object> ask(@PathVariable UUID kb, @Valid @RequestBody Question body, HttpServletRequest request) {
        var answer = rag.ask(kb, body.question());
        return Map.of("answer", answer.answer(), "sources", answer.sources(), "grounded", answer.grounded(),
                "insufficientEvidence", answer.insufficientEvidence(), "elapsedMs", answer.elapsedMs(), "requestId", RequestIds.get(request));
    }
}
