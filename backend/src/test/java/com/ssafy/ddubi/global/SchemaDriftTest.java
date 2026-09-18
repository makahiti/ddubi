package com.ssafy.ddubi.global;

import com.ssafy.ddubi.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ERD 원천에 적힌 인덱스·제약이 <b>마이그레이션을 다 돌린 실제 DB</b>에 있는지 확인한다.
 *
 * <h3>왜 필요한가</h3>
 * 예전에는 {@code V1__init.sql} 이 ERD 의 복사본이라 두 파일을 비교하면 됐다. 지금은
 * 스키마 변경을 {@code V2__}, {@code V3__} 로 쌓으므로(2026-09-08 변경) <b>두 파일이 같지 않다.</b>
 * ERD 는 최종 목표, V1 은 최초 상태다.
 *
 * <p>그래서 "파일이 같은가" 가 아니라 <b>"ERD 에 적은 것이 실제로 만들어졌는가"</b> 를 본다.
 * ERD 에만 적고 마이그레이션을 안 만들면 이 테스트가 잡는다.
 *
 * <h3>다른 테스트가 못 잡는다</h3>
 * Hibernate {@code ddl-auto: validate} 는 <b>테이블·컬럼만</b> 본다. 인덱스와 UNIQUE 가
 * 통째로 없어도 통과한다. 도메인 테스트도 못 잡는다 — "재보고해도 행이 안 는다" 는 코드로도
 * 지켜지므로 DB 제약이 빠져도 초록불이다. 동시 요청이 들어오는 운영에서만 드러난다.
 *
 * <h3>한 방향만 본다</h3>
 * "ERD 에 있는 것이 DB 에 있는가" 만 검사하고 반대는 보지 않는다. MySQL 이 외래키에
 * 인덱스를 자동으로 만들기 때문에 반대 방향은 잡음이 많다.
 */
class SchemaDriftTest extends IntegrationTestSupport {

    /** 테스트는 backend/ 에서 돈다. ERD 원본은 저장소 루트의 docs/ 아래. */
    private static final Path ERD = Path.of("..", "docs", "ddubi_erd_v2.sql");

    private static final Pattern DECLARED =
            Pattern.compile("(?:UNIQUE\\s+KEY|KEY|CONSTRAINT)\\s+([A-Z][A-Z0-9_]+)");

    /**
     * CHECK 제약은 제외한다. MySQL 이 {@code information_schema.statistics} 에 넣지 않아
     * 조회 방식이 달라지는데, 이 테스트의 목적(인덱스·제약 유실 감지)에는 인덱스만으로 충분하다.
     */
    private static final String CHECK_PREFIX = "CK_";

    /** 작은따옴표 문자열(주로 COMMENT). 이 안의 SQL 예시 문구가 오탐을 만든다. */
    private static final Pattern STRING_LITERAL = Pattern.compile("'[^']*'");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("ERD 에 선언한 인덱스·제약이 마이그레이션 적용 후 DB 에 모두 존재한다")
    void everyDeclaredConstraintExistsInDatabase() throws IOException {
        Set<String> declared = parseDeclaredNames();
        assertThat(declared).as("ERD 에서 이름을 하나도 못 읽었다면 파싱이 깨진 것이다").isNotEmpty();

        Set<String> actual = readActualNames();

        Set<String> missing = declared.stream()
                .filter(name -> !actual.contains(name))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertThat(missing)
                .as("""
                        ERD 에는 있는데 실제 DB 에 없는 인덱스·제약이다.
                        ERD 만 고치고 마이그레이션(V2__, V3__ ...)을 안 만들었을 가능성이 크다.
                        Hibernate validate 도 다른 테스트도 이걸 못 잡으므로 여기서만 드러난다.
                          ERD: %s
                        """, ERD.toAbsolutePath())
                .isEmpty();
    }

    /**
     * {@code UNIQUE KEY UK_X} · {@code KEY IX_X} · {@code CONSTRAINT FK_X} 에서 이름만 뽑는다.
     *
     * <p>줄 단위로 읽고 <b>주석({@code --})은 버린다.</b> 주석을 남기면 두 가지를 오탐한다 —
     * 컬럼 설명에 적힌 {@code "ON DUPLICATE KEY UPDATE"} 같은 문구와, 팀 합의 대기 중이라
     * 일부러 주석 처리해 둔 미래 안건({@code UK_ROUTE_SELECTED}). 둘 다 실제로 걸렸다.
     */
    private Set<String> parseDeclaredNames() throws IOException {
        Set<String> names = new LinkedHashSet<>();
        for (String line : Files.readAllLines(ERD, StandardCharsets.UTF_8)) {
            Matcher m = DECLARED.matcher(stripNonCode(line));
            while (m.find()) {
                String name = m.group(1);
                if (!name.startsWith(CHECK_PREFIX)) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /**
     * 코드가 아닌 부분을 지운다 — 줄 주석과 작은따옴표 문자열.
     *
     * 문자열까지 지우는 이유: 컬럼 COMMENT 에 'INSERT ... ON DUPLICATE KEY UPDATE' 처럼
     * SQL 문법을 설명하는 문구가 들어 있어, 그대로 두면 UPDATE 를 인덱스 이름으로 잡는다.
     */
    private static String stripNonCode(String line) {
        String withoutLiterals = STRING_LITERAL.matcher(line).replaceAll("");
        int at = withoutLiterals.indexOf("--");
        return at < 0 ? withoutLiterals : withoutLiterals.substring(0, at);
    }

    /** 인덱스 이름(UNIQUE·일반)과 제약 이름(FK 포함)을 모두 모은다. */
    private Set<String> readActualNames() {
        List<String> indexes = jdbcTemplate.queryForList("""
                select index_name from information_schema.statistics
                 where table_schema = database()
                """, String.class);
        List<String> constraints = jdbcTemplate.queryForList("""
                select constraint_name from information_schema.table_constraints
                 where table_schema = database()
                """, String.class);

        Set<String> all = new LinkedHashSet<>(indexes);
        all.addAll(constraints);
        return all;
    }
}
