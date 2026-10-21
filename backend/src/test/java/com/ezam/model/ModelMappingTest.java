package com.ezam.model;

import com.google.gson.Gson;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.*;

/** Fixture derives independently from models.py AST and an actual read-only catalog inspection. */
class ModelMappingTest {
    private static final Baseline BASELINE = load();
    record Column(String column, String property, String javaType, boolean nullable,
                  boolean primaryKey, String foreignKey, int sourceLine, String ormDefault,
                  String serverDefault, String postgresType) { }
    record Table(String tableName, String className, List<Column> columns) { }
    record EnumType(String className, String postgresType, List<String> labels) { }
    record Baseline(String legacySha256, boolean catalogVerifiedReadOnly, List<Table> tables,
                    List<EnumType> enums, List<Map<String, String>> constraints,
                    List<Map<String, Object>> indexes) { }

    static Baseline load() {
        var input = ModelMappingTest.class.getResourceAsStream("/model/schema-baseline.json");
        assertNotNull(input);
        try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            return new Gson().fromJson(reader, Baseline.class);
        } catch (Exception error) {
            throw new IllegalStateException("Missing mapping evidence.", error);
        }
    }

    static Stream<Arguments> tables() {
        return BASELINE.tables().stream().map(table -> Arguments.of(table.className(), table));
    }

    static Stream<Arguments> columns() {
        return BASELINE.tables().stream().flatMap(table -> table.columns().stream()
                .map(column -> Arguments.of(table.className() + "." + column.property(), table, column)));
    }

    @Test
    void fixtureHasExactlyTheSixVerifiedTablesAnd38Columns() {
        assertTrue(BASELINE.catalogVerifiedReadOnly());
        assertEquals("d225dca61619e02e2697b4caed497e3ebed6badbfa2d771b37cf02750721530f", BASELINE.legacySha256());
        assertEquals(Set.of("giao_vien", "de_thi", "cau_hoi", "phong_thi", "hoc_sinh", "ket_qua_thi"),
                BASELINE.tables().stream().map(Table::tableName).collect(Collectors.toSet()));
        assertEquals(38, BASELINE.tables().stream().mapToInt(table -> table.columns().size()).sum());
    }

    @ParameterizedTest(name = "bean columns only: {0}")
    @MethodSource("tables")
    void beansHaveOnlyColumnPropertiesAndPublicNoArgConstructors(String name, Table table) throws Exception {
        Class<?> type = Class.forName("com.ezam.model." + name);
        assertTrue(Serializable.class.isAssignableFrom(type));
        assertNotNull(type.getConstructor().newInstance());
        Set<String> expected = table.columns().stream().map(Column::property).collect(Collectors.toSet());
        var descriptors = Introspector.getBeanInfo(type, Object.class).getPropertyDescriptors();
        assertEquals(expected, Arrays.stream(descriptors).map(PropertyDescriptor::getName).collect(Collectors.toSet()));
        assertEquals(expected, Arrays.stream(type.getDeclaredFields()).filter(field -> !Modifier.isStatic(field.getModifiers()))
                .peek(field -> assertTrue(Modifier.isPrivate(field.getModifiers())))
                .map(java.lang.reflect.Field::getName).collect(Collectors.toSet()));
        // Inherited Object.toString does not disclose password/hash or other row data.
        assertEquals(Object.class, type.getMethod("toString").getDeclaringClass());
    }

    @ParameterizedTest(name = "type and nullable roundtrip: {0}")
    @MethodSource("columns")
    void propertiesUseExactTypesAndPreserveValuesAndNulls(String label, Table table, Column column) throws Exception {
        Class<?> type = Class.forName("com.ezam.model." + table.className());
        Object bean = type.getConstructor().newInstance();
        PropertyDescriptor property = new PropertyDescriptor(column.property(), type);
        Class<?> expectedType = Class.forName(column.javaType());
        assertEquals(expectedType, property.getPropertyType());
        assertNotNull(property.getReadMethod());
        assertNotNull(property.getWriteMethod());
        assertNull(property.getReadMethod().invoke(bean), "Unpopulated bean must not fabricate insert defaults.");
        Object sample = expectedType == String.class ? "  Giá trị thử 🔐  "
                : expectedType == Integer.class ? Integer.valueOf(30)
                : expectedType == Double.class ? Double.valueOf(6.67)
                : expectedType == LocalDate.class ? LocalDate.of(2024, 2, 29)
                : expectedType.getEnumConstants()[0];
        property.getWriteMethod().invoke(bean, sample);
        assertEquals(sample, property.getReadMethod().invoke(bean));
        property.getWriteMethod().invoke(bean, new Object[] {null});
        assertNull(property.getReadMethod().invoke(bean));
        if (column.column().startsWith("id_")) assertEquals(String.class, expectedType);
        assertTrue(column.sourceLine() > 0);
        assertNull(column.serverDefault(), "Live catalog has no server defaults; insert values are application-owned.");
    }

    @Test
    void enumLabelsMatchThePostgresTypesExactly() throws Exception {
        assertEquals(2, BASELINE.enums().size());
        for (EnumType source : BASELINE.enums()) {
            Class<?> type = Class.forName("com.ezam.model." + source.className());
            assertEquals(source.labels(), Arrays.stream(type.getEnumConstants()).map(value -> ((Enum<?>) value).name()).toList());
            assertEquals(source.className().toLowerCase(java.util.Locale.ROOT), source.postgresType());
        }
    }

    @Test
    void catalogDoesNotInventCascadeOrStudentResultUniqueness() {
        assertEquals(6, BASELINE.constraints().stream().filter(row -> row.get("constraint_type").equals("p")).count());
        assertEquals(7, BASELINE.constraints().stream().filter(row -> row.get("constraint_type").equals("f")).count());
        assertEquals(2, BASELINE.constraints().stream().filter(row -> row.get("constraint_type").equals("u")).count());
        assertTrue(BASELINE.constraints().stream().noneMatch(row -> row.get("definition").contains("CASCADE")));
        assertTrue(BASELINE.indexes().stream().filter(row -> row.get("table_name").equals("ket_qua_thi"))
                .allMatch(row -> row.get("definition").toString().contains("(id_kq)")));
    }
}
