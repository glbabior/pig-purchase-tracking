package com.pigpurchases.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The origin check and the response headers.
 *
 * <p>Both halves matter and they fail differently. Too loose and any web page the user has
 * open can drive the app; too tight and the app cannot drive itself, which would be obvious
 * immediately but is worth pinning anyway so nobody "hardens" it into uselessness later.
 *
 * <p>{@code /api/backup/now} is the probe: it takes no request body, so it is one of the
 * endpoints a cross-origin form POST could actually reach, which makes it the honest test
 * case rather than a convenient one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LocalOriginFilterTest {

    @Autowired private MockMvc mvc;

    // ---- what must be refused ----------------------------------------------

    @Test
    void aPostFromAnotherSiteIsRefused() throws Exception {
        mvc.perform(post("/api/backup/now").header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aRefererFromAnotherSiteIsRefusedWhenThereIsNoOrigin() throws Exception {
        // A form POST from a page that sends Referer but no Origin must not slip through the
        // gap between the two headers.
        mvc.perform(post("/api/backup/now").header("Referer", "https://evil.example/page.html"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aHostThatMerelyLooksLocalIsRefused() throws Exception {
        // "localhost.evil.example" contains "localhost". Matching on the parsed HOST rather
        // than on the string is what makes that a non-issue, so pin it.
        mvc.perform(post("/api/backup/now").header("Origin", "https://localhost.evil.example"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/backup/now").header("Origin", "https://notlocalhost"))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteFromAnotherSiteIsRefusedToo() throws Exception {
        // Cross-origin DELETE cannot be sent by a form today, so this is defence against the
        // check being narrowed to POST later on the grounds that "only POST is reachable".
        mvc.perform(delete("/api/debug-log").header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden());
    }

    // ---- what must still work ----------------------------------------------

    @Test
    void theAppsOwnRequestsGoThrough() throws Exception {
        mvc.perform(post("/api/backup/now").header("Origin", "http://localhost:8080"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/backup/now").header("Referer", "http://127.0.0.1:8080/"))
                .andExpect(status().isOk());
    }

    @Test
    void aRequestWithNeitherHeaderIsAllowed() throws Exception {
        // curl, scripts, the project's own tooling. A browser will not omit both headers for a
        // page-initiated state change, so this concedes nothing to the attacker in scope —
        // and refusing it would break legitimate local use.
        mvc.perform(post("/api/backup/now")).andExpect(status().isOk());
    }

    @Test
    void readsAreNotChecked() throws Exception {
        // A GET changes nothing, and the same-origin policy already stops another page reading
        // the response. Checking it would only break links.
        mvc.perform(get("/api/settings").header("Origin", "https://evil.example"))
                .andExpect(status().isOk());
    }

    // ---- headers ------------------------------------------------------------

    @Test
    void everyResponseRefusesToBeFramed() throws Exception {
        // Framing is how a hostile page reaches the DELETEs and PUTs the origin check blocks —
        // by borrowing the user's clicks instead of sending its own request.
        mvc.perform(get("/api/settings"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Content-Security-Policy",
                        org.hamcrest.Matchers.containsString("frame-ancestors 'none'")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void theContentPolicyKeepsInjectedCodeFromPhoningHome() throws Exception {
        // The point of the CSP here is not script-src — the frontend is one inline script by
        // design. It is connect-src: even a successful injection has nowhere off-machine to
        // send what it reads, which is the guarantee the whole project rests on.
        mvc.perform(get("/api/settings"))
                .andExpect(header().string("Content-Security-Policy",
                        org.hamcrest.Matchers.containsString("connect-src 'self'")));
    }
}
