package com.ezam.baseline;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs after package; inspects the actual WAR without starting Tomcat or a DB. */
class WarPackagingIT {
    private Path warPath() {
        String configured = System.getProperty("ezam.war.path");
        assertNotNull(configured, "Run with Maven verify so the packaged WAR is available.");
        Path path = Path.of(configured);
        assertTrue(Files.isRegularFile(path), "Packaged WAR must exist.");
        assertEquals("ROOT.war", path.getFileName().toString());
        return path;
    }

    @Test
    void packagedDescriptorUsesServlet4AndAllowsAnnotations() throws Exception {
        try (ZipFile war = new ZipFile(warPath().toFile())) {
            ZipEntry descriptor = war.getEntry("WEB-INF/web.xml");
            assertNotNull(descriptor);
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            try (InputStream input = war.getInputStream(descriptor)) {
                Element webApp = factory.newDocumentBuilder().parse(input).getDocumentElement();
                assertEquals("http://xmlns.jcp.org/xml/ns/javaee", webApp.getNamespaceURI());
                assertEquals("4.0", webApp.getAttribute("version"));
                assertEquals("false", webApp.getAttribute("metadata-complete"));
                assertEquals("UTF-8", webApp.getElementsByTagNameNS(
                        webApp.getNamespaceURI(), "request-character-encoding").item(0).getTextContent());
                assertEquals("UTF-8", webApp.getElementsByTagNameNS(
                        webApp.getNamespaceURI(), "response-character-encoding").item(0).getTextContent());
            }
        }
    }

    @Test
    void runtimeLibrariesHavePinnedVersionsWithoutServletOrTestLibraries() throws Exception {
        Set<String> expected = Set.of(
                "postgresql-42.7.2.jar", "HikariCP-5.1.0.jar", "gson-2.10.1.jar",
                "jbcrypt-0.4.jar", "jjwt-api-0.11.5.jar", "jjwt-impl-0.11.5.jar",
                "jjwt-jackson-0.11.5.jar", "netty-socketio-2.0.13.jar");
        try (ZipFile war = new ZipFile(warPath().toFile())) {
            List<String> libraries = war.stream().map(ZipEntry::getName)
                    .filter(name -> name.startsWith("WEB-INF/lib/") && name.endsWith(".jar"))
                    .map(name -> name.substring("WEB-INF/lib/".length())).toList();
            assertTrue(libraries.containsAll(expected), "Pinned runtime libraries must be packaged.");
            assertEquals(1, libraries.stream().filter(name -> name.startsWith("netty-socketio-")).count(),
                    "No stale Socket.IO server version may remain in the WAR.");
            for (String library : libraries) {
                assertFalse(library.startsWith("javax.servlet-api-")
                        || library.startsWith("jakarta.servlet-api-"), library);
                assertFalse(library.startsWith("junit-") || library.startsWith("junit-platform-")
                        || library.startsWith("mockito-") || library.startsWith("opentest4j-")
                        || library.startsWith("apiguardian-"), library);
            }
        }
    }

    @Test
    void warDoesNotBundleLocalSecretsRuntimeOrTestSources() throws Exception {
        try (ZipFile war = new ZipFile(warPath().toFile())) {
            for (ZipEntry entry : war.stream().toList()) {
                String name = entry.getName().replace('\\', '/').toLowerCase(Locale.ROOT);
                String padded = "/" + name;
                String basename = name.substring(name.lastIndexOf('/') + 1);
                assertFalse(basename.equals(".env") || basename.startsWith(".env."), name);
                assertFalse(padded.contains("/.runtime/") || padded.contains("/.git/")
                        || padded.contains("/.tools/") || padded.contains("/src/test/"), name);
                assertFalse(name.endsWith(".py") || name.endsWith(".pyc")
                        || name.endsWith(".p12") || name.endsWith(".pfx")
                        || name.endsWith(".pem") || basename.equals(".gitkeep"), name);
                assertFalse(name.startsWith("web-inf/classes/com/ezam/baseline/"), name);
            }
        }
    }
}
