package com.traffic.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traffic.agent.dto.AgentDataDtos.ConversationResponse;
import com.traffic.agent.dto.AgentDataDtos.CreateConversationRequest;
import com.traffic.agent.dto.AgentDataDtos.CreateMessageRequest;
import com.traffic.agent.dto.AgentDataDtos.MessageResponse;
import com.traffic.agent.dto.AgentDataDtos.RecordToolCallRequest;
import com.traffic.agent.dto.AgentDataDtos.ToolCallResponse;
import com.traffic.common.exception.BusinessException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentDataService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final int MAX_RESULT_PAYLOAD_CHARS = 12000;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AgentDataService(NamedParameterJdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ConversationResponse createConversation(CreateConversationRequest request) {
        UUID id = UUID.randomUUID();
        UUID userId = parseOptionalUuid(request.userId(), "userId").orElse(null);
        UUID sessionId = findSimulationSessionId(request.sid()).orElse(null);
        jdbcTemplate.update("""
                insert into agent_conversation (
                    id, user_id, session_id, external_session_id, title
                )
                values (:id, :userId, :sessionId, :externalSessionId, :title)
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("userId", userId)
                .addValue("sessionId", sessionId)
                .addValue("externalSessionId", blankToNull(request.externalSessionId()))
                .addValue("title", request.title()));
        return getConversation(id.toString());
    }

    public ConversationResponse getConversation(String conversationId) {
        UUID id = parseRequiredUuid(conversationId, "conversationId");
        ConversationResponse response = jdbcTemplate.query("""
                select ac.id, ac.user_id, ss.sid, ac.external_session_id, ac.title,
                       ac.created_at, ac.updated_at
                from agent_conversation ac
                left join simulation_session ss on ss.id = ac.session_id
                where ac.id = :id
                """, new MapSqlParameterSource("id", id), rs -> rs.next() ? mapConversation(rs) : null);
        if (response == null) {
            throw new BusinessException("未找到 Agent 会话：" + conversationId);
        }
        return response;
    }

    public java.util.List<ConversationResponse> listConversations(
            String sid,
            String externalSessionId,
            String userId,
            int requestedLimit
    ) {
        int limit = normalizeLimit(requestedLimit);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", limit)
                .addValue("sid", sid)
                .addValue("externalSessionId", externalSessionId);
        StringBuilder sql = new StringBuilder("""
                select ac.id, ac.user_id, ss.sid, ac.external_session_id, ac.title,
                       ac.created_at, ac.updated_at
                from agent_conversation ac
                left join simulation_session ss on ss.id = ac.session_id
                where 1 = 1
                """);
        if (hasText(sid)) {
            sql.append(" and ss.sid = :sid");
        }
        if (hasText(externalSessionId)) {
            sql.append(" and ac.external_session_id = :externalSessionId");
        }
        parseOptionalUuid(userId, "userId").ifPresent(uuid -> {
            sql.append(" and ac.user_id = :userId");
            params.addValue("userId", uuid);
        });
        sql.append(" order by ac.updated_at desc, ac.created_at desc limit :limit");
        return jdbcTemplate.query(sql.toString(), params, (rs, rowNum) -> mapConversation(rs));
    }

    @Transactional
    public MessageResponse createMessage(String conversationId, CreateMessageRequest request) {
        UUID conversationUuid = parseRequiredUuid(conversationId, "conversationId");
        ensureConversationExists(conversationUuid);
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into agent_message (id, conversation_id, role, content)
                values (:id, :conversationId, :role, :content)
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("conversationId", conversationUuid)
                .addValue("role", request.role())
                .addValue("content", request.content()));
        touchConversation(conversationUuid);
        return getMessage(id.toString());
    }

    public MessageResponse getMessage(String messageId) {
        UUID id = parseRequiredUuid(messageId, "messageId");
        MessageResponse response = jdbcTemplate.query("""
                select id, conversation_id, role, content, created_at
                from agent_message
                where id = :id
                """, new MapSqlParameterSource("id", id), rs -> rs.next() ? mapMessage(rs) : null);
        if (response == null) {
            throw new BusinessException("未找到 Agent 消息：" + messageId);
        }
        return response;
    }

    public java.util.List<MessageResponse> listMessages(String conversationId, int requestedLimit) {
        UUID conversationUuid = parseRequiredUuid(conversationId, "conversationId");
        ensureConversationExists(conversationUuid);
        return jdbcTemplate.query("""
                select id, conversation_id, role, content, created_at
                from agent_message
                where conversation_id = :conversationId
                order by created_at asc
                limit :limit
                """, new MapSqlParameterSource()
                .addValue("conversationId", conversationUuid)
                .addValue("limit", normalizeLimit(requestedLimit)), (rs, rowNum) -> mapMessage(rs));
    }

    @Transactional
    public ToolCallResponse recordToolCall(String messageId, RecordToolCallRequest request) {
        return recordToolCall(
                messageId,
                request.toolName(),
                request.arguments(),
                request.result(),
                hasText(request.status()) ? request.status() : "SUCCESS",
                request.latencyMs() == null ? 0 : request.latencyMs(),
                request.errorMessage()
        );
    }

    @Transactional
    public ToolCallResponse recordToolCall(
            String messageId,
            String toolName,
            Object arguments,
            Object result,
            String status,
            int latencyMs,
            String errorMessage
    ) {
        UUID messageUuid = parseRequiredUuid(messageId, "messageId");
        ensureMessageExists(messageUuid);
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into agent_tool_call (
                    id, message_id, tool_name, arguments_payload, result_payload,
                    status, latency_ms, error_message
                )
                values (
                    :id, :messageId, :toolName, :argumentsPayload, :resultPayload,
                    :status, :latencyMs, :errorMessage
                )
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("messageId", messageUuid)
                .addValue("toolName", toolName)
                .addValue("argumentsPayload", toJson(arguments, Integer.MAX_VALUE))
                .addValue("resultPayload", toJson(result, MAX_RESULT_PAYLOAD_CHARS))
                .addValue("status", hasText(status) ? status : "SUCCESS")
                .addValue("latencyMs", Math.max(latencyMs, 0))
                .addValue("errorMessage", blankToNull(errorMessage)));
        return getToolCall(id.toString());
    }

    public ToolCallResponse getToolCall(String toolCallId) {
        UUID id = parseRequiredUuid(toolCallId, "toolCallId");
        ToolCallResponse response = jdbcTemplate.query("""
                select id, message_id, tool_name, arguments_payload, result_payload,
                       status, latency_ms, error_message, created_at
                from agent_tool_call
                where id = :id
                """, new MapSqlParameterSource("id", id), rs -> rs.next() ? mapToolCall(rs) : null);
        if (response == null) {
            throw new BusinessException("未找到 Agent 工具调用：" + toolCallId);
        }
        return response;
    }

    public java.util.List<ToolCallResponse> listToolCallsByMessage(String messageId, int requestedLimit) {
        UUID messageUuid = parseRequiredUuid(messageId, "messageId");
        ensureMessageExists(messageUuid);
        return jdbcTemplate.query("""
                select id, message_id, tool_name, arguments_payload, result_payload,
                       status, latency_ms, error_message, created_at
                from agent_tool_call
                where message_id = :messageId
                order by created_at desc
                limit :limit
                """, new MapSqlParameterSource()
                .addValue("messageId", messageUuid)
                .addValue("limit", normalizeLimit(requestedLimit)), (rs, rowNum) -> mapToolCall(rs));
    }

    public java.util.List<ToolCallResponse> listToolCalls(String toolName, String status, int requestedLimit) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("toolName", toolName)
                .addValue("status", status)
                .addValue("limit", normalizeLimit(requestedLimit));
        StringBuilder sql = new StringBuilder("""
                select id, message_id, tool_name, arguments_payload, result_payload,
                       status, latency_ms, error_message, created_at
                from agent_tool_call
                where 1 = 1
                """);
        if (hasText(toolName)) {
            sql.append(" and tool_name = :toolName");
        }
        if (hasText(status)) {
            sql.append(" and status = :status");
        }
        sql.append(" order by created_at desc limit :limit");
        return jdbcTemplate.query(sql.toString(), params, (rs, rowNum) -> mapToolCall(rs));
    }

    private Optional<UUID> findSimulationSessionId(String sid) {
        if (!hasText(sid)) {
            return Optional.empty();
        }
        UUID id = jdbcTemplate.query("""
                select id from simulation_session where sid = :sid limit 1
                """, new MapSqlParameterSource("sid", sid), rs -> rs.next() ? (UUID) rs.getObject("id") : null);
        return Optional.ofNullable(id);
    }

    private void ensureConversationExists(UUID conversationId) {
        Boolean exists = jdbcTemplate.queryForObject("""
                select exists(select 1 from agent_conversation where id = :id)
                """, new MapSqlParameterSource("id", conversationId), Boolean.class);
        if (!Boolean.TRUE.equals(exists)) {
            throw new BusinessException("未找到 Agent 会话：" + conversationId);
        }
    }

    private void ensureMessageExists(UUID messageId) {
        Boolean exists = jdbcTemplate.queryForObject("""
                select exists(select 1 from agent_message where id = :id)
                """, new MapSqlParameterSource("id", messageId), Boolean.class);
        if (!Boolean.TRUE.equals(exists)) {
            throw new BusinessException("未找到 Agent 消息：" + messageId);
        }
    }

    private void touchConversation(UUID conversationId) {
        jdbcTemplate.update("""
                update agent_conversation
                set updated_at = current_timestamp
                where id = :id
                """, new MapSqlParameterSource("id", conversationId));
    }

    private ConversationResponse mapConversation(ResultSet rs) throws SQLException {
        return new ConversationResponse(
                uuidString(rs, "id"),
                uuidString(rs, "user_id"),
                rs.getString("sid"),
                rs.getString("external_session_id"),
                rs.getString("title"),
                instant(rs, "created_at"),
                instant(rs, "updated_at")
        );
    }

    private MessageResponse mapMessage(ResultSet rs) throws SQLException {
        return new MessageResponse(
                uuidString(rs, "id"),
                uuidString(rs, "conversation_id"),
                rs.getString("role"),
                rs.getString("content"),
                instant(rs, "created_at")
        );
    }

    private ToolCallResponse mapToolCall(ResultSet rs) throws SQLException {
        return new ToolCallResponse(
                uuidString(rs, "id"),
                uuidString(rs, "message_id"),
                rs.getString("tool_name"),
                rs.getString("arguments_payload"),
                rs.getString("result_payload"),
                rs.getString("status"),
                rs.getInt("latency_ms"),
                rs.getString("error_message"),
                instant(rs, "created_at")
        );
    }

    private UUID parseRequiredUuid(String value, String fieldName) {
        return parseOptionalUuid(value, fieldName)
                .orElseThrow(() -> new BusinessException(fieldName + " 必须是 UUID"));
    }

    private Optional<UUID> parseOptionalUuid(String value, String fieldName) {
        if (!hasText(value)) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(fieldName + " 必须是 UUID：" + value);
        }
    }

    private int normalizeLimit(int requestedLimit) {
        if (requestedLimit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requestedLimit, MAX_LIMIT);
    }

    private String toJson(Object value, int maxChars) {
        try {
            String json = objectMapper.writeValueAsString(value == null ? Map.of() : value);
            return json.length() <= maxChars ? json : json.substring(0, maxChars) + "...";
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private String blankToNull(String value) {
        return hasText(value) ? value : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String uuidString(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : value.toString();
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
