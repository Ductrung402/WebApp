package com.ezam.util;

import com.google.gson.Gson;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mindrot.jbcrypt.BCrypt;

import static org.junit.jupiter.api.Assertions.*;

/** Real cryptographic calls on synthetic fixtures; no mocks, app, network or DB. */
class PasswordCompatibilityTest {
    private static final Gson JSON = new Gson();
    private static final Fixture FIXTURE = loadFixture();
    private static final Map<String, String> JAVA_HASHES = new TreeMap<>();

    record Vector(String id, String password, String wrongPassword, int utf8Bytes, String hash) { }
    record Profile(String revision, int cost, String encoding, int maxBytes, boolean nulAllowed) { }
    record Fixture(boolean syntheticOnly, Map<String, String> generator, Profile profile,
                   List<Vector> vectors) { }

    private static Fixture loadFixture() {
        var resource = PasswordCompatibilityTest.class.getResourceAsStream(
                "/password/passlib-bcrypt-vectors.json");
        assertNotNull(resource, "Generate the checked-in synthetic fixture first.");
        try (var reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
            return JSON.fromJson(reader, Fixture.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot read synthetic test fixture.", exception);
        }
    }

    static Stream<Arguments> vectors() {
        return FIXTURE.vectors().stream().map(vector -> Arguments.of(vector.id(), vector));
    }

    @Test
    void fixtureProvenanceAndCoverageAreExplicit() {
        assertTrue(FIXTURE.syntheticOnly());
        assertEquals(Map.of("passlib", "1.7.4", "bcrypt", "4.0.1"), FIXTURE.generator());
        assertEquals(new Profile("2b", 12, "UTF-8", 72, false), FIXTURE.profile());
        assertEquals(12, FIXTURE.vectors().size());
        assertEquals(12, FIXTURE.vectors().stream().map(Vector::id).distinct().count());
        for (Vector vector : FIXTURE.vectors()) {
            assertTrue(vector.hash().startsWith("$2b$12$"), vector.id());
            assertEquals(vector.utf8Bytes(), vector.password().getBytes(StandardCharsets.UTF_8).length);
            assertTrue(vector.utf8Bytes() <= 72);
        }
    }

    @Test
    void unadaptedJbcrypt04RejectsTheActualPasslibRevision() {
        Vector vector = FIXTURE.vectors().get(0);
        assertThrows(IllegalArgumentException.class,
                () -> BCrypt.checkpw(vector.password(), vector.hash()));
    }

    @ParameterizedTest(name = "legacy correct/wrong: {0}")
    @MethodSource("vectors")
    void verifiesLegacyWithoutChangingTheStoredHash(String id, Vector vector) {
        String original = vector.hash();
        assertTrue(PasswordUtil.verifyPassword(vector.password(), original), id);
        assertFalse(PasswordUtil.verifyPassword(vector.wrongPassword(), original), id);
        assertEquals(original, vector.hash(), "Input hash must remain unchanged.");
        assertTrue(original.startsWith("$2b$12$"));
    }

    @ParameterizedTest(name = "independent body comparison: {0}")
    @MethodSource("vectors")
    void jbcryptRecomputesTheSameSaltCostAndChecksumAsPasslib(String id, Vector vector) {
        String nativeHash = BCrypt.hashpw(vector.password(), "$2a$" + vector.hash().substring(4));
        assertTrue(nativeHash.startsWith("$2a$12$"));
        assertEquals(vector.hash().substring(4), nativeHash.substring(4), id);
    }

    @ParameterizedTest(name = "new cost-12 hash: {0}")
    @MethodSource("vectors")
    void newHashesVerifyCorrectAndWrongPasswords(String id, Vector vector) {
        String hash = PasswordUtil.hashPassword(vector.password());
        assertEquals(60, hash.length());
        assertTrue(hash.startsWith("$2a$12$"));
        assertTrue(PasswordUtil.verifyPassword(vector.password(), hash), id);
        assertFalse(PasswordUtil.verifyPassword(vector.wrongPassword(), hash), id);
        JAVA_HASHES.put(id, hash);
    }

    static Stream<Arguments> malformedHashes() {
        String valid = FIXTURE.vectors().get(0).hash();
        return Stream.of(
                Arguments.of("null", null), Arguments.of("empty", ""),
                Arguments.of("truncated", valid.substring(0, 59)),
                Arguments.of("extra-character", valid + "."),
                Arguments.of("leading-space", " " + valid),
                Arguments.of("trailing-newline", valid + "\n"),
                Arguments.of("old-2", "$2$" + valid.substring(4)),
                Arguments.of("unsupported-2x", "$2x$" + valid.substring(4)),
                Arguments.of("unproven-2y", "$2y$" + valid.substring(4)),
                Arguments.of("unsupported-2c", "$2c$" + valid.substring(4)),
                Arguments.of("cost-too-low", "$2b$03$" + valid.substring(7)),
                Arguments.of("cost-31-unsupported", "$2b$31$" + valid.substring(7)),
                Arguments.of("cost-out-of-range", "$2b$99$" + valid.substring(7)),
                Arguments.of("cost-not-numeric", "$2b$xx$" + valid.substring(7)),
                Arguments.of("missing-cost-digit", "$2b$2$" + valid.substring(7)),
                Arguments.of("invalid-separator", valid.substring(0, 6) + ":" + valid.substring(7)),
                Arguments.of("invalid-alphabet", valid.substring(0, 7) + "!" + valid.substring(8)),
                Arguments.of("noncanonical-salt", valid.substring(0, 28) + "B" + valid.substring(29)),
                Arguments.of("noncanonical-checksum", valid.substring(0, 59) + "B"),
                Arguments.of("embedded-nul", valid.substring(0, 20) + '\0' + valid.substring(21)),
                Arguments.of("unrelated-hash", "not-a-bcrypt-hash"));
    }

    @ParameterizedTest(name = "malformed hash: {0}")
    @MethodSource("malformedHashes")
    void malformedHashesFailClosedWithoutThrowing(String label, String hash) {
        assertFalse(assertDoesNotThrow(() -> PasswordUtil.verifyPassword(
                FIXTURE.vectors().get(0).password(), hash)), label);
    }

    static Stream<Arguments> invalidPasswords() {
        return Stream.of(
                Arguments.of("null", null),
                Arguments.of("nul-start", "\0SyntheticPass"),
                Arguments.of("nul-middle", "Synthetic\0Pass"),
                Arguments.of("nul-end", "SyntheticPass\0"),
                Arguments.of("ascii-73-bytes", "a".repeat(73)),
                Arguments.of("utf8-74-bytes", "é".repeat(37)),
                Arguments.of("emoji-76-bytes", "🔐".repeat(19)),
                Arguments.of("utf8-crosses-72", "a".repeat(71) + "é"),
                Arguments.of("wraparound-255-bytes", "a".repeat(255)),
                Arguments.of("unpaired-high-surrogate", "Synthetic\uD800Pass"),
                Arguments.of("unpaired-low-surrogate", "Synthetic\uDC00Pass"));
    }

    @ParameterizedTest(name = "invalid input: {0}")
    @MethodSource("invalidPasswords")
    void invalidInputsAreRejectedBeforeBcrypt(String label, String password) {
        assertFalse(PasswordUtil.verifyPassword(password, FIXTURE.vectors().get(0).hash()), label);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> PasswordUtil.hashPassword(password));
        assertEquals("Unsupported password input.", error.getMessage());
    }

