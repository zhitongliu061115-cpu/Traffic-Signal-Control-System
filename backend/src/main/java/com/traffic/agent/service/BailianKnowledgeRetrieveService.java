package com.traffic.agent.service;

import com.aliyun.bailian20231229.Client;
import com.aliyun.bailian20231229.models.RetrieveRequest;
import com.aliyun.bailian20231229.models.RetrieveResponse;
import com.aliyun.bailian20231229.models.RetrieveResponseBody;
import com.aliyun.bailian20231229.models.RetrieveResponseBody.RetrieveResponseBodyDataNodes;
import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teautil.models.RuntimeOptions;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class BailianKnowledgeRetrieveService {

    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String endpoint;
    private final String accessKeyId;
    private final String accessKeySecret;
    private final String workspaceId;
    private final String indexId;
    private final int denseTopK;
    private final int sparseTopK;
    private final boolean enableReranking;
    private final int rerankTopN;

    private volatile Client client;

    public BailianKnowledgeRetrieveService(
            ObjectMapper objectMapper,
            @Value("${bailian.knowledge.enabled:false}") boolean enabled,
            @Value("${bailian.knowledge.endpoint:bailian.cn-beijing.aliyuncs.com}") String endpoint,
            @Value("${bailian.knowledge.access-key-id:}") String accessKeyId,
            @Value("${bailian.knowledge.access-key-secret:}") String accessKeySecret,
            @Value("${bailian.knowledge.workspace-id:}") String workspaceId,
            @Value("${bailian.knowledge.index-id:}") String indexId,
            @Value("${bailian.knowledge.dense-top-k:10}") int denseTopK,
            @Value("${bailian.knowledge.sparse-top-k:10}") int sparseTopK,
            @Value("${bailian.knowledge.enable-reranking:true}") boolean enableReranking,
            @Value("${bailian.knowledge.rerank-top-n:5}") int rerankTopN
    ) {
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.endpoint = normalize(endpoint);
        this.accessKeyId = normalize(accessKeyId);
        this.accessKeySecret = normalize(accessKeySecret);
        this.workspaceId = normalize(workspaceId);
        this.indexId = normalize(indexId);
        this.denseTopK = normalizeTopK(denseTopK, 10, 100);
        this.sparseTopK = normalizeTopK(sparseTopK, 10, 100);
        this.enableReranking = enableReranking;
        this.rerankTopN = normalizeTopK(rerankTopN, 5, 20);
    }

    public RetrieveResult retrieve(String query, int topK) {
        if (!enabled) {
            return new RetrieveResult(
                    new ProviderStatus("bailian", "disabled", Map.of()),
                    List.of(),
                    List.of("Bailian Retrieve is disabled; using local documents only.")
            );
        }
        List<String> missing = missingConfigurations();
        if (!missing.isEmpty()) {
            return new RetrieveResult(
                    new ProviderStatus("bailian", "not_configured", Map.of("missing", missing)),
                    List.of(),
                    List.of("Bailian Retrieve is enabled but required configuration is missing: " + String.join(", ", missing))
            );
        }
        if (!StringUtils.hasText(query)) {
            return new RetrieveResult(
                    new ProviderStatus("bailian", "invalid_query", Map.of()),
                    List.of(),
                    List.of("Bailian Retrieve query cannot be empty.")
            );
        }
        try {
            RetrieveRequest request = new RetrieveRequest()
                    .setIndexId(indexId)
                    .setQuery(query)
                    .setDenseSimilarityTopK(denseTopK)
                    .setSparseSimilarityTopK(sparseTopK)
                    .setEnableReranking(enableReranking)
                    .setRerankTopN(Math.min(rerankTopN, Math.max(1, topK)));

            RetrieveResponse response = client().retrieveWithOptions(
                    workspaceId,
                    request,
                    null,
                    new RuntimeOptions()
            );
            RetrieveResponseBody body = response.getBody();
            List<KnowledgeSlice> slices = extractSlices(body, topK);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("endpoint", endpoint);
            details.put("workspaceId", workspaceId);
            details.put("indexId", indexId);
            details.put("requestId", body == null ? null : body.getRequestId());
            details.put("hitCount", slices.size());
            details.put("statusCode", response.getStatusCode());
            return new RetrieveResult(
                    new ProviderStatus("bailian", "available", details),
                    slices,
                    successWarnings(body)
            );
        } catch (Exception ex) {
            return new RetrieveResult(
                    new ProviderStatus("bailian", "error", Map.of(
                            "errorType", ex.getClass().getSimpleName(),
                            "indexId", indexId
                    )),
                    List.of(),
                    List.of("Bailian Retrieve failed: " + safeMessage(ex))
            );
        }
    }

    private Client client() throws Exception {
        Client current = client;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (client == null) {
                Config config = new Config()
                        .setAccessKeyId(accessKeyId)
                        .setAccessKeySecret(accessKeySecret)
                        .setEndpoint(endpoint);
                client = new Client(config);
            }
            return client;
        }
    }

    private List<KnowledgeSlice> extractSlices(RetrieveResponseBody body, int topK) {
        if (body == null || body.getData() == null || body.getData().getNodes() == null) {
            return List.of();
        }
        int limit = Math.max(1, Math.min(topK, 20));
        List<KnowledgeSlice> slices = new ArrayList<>();
        for (RetrieveResponseBodyDataNodes node : body.getData().getNodes()) {
            if (node == null || !StringUtils.hasText(node.getText())) {
                continue;
            }
            double score = node.getScore() == null ? 0.0 : node.getScore();
            slices.add(new KnowledgeSlice(
                    "bailian:" + indexId,
                    score,
                    node.getText(),
                    normalizeMetadata(node.getMetadata())
            ));
            if (slices.size() >= limit) {
                break;
            }
        }
        return slices;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeMetadata(Object metadata) {
        if (metadata == null) {
            return Map.of();
        }
        if (metadata instanceof Map<?, ?> rawMap) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            rawMap.forEach((key, value) -> normalized.put(String.valueOf(key), value));
            return normalized;
        }
        try {
            return objectMapper.convertValue(metadata, Map.class);
        } catch (IllegalArgumentException ex) {
            return Map.of("raw", String.valueOf(metadata));
        }
    }

    private List<String> successWarnings(RetrieveResponseBody body) {
        if (body == null) {
            return List.of("Bailian Retrieve response body is empty.");
        }
        if (Boolean.FALSE.equals(body.getSuccess())) {
            return List.of("Bailian Retrieve returned unsuccessful status: " + safe(body.getMessage()));
        }
        if (body.getData() == null || body.getData().getNodes() == null || body.getData().getNodes().isEmpty()) {
            return List.of("Bailian Retrieve returned no semantic slices.");
        }
        return List.of();
    }

    private List<String> missingConfigurations() {
        List<String> missing = new ArrayList<>();
        if (!StringUtils.hasText(endpoint)) {
            missing.add("endpoint");
        }
        if (!StringUtils.hasText(accessKeyId)) {
            missing.add("access-key-id");
        }
        if (!StringUtils.hasText(accessKeySecret)) {
            missing.add("access-key-secret");
        }
        if (!StringUtils.hasText(workspaceId)) {
            missing.add("workspace-id");
        }
        if (!StringUtils.hasText(indexId)) {
            missing.add("index-id");
        }
        return missing;
    }

    private int normalizeTopK(int value, int defaultValue, int maxValue) {
        if (value <= 0) {
            return defaultValue;
        }
        return Math.min(value, maxValue);
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.length() >= 2) {
            char first = normalized.charAt(0);
            char last = normalized.charAt(normalized.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return normalized.substring(1, normalized.length() - 1).trim();
            }
        }
        return normalized;
    }

    private String safeMessage(Exception ex) {
        String message = ex.getMessage();
        if (!StringUtils.hasText(message)) {
            return ex.getClass().getSimpleName();
        }
        return message
                .replace(accessKeyId, "***")
                .replace(accessKeySecret, "***");
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    public record RetrieveResult(
            ProviderStatus providerStatus,
            List<KnowledgeSlice> slices,
            List<String> warnings
    ) {
    }

    public record ProviderStatus(
            String provider,
            String status,
            Map<String, Object> details
    ) {
    }

    public record KnowledgeSlice(
            String source,
            double score,
            String text,
            Map<String, Object> metadata
    ) {
    }
}
