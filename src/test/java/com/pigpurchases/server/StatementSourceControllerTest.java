package com.pigpurchases.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer tests for the REST endpoints, running the full app against an
 * in-memory H2. Covers settings (annual -> derived monthly), statement-source
 * CRUD, and the key privacy property: parserRules never leaks to the list, while
 * its excludeFromSpend carve-outs are surfaced as `exclusions`.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StatementSourceControllerTest {

    @Autowired
    private MockMvc mvc;

    private final ObjectMapper om = new ObjectMapper();

    private String json(Object value) throws Exception {
        return om.writeValueAsString(value);
    }

    @Test
    void settingsDeriveMonthlyFromAnnual() throws Exception {
        mvc.perform(get("/api/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthlyAllowance").value("0.00"));

        mvc.perform(put("/api/settings").contentType(APPLICATION_JSON)
                        .content(json(Map.of("annualBudget", "120000.00"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthlyAllowance").value("10000.00"));

        mvc.perform(get("/api/settings"))
                .andExpect(jsonPath("$.monthlyAllowance").value("10000.00"));
    }

    @Test
    void sourceCrudSurfacesExclusionsButHidesParserRules() throws Exception {
        // Create.
        String created = mvc.perform(post("/api/statement-sources").contentType(APPLICATION_JSON)
                        .content(json(Map.of("name", "Test Bank", "folderPath", "C:\\stmts\\test"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Test Bank"))
                .andExpect(jsonPath("$.folderPath").value("C:\\stmts\\test"))
                .andExpect(jsonPath("$.exclusions").isArray())
                .andReturn().getResponse().getContentAsString();
        long id = om.readTree(created).get("id").asLong();

        // List: exclusions empty, parserRules absent.
        JsonNode row = findByName("Test Bank");
        assertNotNull(row);
        assertFalse(row.has("parserRules"), "list must not expose parserRules");
        assertTrue(row.get("exclusions").isEmpty());

        // Attach parser rules with a spend exclusion.
        String rules = json(Map.of("excludeFromSpend",
                java.util.List.of(Map.of("contains", "FOO", "reason", "bar"))));
        mvc.perform(put("/api/statement-sources/" + id + "/parser-rules").contentType(APPLICATION_JSON)
                        .content(json(Map.of("parserRules", rules))))
                .andExpect(status().isOk());

        // List now surfaces the exclusion; still no parserRules.
        row = findByName("Test Bank");
        assertFalse(row.has("parserRules"));
        JsonNode exclusions = row.get("exclusions");
        assertEquals(1, exclusions.size());
        assertEquals("FOO", exclusions.get(0).get("contains").asText());
        assertEquals("bar", exclusions.get(0).get("reason").asText());

        // Delete.
        mvc.perform(delete("/api/statement-sources/" + id)).andExpect(status().isOk());
        assertNull(findByName("Test Bank"));
    }

    @Test
    void fileBrowserListsFoldersAndPdfsWithinTheSource(@TempDir Path folder) throws Exception {
        // temp source folder: a subdir with a PDF, plus a non-PDF that must be hidden
        Files.createDirectories(folder.resolve("2026"));
        Files.createFile(folder.resolve("2026").resolve("stmt.pdf"));
        Files.createFile(folder.resolve("notes.txt"));

        String created = mvc.perform(post("/api/statement-sources").contentType(APPLICATION_JSON)
                        .content(json(Map.of("name", "Browse Test", "folderPath", folder.toString()))))
                .andReturn().getResponse().getContentAsString();
        long id = om.readTree(created).get("id").asLong();

        // Root: shows the "2026" directory, hides notes.txt.
        String root = mvc.perform(get("/api/statement-sources/" + id + "/files"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.atRoot").value(true))
                .andReturn().getResponse().getContentAsString();
        JsonNode entries = om.readTree(root).get("entries");
        assertEquals(1, entries.size());
        assertEquals("2026", entries.get(0).get("name").asText());
        assertEquals("dir", entries.get(0).get("type").asText());

        // Into 2026: shows the PDF.
        mvc.perform(get("/api/statement-sources/" + id + "/files").param("relPath", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.atRoot").value(false))
                .andExpect(jsonPath("$.entries[0].name").value("stmt.pdf"))
                .andExpect(jsonPath("$.entries[0].type").value("file"));

        // Path traversal is rejected.
        mvc.perform(get("/api/statement-sources/" + id + "/files").param("relPath", "../.."))
                .andExpect(status().is4xxClientError());
    }

    private JsonNode findByName(String name) throws Exception {
        String list = mvc.perform(get("/api/statement-sources")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (JsonNode node : om.readTree(list)) {
            if (name.equals(node.path("name").asText())) {
                return node;
            }
        }
        return null;
    }
}