    @Test
    void overlengthPasswordCannotAuthenticateAsItsTruncatedPrefix() {
        String hash = FIXTURE.vectors().stream().filter(v -> v.id().equals("ascii-72-bytes"))
                .findFirst().orElseThrow().hash();
        assertTrue(PasswordUtil.verifyPassword("a".repeat(72), hash));
        assertFalse(PasswordUtil.verifyPassword("a".repeat(72) + "x", hash));
    }

    @Test
    void whitespaceIsNotTrimmed() {
        Vector vector = FIXTURE.vectors().stream().filter(v -> v.id().equals("whitespace"))
                .findFirst().orElseThrow();
        assertFalse(PasswordUtil.verifyPassword(vector.password().strip(), vector.hash()));
    }

    @Test
    void unicodeIsNotNormalized() {
        Vector nfc = FIXTURE.vectors().stream().filter(v -> v.id().equals("unicode-nfc"))
                .findFirst().orElseThrow();
        Vector nfd = FIXTURE.vectors().stream().filter(v -> v.id().equals("unicode-nfd"))
                .findFirst().orElseThrow();
        assertFalse(PasswordUtil.verifyPassword(nfd.password(), nfc.hash()));
        assertFalse(PasswordUtil.verifyPassword(nfc.password(), nfd.hash()));
    }

    @Test
    void newHashesUseFreshRandomSalts() {
        String password = FIXTURE.vectors().get(0).password();
        String first = PasswordUtil.hashPassword(password);
        String second = PasswordUtil.hashPassword(password);
        assertNotEquals(first.substring(7, 29), second.substring(7, 29));
        assertTrue(PasswordUtil.verifyPassword(password, first));
        assertTrue(PasswordUtil.verifyPassword(password, second));
    }

    @AfterAll
    static void exportSyntheticJavaHashesForIndependentPasslibVerification() throws Exception {
        if (JAVA_HASHES.size() != FIXTURE.vectors().size()) {
            throw new IllegalStateException("Incomplete synthetic Java output.");
        }
        List<Map<String, String>> vectors = new ArrayList<>();
        JAVA_HASHES.forEach((id, hash) -> vectors.add(Map.of("id", id, "hash", hash)));
        Path path = Path.of("target", "verification", "password-java-synthetic.json");
        Files.createDirectories(path.getParent());
        Files.writeString(path, JSON.toJson(Map.of("syntheticOnly", true, "vectors", vectors)),
                StandardCharsets.UTF_8);
    }
}
