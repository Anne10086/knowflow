package dev.knowflow.api;

import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import static org.assertj.core.api.Assertions.*;

class ModelFailuresTest {
    @Test void identifiesNestedAuthenticationErrorWithoutExposingPayload() {
        var failure=new RuntimeException(new NonTransientAiException("401 - private-key-and-document"));
        var result=ModelFailures.safe(failure,"INDEX_FAILED","索引失败");
        assertThat(result.code()).isEqualTo("MODEL_AUTH_FAILED");
        assertThat(result.getMessage()).contains("401").doesNotContain("private-key-and-document");
    }
    @Test void handlesPermissionAndUnknownFailuresSafely() {
        assertThat(ModelFailures.safe(new NonTransientAiException("403 - secret"),"MODEL_FAILED","失败").code()).isEqualTo("MODEL_ACCESS_DENIED");
        assertThat(ModelFailures.safe(new RuntimeException("private document"),"MODEL_FAILED","失败").getMessage()).isEqualTo("失败");
    }
}
