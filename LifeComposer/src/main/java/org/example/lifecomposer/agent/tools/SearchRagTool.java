package org.example.lifecomposer.agent.tools;

import com.google.gson.JsonObject;
import org.example.lifecomposer.agent.AgentTool;
import org.example.lifecomposer.agent.AgentToolContext;
import org.example.lifecomposer.agent.AgentToolSupport;
import org.example.lifecomposer.agent.ToolResult;
import org.example.lifecomposer.rag.RagHit;
import org.example.lifecomposer.rag.RagSearchException;
import org.example.lifecomposer.rag.RagSearchService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Vector search over RAG chunks using the local Ollama embedding model. */
@Component
public class SearchRagTool implements AgentTool {

    private static final Logger LOG = LogManager.getLogger(SearchRagTool.class);

    private final RagSearchService ragSearchService;

    public SearchRagTool(RagSearchService ragSearchService) {
        this.ragSearchService = ragSearchService;
    }

    @Override
    public String name() {
        return "search_rag";
    }

    @Override
    public String description() {
        return "对用户问题生成 embedding，并在本地 RAG 切片库中做余弦相似度检索，返回最相关的知识片段及来源。";
    }

    @Override
    public String displayDescription() {
        return "检索知识库片段";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Map.of("type", "string", "description", "要检索的自然语言问题"));
        properties.put("topK", Map.of("type", "integer", "description", "返回条数，默认 5，最大 20"));
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("query"),
                "additionalProperties", false);
    }

    @Override
    public ToolResult execute(AgentToolContext context, JsonObject arguments) {
        String query = AgentToolSupport.stringArg(arguments, "query");
        if (query == null) {
            return ToolResult.error("INVALID_ARGUMENTS", "query 不能为空");
        }
        Integer topK = AgentToolSupport.intArg(arguments, "topK");
        try {
            List<RagHit> hits = ragSearchService.search(query, topK);
            List<Map<String, Object>> items = new ArrayList<>();
            for (RagHit hit : hits) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("chunkId", hit.chunkId());
                item.put("title", hit.title());
                item.put("text", hit.text());
                item.put("sourceType", hit.sourceType());
                item.put("sourceUrl", hit.sourceUrl());
                item.put("sourceFile", hit.sourceFile());
                item.put("pageOrSection", hit.pageOrSection());
                item.put("relatedResourceId", hit.relatedResourceId());
                item.put("similarity", hit.similarity());
                items.add(item);
            }
            return ToolResult.ok(Map.of("count", items.size(), "items", items));
        } catch (RagSearchException e) {
            // The cause can name hosts/models; keep it in the log, not in the model context.
            LOG.warn("search_rag unavailable: {}", e.getMessage());
            return ToolResult.error("RAG_UNAVAILABLE", "知识库检索暂不可用");
        }
    }
}
