package com.traffic.agent.tool;

import dev.langchain4j.agent.tool.Tool;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class TrafficKnowledgeAgentTools {

    private static final int MAX_TOP_K = 8;
    private static final int MAX_FILE_CHARS = 120_000;

    @Tool(name = "search_knowledge_base", value = "查询项目文档、接口规范、部署资料、Agent 设计和交通算法说明。只读。")
    public AgentToolResult searchKnowledgeBase(String query, Integer topK, String scope) {
        if (query == null || query.isBlank()) {
            return new AgentToolResult(
                    false,
                    "search_knowledge_base",
                    null,
                    List.of(),
                    List.of("query 不能为空"),
                    java.time.Instant.now()
            );
        }
        return AgentToolSupport.run(
                "search_knowledge_base",
                () -> search(query, normalizeTopK(topK), scope),
                "来自本地项目文档的知识检索结果"
        );
    }

    private KnowledgeSearchResponse search(String query, int topK, String scope) {
        Path root = resolveRepoRoot();
        List<String> terms = splitTerms(query);
        List<KnowledgeHit> hits = documentCandidates(root, scope).stream()
                .map(path -> scoreDocument(root, path, query, terms))
                .filter(hit -> hit.score() > 0)
                .sorted(Comparator.comparingInt(KnowledgeHit::score).reversed())
                .limit(topK)
                .toList();
        return new KnowledgeSearchResponse(query, scope, hits);
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
            return new KnowledgeHit(root.relativize(path).toString(), 0, "", List.of("读取失败：" + ex.getMessage()));
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
        return new KnowledgeHit(root.relativize(path).toString(), score, snippet(content, lowerContent, lowerQuery, terms), List.of());
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
        if (term == null || term.isBlank()) {
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
        return java.util.Arrays.stream(query.split("[\\s,，。；;:：/\\\\|]+"))
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

    public record KnowledgeSearchResponse(
            String query,
            String scope,
            List<KnowledgeHit> hits
    ) {
    }

    public record KnowledgeHit(
            String source,
            int score,
            String snippet,
            List<String> warnings
    ) {
    }
}
