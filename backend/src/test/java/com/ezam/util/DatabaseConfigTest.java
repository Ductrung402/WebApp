package com.ezam.util;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseConfigTest {
    static Stream<Arguments> validUrls() {
        return Stream.of(
                Arguments.of("postgres-alias", "postgres://fixture:synthetic@db.example/exam_test",
                        "jdbc:postgresql://db.example:5432/exam_test", "fixture", "synthetic"),
                Arguments.of("postgresql", "postgresql://fixture:synthetic@localhost:5433/exam_test",
                        "jdbc:postgresql://localhost:5433/exam_test", "fixture", "synthetic"),
                Arguments.of("sqlalchemy-driver", "postgresql+psycopg2://fixture:synthetic@db.example/exam_test",
                        "jdbc:postgresql://db.example:5432/exam_test", "fixture", "synthetic"),
                Arguments.of("jdbc-userinfo", "jdbc:postgresql://fixture:synthetic@db.example/exam_test",
                        "jdbc:postgresql://db.example:5432/exam_test", "fixture", "synthetic"),
                Arguments.of("jdbc-query", "jdbc:postgresql://db.example/exam_test?user=fixture&password=synthetic",
                        "jdbc:postgresql://db.example:5432/exam_test", "fixture", "synthetic"),
                Arguments.of("escaped-credentials", "postgresql://tea%2Bcher:p%40ss%3A%2F%3F%23%25%2B@db.example/exam_test",
                        "jdbc:postgresql://db.example:5432/exam_test", "tea+cher", "p@ss:/?#%+"),
                Arguments.of("literal-plus", "postgresql://fixture:p+ass@db.example/exam_test",
                        "jdbc:postgresql://db.example:5432/exam_test", "fixture", "p+ass"),
                Arguments.of("utf8-credentials", "postgresql://gi%C3%A1o:th%E1%BB%AD@db.example/exam_test",
                        "jdbc:postgresql://db.example:5432/exam_test", "giáo", "thử"),
                Arguments.of("ipv6", "postgresql://fixture:synthetic@[::1]:5433/exam_test",
                        "jdbc:postgresql://[::1]:5433/exam_test", "fixture", "synthetic"),
                Arguments.of("escaped-database", "postgresql://fixture:synthetic@db.example/exam%20test",
                        "jdbc:postgresql://db.example:5432/exam%20test", "fixture", "synthetic"),
                Arguments.of("query-form-plus", "postgresql://db.example/exam_test?user=fixture&password=with+space%2B",
                        "jdbc:postgresql://db.example:5432/exam_test", "fixture", "with space+"));
    }

    @ParameterizedTest(name = "URL conversion: {0}")
    @MethodSource("validUrls")
    void convertsUrlsWithCredentialsOnlyInSeparateProperties(String label, String url,
            String expectedUrl, String expectedUser, String expectedPassword) {
        DatabaseConfig config = DatabaseConfig.fromEnvironment(Map.of("DATABASE_URL", url));
        assertEquals(expectedUrl, config.getJdbcUrl());
        assertEquals(expectedUser, config.toConnectionProperties().getProperty("user"));
        assertEquals(expectedPassword, config.toConnectionProperties().getProperty("password"));
        assertFalse(config.getJdbcUrl().contains("@"));
        assertFalse(config.getJdbcUrl().contains("?"));
        assertEquals("DatabaseConfig[redacted]", config.toString());
    }

    static Stream<Arguments> invalidUrls() {
        String base = "postgresql://fixture:synthetic@db.example";
        return Stream.of(
                Arguments.of("empty", ""), Arguments.of("blank", "  "),
                Arguments.of("mysql", "mysql://fixture:synthetic@db.example/exam_test"),
                Arguments.of("sqlite", "sqlite:///exam_test"),
                Arguments.of("opaque-jdbc", "jdbc:postgresql:exam_test"),
                Arguments.of("missing-host", "postgresql:///exam_test?user=fixture"),
                Arguments.of("missing-user", "postgresql://db.example/exam_test"),
                Arguments.of("blank-user", "postgresql://:synthetic@db.example/exam_test"),
                Arguments.of("missing-path", base), Arguments.of("empty-database", base + "/"),
                Arguments.of("extra-path", base + "/exam_test/other"),
                Arguments.of("raw-space", base + "/exam test"),
                Arguments.of("fragment", base + "/exam_test#password"),
                Arguments.of("bad-port", "postgresql://fixture:synthetic@db.example:abc/exam_test"),
                Arguments.of("port-zero", "postgresql://fixture:synthetic@db.example:0/exam_test"),
                Arguments.of("port-too-large", "postgresql://fixture:synthetic@db.example:65536/exam_test"),
                Arguments.of("bad-escape", "postgresql://fixture:synthetic%ZZ@db.example/exam_test"),
                Arguments.of("bad-utf8", "postgresql://fixture:%FF@db.example/exam_test"),
                Arguments.of("nul", "postgresql://fixture:synthetic%00@db.example/exam_test"),
                Arguments.of("newline", "postgresql://fixture:synthetic%0A@db.example/exam_test"),
                Arguments.of("duplicate-user", base + "/exam_test?user=other"),
                Arguments.of("duplicate-password", base + "/exam_test?password=other"),
                Arguments.of("duplicate-option", base + "/exam_test?sslmode=require&sslmode=disable"),
                Arguments.of("unsupported-option", base + "/exam_test?unknown=synthetic"),
                Arguments.of("empty-query", base + "/exam_test?"),
                Arguments.of("no-equals", base + "/exam_test?sslmode"),
                Arguments.of("empty-pair", base + "/exam_test?sslmode=require&"));
    }

    @ParameterizedTest(name = "safe rejection: {0}")
    @MethodSource("invalidUrls")
    void invalidConfigurationNeverEchoesTheInputOrParserCause(String label, String url) {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> DatabaseConfig.fromEnvironment(Map.of("DATABASE_URL", url)));
        assertEquals("Invalid or missing database configuration.", error.getMessage());
        assertNull(error.getCause());
        assertEquals(0, error.getSuppressed().length);
        assertFalse(error.toString().contains("synthetic"));
    }

    @Test
    void missingEnvironmentFailsSafely() {
        for (var error : List.of(
                assertThrows(IllegalStateException.class, () -> DatabaseConfig.fromEnvironment(Map.of())),
                assertThrows(IllegalStateException.class, () -> DatabaseConfig.fromEnvironment(null)))) {
            assertEquals("Invalid or missing database configuration.", error.getMessage());
            assertNull(error.getCause());
        }
    }

    @Test
    void preservesTlsTimeoutAndSchemaPropertiesOutsideTheJdbcUrl() {
        var config = DatabaseConfig.fromEnvironment(Map.of("DATABASE_URL",
                "postgresql://fixture:synthetic@db.example/exam_test?sslmode=verify-full"
                        + "&connectTimeout=5&socketTimeout=10&currentSchema=public&ApplicationName=EZam+test"));
        Properties props = config.toConnectionProperties();
        assertEquals("verify-full", props.getProperty("sslmode"));
        assertEquals("5", props.getProperty("connectTimeout"));
        assertEquals("10", props.getProperty("socketTimeout"));
        assertEquals("public", props.getProperty("currentSchema"));
        assertEquals("EZam test", props.getProperty("ApplicationName"));
        assertFalse(config.getJdbcUrl().contains("?"));
    }

    @Test
    void environmentFallbackIsExplicitAndUrlCredentialsTakePrecedence() {
        var config = DatabaseConfig.fromEnvironment(Map.of("DATABASE_URL", "postgresql://db.example/exam_test",
                "PGUSER", "fixture", "PGPASSWORD", "fallback"));
        assertEquals("fixture", config.toConnectionProperties().getProperty("user"));
        assertEquals("fallback", config.toConnectionProperties().getProperty("password"));
        var urlWins = DatabaseConfig.fromEnvironment(Map.of("DATABASE_URL", "postgresql://url:chosen@db.example/exam_test",
                "PGUSER", "unused", "PGPASSWORD", "unused"));
        assertEquals("url", urlWins.toConnectionProperties().getProperty("user"));
        assertEquals("chosen", urlWins.toConnectionProperties().getProperty("password"));
    }

    @Test
    void aPasswordIsNotRequiredForCertificateOrTrustAuthentication() {
        var config = DatabaseConfig.fromEnvironment(Map.of("DATABASE_URL", "postgresql://fixture@db.example/exam_test"));
        assertFalse(config.toConnectionProperties().containsKey("password"));
    }

    @Test
    void returnedPropertiesAndEnvironmentCannotMutateConfiguration() {
        Map<String, String> environment = new HashMap<>(Map.of("DATABASE_URL", "postgresql://fixture:synthetic@db.example/exam_test"));
        var config = DatabaseConfig.fromEnvironment(environment);
        environment.put("DATABASE_URL", "not-a-url");
        var properties = config.toConnectionProperties();
        properties.setProperty("password", "changed");
        properties.setProperty("sslmode", "disable");
        assertEquals("synthetic", config.toConnectionProperties().getProperty("password"));
        assertFalse(config.toConnectionProperties().containsKey("sslmode"));
    }
}
