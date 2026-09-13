package com.ssafy.ddubi.domain.recommendation.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.TimeZone;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CI의 UTC JVM에서도 DATETIME에 KST 벽시각을 그대로 바인딩하는지 확인한다.
 * DB 없이 JDBC 경계를 검사하며, 실제 MySQL 왕복은 ScheduleEvaluationIntegrationTest가 검증한다.
 */
class ScheduleEvaluationJdbcTimeTest {
    @Test void utcJvmBindsKstLocalDateTimeWithoutTimestampConversion() throws Exception {
        TimeZone previous = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            Instant observed = Instant.parse("2026-09-11T02:00:00.123456Z");
            LocalDateTime bound = ScheduleEvaluationStore.ts(observed);
            assertThat(bound).isEqualTo(LocalDateTime.parse("2026-09-11T11:00:00.123456"));
            DataSource source = mock(DataSource.class);
            Connection connection = mock(Connection.class);
            PreparedStatement statement = mock(PreparedStatement.class);
            when(source.getConnection()).thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenReturn(statement);
            when(statement.executeUpdate()).thenReturn(1);
            new JdbcTemplate(source).update("UPDATE schedule_evaluation SET next_check_at=?", bound);
            verify(statement).setObject(1, bound);
            verify(statement, never()).setTimestamp(anyInt(), any());
        } finally {
            TimeZone.setDefault(previous);
        }
    }
}
