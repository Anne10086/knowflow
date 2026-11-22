package dev.knowflow.api;

import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.web.client.RestClientResponseException;

/** Classify provider failures without exposing response bodies, keys or document text. */
public final class ModelFailures {
    private ModelFailures() {}
    public static ApiException safe(Exception failure, String fallbackCode, String fallbackMessage) {
        Throwable cause=failure;
        for(int depth=0;cause!=null && depth<12;depth++,cause=cause.getCause()) {
            int status=0;
            if(cause instanceof RestClientResponseException response) status=response.getStatusCode().value();
            else if(cause instanceof NonTransientAiException && cause.getMessage()!=null) {
                var match=java.util.regex.Pattern.compile("^(\\d{3})\\s*-").matcher(cause.getMessage());
                if(match.find()) status=Integer.parseInt(match.group(1));
            }
            if(status==401) return ApiException.unavailable("MODEL_AUTH_FAILED","模型鉴权失败（401），请核对密钥、所属地区及接口地址；不得混用 Coding Plan 与通用模型密钥");
            if(status==403) return ApiException.unavailable("MODEL_ACCESS_DENIED","模型访问被拒绝（403），请检查模型权限和账户状态");
        }
        return ApiException.unavailable(fallbackCode,fallbackMessage);
    }
}
