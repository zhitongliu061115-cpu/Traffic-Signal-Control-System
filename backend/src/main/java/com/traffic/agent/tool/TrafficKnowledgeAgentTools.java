package com.traffic.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traffic.agent.service.BailianKnowledgeRetrieveService;
import dev.langchain4j.agent.tool.Tool;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class TrafficKnowledgeAgentTools {

    private static final int MAX_TOP_K = 8;
    private static final int MAX_FILE_CHARS = 120_000;

    private final BailianKnowledgeRetrieveService bailianRetrieveService;

    public TrafficKnowledgeAgentTools() {
        this(new ObjectMapper(), null);
    }

    @Autowired
    public TrafficKnowledgeAgentTools(
            ObjectMapper objectMapper,
            BailianKnowledgeRetrieveService bailianRetrieveService
    ) {
        this.bailianRetrieveService = bailianRetrieveService;
    }

    @Tool(name = "search_knowledge_base", value = "Search project docs and, when configured, Bailian Retrieve semantic slices. Read-only.")
    public AgentToolResult searchKnowledgeBase(String query, Integer topK, String scope) {
        if (!StringUtils.hasText(query)) {
            return new AgentToolResult(
                    false,
                    "search_knowledge_base",
                    null,
                    List.of(),
                    List.of("query cannot be empty"),
                    Instant.now()
            );
        }
        return AgentToolSupport.run(
                "search_knowledge_base",
                () -> search(query.trim(), normalizeTopK(topK), scope),
                "Hybrid retrieval from local project documents and Bailian Retrieve semantic slices"
        );
    }

    private KnowledgeSearchResponse search(String query, int topK, String scope) {
        List<KnowledgeHit> localHits = searchLocal(query, topK, scope);
        RemoteKnowledgeResult remote = searchBailian(query, topK);
        List<String> warnings = new ArrayList<>(remote.warnings());
        List<KnowledgeHit> merged = new ArrayList<>();
        merged.addAll(remote.hits());
        merged.addAll(localHits);
        List<KnowledgeHit> ranked = merged.stream()
                .sorted(Comparator.comparingInt(KnowledgeHit::score).reversed())
                .limit(topK)
                .toList();
        Map<String, Object> route = new LinkedHashMap<>();
        route.put("mode", "single_bailian_index_plus_local_docs");
        route.put("scope", scope);
        route.put("remoteStatus", remote.providerStatus().status());
        route.put("note", "Bailian Retrieve returns semantic slices; these snippets are passed to the LLM as tool evidence.");
        return new KnowledgeSearchResponse(
                query,
                scope,
                new ProviderStatus(
                        "local",
                        "available",
                        Map.of("hitCount", localHits.size())
                ),
                remote.providerStatus(),
                ranked,
                warnings,
                route
        );
    }

    private List<KnowledgeHit> searchLocal(String query, int topK, String scope) {
        Path root = resolveRepoRoot();
        List<String> terms = splitTerms(query);
        return documentCandidates(root, scope).stream()
                .map(path -> scoreDocument(root, path, query, terms))
                .filter(hit -> hit.score() > 0)
                .sorted(Comparator.comparingInt(KnowledgeHit::score).reversed())
                .limit(topK)
                .toList();
    }

    private RemoteKnowledgeResult searchBailian(String query, int topK) {
        if (bailianRetrieveService == null) {
            return new RemoteKnowledgeResult(
                    new ProviderStatus("bailian", "disabled", Map.of()),
                    List.of(),
                    List.of("Bailian Retrieve service is not available; using local documents only.")
            );
        }
        BailianKnowledgeRetrieveService.RetrieveResult result = bailianRetrieveService.retrieve(query, topK);
        List<KnowledgeHit> hits = result.slices().stream()
                .map(slice -> new KnowledgeHit(
                        slice.source(),
                        scoreToInt(slice.score()),
                        truncate(slice.text(), 1_200),
                        List.of(),
                        slice.metadata()
                ))
                .toList();
        ProviderStatus providerStatus = new ProviderStatus(
                result.providerStatus().provider(),
                result.providerStatus().status(),
                result.providerStatus().details()
        );
        return new RemoteKnowledgeResult(providerStatus, hits, result.warnings());
    }

    private int scoreToInt(double score) {
        if (Double.isNaN(score) || Double.isInfinite(score)) {
            return 0;
        }
        if (score <= 1.0) {
            return (int) Math.round(score * 100);
        }
        return (int) Math.round(Math.min(score, 100.0));
    }

    private List<Path> documentCandidates(Path root, String scope) {
        List<Path> bases = "backend".equalsIgnoreCase(scope)
                ? List.of(root.resolve("backend").resolve("docs"))
                : List.of(root, root.resolve("docs"), root.resolve("backend").resolve("docs"));
        return bases.stream()
                .filter(Files::exists)
                .flatMap(base -> {
                    try {
                        return Files.walk(base, base.equals(root) ? 1 : 8);
                    } catch (IOException ex) {
                        return java.util.stream.Stream.<Path>empty();
                    }
                })
                .filter(Files::isRegularFile)
                .filter(path -> {
                    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                    return name.endsWith(".md") || name.endsWith(".txt");
                })
                .distinct()
                .toList();
    }

    private KnowledgeHit scoreDocument(Path root, Path path, String query, List<String> terms) {
        String content;
        try {
            content = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return new KnowledgeHit(root.relativize(path).toString(), 0, "", List.of("read failed: " + ex.getMessage()));
        }
        if (content.length() > MAX_FILE_CHARS) {
            content = content.substring(0, MAX_FILE_CHARS);
        }
        String lowerContent = content.toLowerCase(Locale.ROOT);
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        int score = countOccurrences(lowerContent, lowerQuery) * 10;
        for (String term : terms) {
            score += countOccurrences(lowerContent, term.toLowerCase(Locale.ROOT));
        }
        return new KnowledgeHit(
                root.relativize(path).toString(),
                score,
                snippet(content, lowerContent, lowerQuery, terms),
                List.of(),
                Map.of("provider", "local")
        );
    }

    private String snippet(String content, String lowerContent, String lowerQuery, List<String> terms) {
        int index = lowerContent.indexOf(lowerQuery);
        if (index < 0) {
            for (String term : terms) {
                index = lowerContent.indexOf(term.toLowerCase(Locale.ROOT));
                if (index >= 0) {
                    break;
                }
            }
        }
        if (index < 0) {
            index = 0;
        }
        int start = Math.max(0, index - 120);
        int end = Math.min(content.length(), index + 360);
        return content.substring(start, end).replaceAll("\\s+", " ").trim();
    }

    private int countOccurrences(String content, String term) {
        if (!StringUtils.hasText(term)) {
            return 0;
        }
        int count = 0;
        int index = content.indexOf(term);
        while (index >= 0) {
            count++;
            index = content.indexOf(term, index + term.length());
        }
        return count;
    }

    private List<String> splitTerms(String query) {
        return java.util.Arrays.stream(query.split("[\\s,，。；;:：、/\\\\|]+"))
                .map(String::trim)
                .filter(term -> term.length() >= 2)
                .limit(8)
                .toList();
    }

    private int normalizeTopK(Integer topK) {
        if (topK == null || topK <= 0) {
            return 5;
        }
        return Math.min(topK, MAX_TOP_K);
    }

    private Path resolveRepoRoot() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if ("backend".equalsIgnoreCase(cwd.getFileName().toString())) {
            return cwd.getParent();
        }
        return cwd;
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }

    public record KnowledgeSearchResponse(
            String query,
            String scope,
            ProviderStatus localProvider,
            ProviderStatus bailianProvider,
            List<KnowledgeHit> hits,
            List<String> warnings,
            Map<String, Object> route
    ) {
    }

    public record ProviderStatus(
            String provider,
            String status,
            Map<String, Object> details
    ) {
    }

    public record KnowledgeHit(
            String source,
            int score,
            String snippet,
            List<String> warnings,
            Map<String, Object> metadata
    ) {
        public KnowledgeHit(String source, int score, String snippet, List<String> warnings) {
            this(source, score, snippet, warnings, Map.of());
        }
    }

    private record RemoteKnowledgeResult(
            ProviderStatus providerStatus,
            List<KnowledgeHit> hits,
            List<String> warnings
    ) {
    }
}
