package dev.knowflow.retrieval;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.R;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.dml.DeleteParam;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** Deletion uses journaled UUIDs only: no embedding, model credentials or vector-store initialization. */
@Component
public class MilvusVectorDeletion {
    private final ObjectProvider<MilvusServiceClient> clients;
    private final String collection;
    public MilvusVectorDeletion(ObjectProvider<MilvusServiceClient> clients,
            @Value("${knowflow.milvus.collection}") String collection) {
        this.clients=clients; this.collection=collection;
    }
    public void delete(List<UUID> ids) {
        if(ids.isEmpty()) return;
        var client=clients.getObject();
        var exists=client.hasCollection(HasCollectionParam.newBuilder().withCollectionName(collection).build());
        check(exists);
        // The first import may precede collection creation. An absent collection contains no vectors to delete.
        if(Boolean.FALSE.equals(exists.getData())) return;
        if(exists.getData()==null) throw new IllegalStateException("Milvus returned no collection status");
        String expression=MilvusVectorStore.DOC_ID_FIELD_NAME+" in ["+
                ids.stream().map(id->"\""+id+"\"").collect(Collectors.joining(","))+"]";
        check(client.delete(DeleteParam.newBuilder().withCollectionName(collection).withExpr(expression).build()));
    }
    private static void check(R<?> response) {
        if(response==null || !Integer.valueOf(0).equals(response.getStatus()))
            throw new IllegalStateException("Milvus vector deletion unavailable");
    }
}
