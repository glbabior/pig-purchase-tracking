package com.pigpurchases.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pigpurchases.model.StatementImport;
import com.pigpurchases.model.StatementSource;
import com.pigpurchases.repository.StatementImportRepository;
import com.pigpurchases.repository.StatementSourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Mapping screen's table payload.
 *
 * <p>The screen shows an icon for the statement file, not its name, and the whole path lives
 * in the icon's hover text — so the path has to arrive with the row. An icon that launches
 * something has to be able to say what it will launch before it is clicked.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MappingFilesTest {

    @Autowired private MockMvc mvc;
    @Autowired private StatementSourceRepository sourceRepo;
    @Autowired private StatementImportRepository importRepo;

    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void reset() {
        importRepo.deleteAll();
        sourceRepo.deleteAll();
    }

    @Test
    void eachStatementCarriesTheWholePathToTheFileOnDisk() throws Exception {
        StatementSource source = sourceRepo.save(new StatementSource("Crestline", "C:\\statements\\crestline"));
        importRepo.save(new StatementImport(source.getId(), LocalDate.of(2026, 7, 11),
                "20260711-statements-4669-.pdf", "2026\\20260711-statements-4669-.pdf",
                LocalDateTime.of(2026, 7, 12, 9, 0), 144));

        JsonNode files = om.readTree(mvc.perform(get("/api/mapping/files"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("files");

        assertEquals(1, files.size());
        JsonNode f = files.get(0);

        // The subfolder must survive: the file name alone would name a file that is not there,
        // which is a tooltip that lies about what the button opens.
        String expected = Path.of("C:\\statements\\crestline")
                .resolve("2026\\20260711-statements-4669-.pdf").toString();
        assertEquals(expected, f.get("fullPath").asText());
        assertFalse(f.get("mapped").asBoolean(), "nothing has mapped it, so the counts are absent");
    }

    @Test
    void aStatementWithNoSubfolderStillGetsAPath() throws Exception {
        // relativePath is nullable, and falling through to the file name is what BudgetController
        // does when it actually opens the file. The tooltip has to agree with that resolution.
        StatementSource source = sourceRepo.save(new StatementSource("Bayside", "C:\\statements\\bayside"));
        importRepo.save(new StatementImport(source.getId(), LocalDate.of(2026, 7, 8),
                "july.pdf", null, LocalDateTime.of(2026, 7, 9, 9, 0), 15));

        JsonNode files = om.readTree(mvc.perform(get("/api/mapping/files"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("files");

        assertEquals(Path.of("C:\\statements\\bayside").resolve("july.pdf").toString(),
                files.get(0).get("fullPath").asText());
    }
}
