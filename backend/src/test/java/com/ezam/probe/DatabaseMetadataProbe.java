package com.ezam.probe;

import com.ezam.util.DatabaseConfig;
import com.google.gson.GsonBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Opt-in catalog-only JDBC probe; no pool/DAO or business-row access. */
public final class DatabaseMetadataProbe {
    private DatabaseMetadataProbe() { }

    public static void main(String[] args) throws Exception {
        java.util.logging.LogManager.getLogManager().reset(); // Driver logs must not disclose endpoints/credentials.
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("task", "T1.1");
        result.put("businessRowsRead", false);
        int exit = 2;
        try {
            DatabaseConfig config = DatabaseConfig.fromEnvironment();
            Properties properties = config.toConnectionProperties();
            properties.setProperty("connectTimeout", "5");
            properties.setProperty("socketTimeout", "10");
            properties.setProperty("options", "-c default_transaction_read_only=on -c statement_timeout=5000");
            try (Connection connection = DriverManager.getConnection(config.getJdbcUrl(), properties)) {
                connection.setAutoCommit(false);
                connection.setReadOnly(true);
                try {
                    try (var statement = connection.createStatement(); var rows = statement.executeQuery("SHOW transaction_read_only")) {
                        rows.next();
                        result.put("transactionReadOnly", rows.getString(1));
                        if (!"on".equals(rows.getString(1))) throw new IllegalStateException();
                    }
                    List<Map<String, Object>> columns = new ArrayList<>();
                    var metadata = connection.getMetaData();
                    result.put("driverVersion", metadata.getDriverVersion());
                    for (String table : List.of("giao_vien", "de_thi", "cau_hoi", "phong_thi", "hoc_sinh", "ket_qua_thi")) {
                        String escaped = table.replace("_", metadata.getSearchStringEscape() + "_");
                        try (var rows = metadata.getColumns(null, "public", escaped, null)) {
                            while (rows.next()) {
                                if (!table.equals(rows.getString("TABLE_NAME"))) throw new IllegalStateException();
                                Map<String, Object> column = new LinkedHashMap<>();
                                column.put("table", table);
                                column.put("column", rows.getString("COLUMN_NAME"));
                                column.put("type", rows.getString("TYPE_NAME"));
                                column.put("nullable", rows.getInt("NULLABLE") == java.sql.DatabaseMetaData.columnNullable);
                                column.put("serverDefault", rows.getString("COLUMN_DEF"));
                                columns.add(column);
                            }
                        }
                    }
                    if (columns.size() != 38) throw new IllegalStateException();
                    result.put("columns", columns);
                    result.put("status", "READ");
                } finally {
                    connection.rollback();
                    result.put("transactionEnded", "ROLLBACK");
                }
            }
            result.put("connectionClosed", true);
            exit = 0;
        } catch (Exception error) {
            result.put("status", "NOT_VERIFIED");
            result.put("reason", error.getClass().getSimpleName()); // Never exception message, URL, properties or cause.
        }
        Path output = Path.of("target/verification/T1_1_jdbc_catalog.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, new GsonBuilder().serializeNulls().setPrettyPrinting().create().toJson(result), StandardCharsets.UTF_8);
        System.out.println("JDBC catalog probe status=" + result.get("status"));
        System.exit(exit);
    }
}
