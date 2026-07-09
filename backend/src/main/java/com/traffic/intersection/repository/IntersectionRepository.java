package com.traffic.intersection.repository;

import com.traffic.intersection.dto.IntersectionResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class IntersectionRepository {

    private final JdbcTemplate jdbcTemplate;

    public IntersectionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<IntersectionResponse> findAll() {
        return jdbcTemplate.query("""
                select id, code, name, district, longitude, latitude, status,
                       metadata::text as metadata, created_at, updated_at
                from intersections
                order by code
                """, this::mapIntersection);
    }

    public Optional<IntersectionResponse> findByCode(String code) {
        List<IntersectionResponse> rows = jdbcTemplate.query("""
                select id, code, name, district, longitude, latitude, status,
                       metadata::text as metadata, created_at, updated_at
                from intersections
                where code = ?
                """, this::mapIntersection, code);
        return rows.stream().findFirst();
    }

    public int updateStatus(String code, String status) {
        return jdbcTemplate.update("""
                update intersections
                set status = ?
                where code = ?
                """, status, code);
    }

    private IntersectionResponse mapIntersection(ResultSet rs, int rowNum) throws SQLException {
        return new IntersectionResponse(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("district"),
                rs.getBigDecimal("longitude"),
                rs.getBigDecimal("latitude"),
                rs.getString("status"),
                rs.getString("metadata"),
                toOffsetDateTime(rs.getTimestamp("created_at")),
                toOffsetDateTime(rs.getTimestamp("updated_at"))
        );
    }

    private OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        if (timestamp == null) {
            return null;
        }
        return timestamp.toInstant().atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }
}
