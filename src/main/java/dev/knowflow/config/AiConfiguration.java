package dev.knowflow.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.*;
import com.alibaba.cloud.ai.dashscope.embedding.*;
import dev.knowflow.api.ApiException;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Configuration(proxyBeanMethods = false)
public class AiConfiguration {
    @Bean @Lazy
    DashScopeApi dashScopeApi(@Value("${knowflow.ai.api-key}") String key, @Value("${knowflow.ai.base-url}") String url) {
        if (key.isBlank()) throw ApiException.unavailable("MODEL_NOT_CONFIGURED", "未配置 DASHSCOPE_API_KEY，请在本机环境变量中设置后重启后端");
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(60));
        return DashScopeApi.builder().apiKey(key).baseUrl(url)
                .restClientBuilder(RestClient.builder().requestFactory(factory)).build();
    }
    @Bean @Lazy
    EmbeddingModel embeddingModel(DashScopeApi api, @Value("${knowflow.ai.embedding-model}") String model,
            @Value("${knowflow.ai.embedding-dimensions}") int dimensions) {
        return DashScopeEmbeddingModel.builder().dashScopeApi(api)
                .defaultOptions(DashScopeEmbeddingOptions.builder().model(model).dimensions(dimensions).build())
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build()).build();
    }
    @Bean @Lazy
    ChatModel chatModel(DashScopeApi api, @Value("${knowflow.ai.chat-model}") String model) {
        return DashScopeChatModel.builder().dashScopeApi(api)
                .defaultOptions(DashScopeChatOptions.builder().model(model).temperature(0.0).maxToken(2000).build())
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build()).build();
    }
    @Bean(destroyMethod = "close") @Lazy
    MilvusServiceClient milvusClient(@Value("${knowflow.milvus.host}") String host, @Value("${knowflow.milvus.port}") int port) {
        return new MilvusServiceClient(ConnectParam.newBuilder().withHost(host).withPort(port)
                .withConnectTimeout(5, TimeUnit.SECONDS).withRpcDeadline(15, TimeUnit.SECONDS).build());
    }
    @Bean @Lazy
    MilvusVectorStore milvusVectorStore(MilvusServiceClient client, @Lazy EmbeddingModel embeddingModel,
            @Value("${knowflow.milvus.collection}") String collection, @Value("${knowflow.ai.embedding-dimensions}") int dimensions,
            EmbeddingIdentity identity, @Value("${knowflow.ai.embedding-model}") String model) {
        identity.bind(collection, model, dimensions);
        return MilvusVectorStore.builder(client, embeddingModel).collectionName(collection)
                .embeddingDimension(dimensions).metricType(MetricType.COSINE).indexType(IndexType.FLAT)
                .initializeSchema(true).build();
    }
}
