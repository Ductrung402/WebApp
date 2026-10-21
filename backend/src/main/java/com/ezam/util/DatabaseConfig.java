package com.ezam.util;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** Immutable environment configuration only: does not load .env, open JDBC or create a pool. */
public final class DatabaseConfig {
    private static final String INVALID = "Invalid or missing database configuration.";
    private static final Set<String> QUERY_PROPERTIES = Set.of(
            "user", "password", "ssl", "sslmode", "sslrootcert", "sslcert", "sslkey", "sslpassword",
            "currentSchema", "connectTimeout", "socketTimeout", "ApplicationName", "tcpKeepAlive",
            "options", "targetServerType", "channelBinding");

    private final String jdbcUrl;
    private final Properties connectionProperties;

    private DatabaseConfig(String jdbcUrl, Properties properties) {
        this.jdbcUrl = jdbcUrl;
        this.connectionProperties = new Properties();
        this.connectionProperties.putAll(properties);
    }

    public static DatabaseConfig fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    /** DATABASE_URL wins; PGUSER/PGPASSWORD are optional fallbacks for credential-free URLs. */
    public static DatabaseConfig fromEnvironment(Map<String, String> environment) {
        try {
            if (environment == null) throw new IllegalArgumentException();
            String value = environment.get("DATABASE_URL");
            if (value == null || value.isBlank()) throw new IllegalArgumentException();
            String input = value.strip();
            if (input.startsWith("jdbc:")) input = input.substring(5);
            if (input.startsWith("postgresql+psycopg2://")) {
                input = "postgresql://" + input.substring("postgresql+psycopg2://".length());
            }
            URI uri = new URI(input);
            if (!("postgresql".equals(uri.getScheme()) || "postgres".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getFragment() != null
                    || uri.getRawPath() == null || uri.getRawPath().length() <= 1
                    || !uri.getRawPath().startsWith("/") || uri.getRawPath().substring(1).contains("/")) {
                throw new IllegalArgumentException();
            }
            int port = uri.getPort() == -1 ? 5432 : uri.getPort();
            if (port < 1 || port > 65535) throw new IllegalArgumentException();
            // Validate path encoding but retain escaped path for the JDBC driver to decode once.
            String database = decode(uri.getRawPath().substring(1), false);
            if (database.isBlank()) throw new IllegalArgumentException();
            Properties properties = new Properties();
            if (uri.getRawUserInfo() != null) {
                String raw = uri.getRawUserInfo();
                int separator = raw.indexOf(':');
                putUnique(properties, "user", decode(separator < 0 ? raw : raw.substring(0, separator), false));
                if (separator >= 0) putUnique(properties, "password", decode(raw.substring(separator + 1), false));
            }
            if (uri.getRawQuery() != null) {
                if (uri.getRawQuery().isEmpty()) throw new IllegalArgumentException();
                for (String pair : uri.getRawQuery().split("&", -1)) {
                    int separator = pair.indexOf('=');
                    if (separator <= 0) throw new IllegalArgumentException();
                    String key = decode(pair.substring(0, separator), true);
                    if (!QUERY_PROPERTIES.contains(key)) throw new IllegalArgumentException();
                    putUnique(properties, key, decode(pair.substring(separator + 1), true));
                }
            }
            for (String[] entry : new String[][] {{"user", "PGUSER"}, {"password", "PGPASSWORD"}}) {
                if (!properties.containsKey(entry[0]) && environment.get(entry[1]) != null) {
                    putUnique(properties, entry[0], environment.get(entry[1]));
                }
            }
            if (properties.getProperty("user", "").isBlank()) throw new IllegalArgumentException();
            // A password may be absent for TLS client-certificate/trust authentication.
            // Authentication itself belongs to JDBC/server, not this URL parser.
            String host = uri.getHost();
            if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
            String address = new URI("postgresql://" + host + ":" + port + uri.getRawPath()).toASCIIString();
            return new DatabaseConfig("jdbc:" + address, properties);
        } catch (Exception invalidConfiguration) {
            // URI/parser causes may embed the complete URL: do not attach them or log input.
            throw new IllegalStateException(INVALID);
        }
    }

    private static void putUnique(Properties properties, String key, String value) {
        if (properties.containsKey(key) || value == null || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException();
        }
        properties.setProperty(key, value);
    }

    /** Percent decode exactly once, preserving '+' in URI userinfo and UTF-8 strictly. */
    private static String decode(String raw, boolean formQuery) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int index = 0; index < raw.length();) {
            char next = raw.charAt(index);
            if (next == '%') {
                if (index + 2 >= raw.length()) throw new IllegalArgumentException();
                int high = Character.digit(raw.charAt(index + 1), 16);
                int low = Character.digit(raw.charAt(index + 2), 16);
                if (high < 0 || low < 0) throw new IllegalArgumentException();
                bytes.write(high * 16 + low);
                index += 3;
            } else {
                int codePoint = raw.codePointAt(index);
                bytes.writeBytes((formQuery && next == '+' ? " " : new String(Character.toChars(codePoint)))
                        .getBytes(StandardCharsets.UTF_8));
                index += Character.charCount(codePoint);
            }
        }
        String decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        if (decoded.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
        return decoded;
    }

    /** No userinfo, password, or query parameters in the returned JDBC URL. Never log config. */
    public String getJdbcUrl() {
        return jdbcUrl;
    }

    /** Contains credentials for the connection owner; never log or serialize these properties. */
    public Properties toConnectionProperties() {
        Properties copy = new Properties();
        copy.putAll(connectionProperties);
        return copy;
    }

    @Override
    public String toString() {
        return "DatabaseConfig[redacted]";
    }
}
