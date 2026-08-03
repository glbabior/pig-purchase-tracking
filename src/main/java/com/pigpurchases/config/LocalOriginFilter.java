package com.pigpurchases.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

/**
 * Stops another web page from driving this app, and from framing it.
 *
 * <p>The app has no login, because it is one person on their own machine and a password would
 * only be theatre. But "no login" and "no protection" are not the same thing, and they were
 * being treated as the same thing. A page on any other origin cannot READ a response from
 * localhost — the same-origin policy handles that — but nothing stopped it SENDING a request.
 * A plain HTML form needs no preflight, so any site the browser had open could quietly hit
 * this app's endpoints with the user's own machine.
 *
 * <p>What that reached was not trivial. Spending Claude API credit on every visit, forcing a
 * restore commit, launching a file through the shell, and — quietest of all — clearing the
 * backup baseline whose entire job is to notice that the database has collapsed. None of it
 * shows on screen.
 *
 * <h2>How the check works</h2>
 *
 * <p>A browser attaches {@code Origin} to a cross-origin state-changing request, including a
 * form POST, and attaches {@code Referer} to anything a page initiated. So a foreign page
 * always identifies itself in one header or the other. If either says a host that is not this
 * machine, the request is refused.
 *
 * <p>When BOTH headers are absent the request is allowed. That is not a gap being shrugged at:
 * a browser will not omit both for a page-initiated state change, so what is left is a
 * non-browser client — {@code curl}, a script, the app's own tooling — and something already
 * executing code on this machine has far better options than this endpoint. Refusing those
 * would break legitimate local use to defend against an attacker who does not need the door.
 *
 * <p>Any loopback host is accepted, port included, rather than just this app's own port. A
 * hostile page cannot forge {@code Origin}, so accepting {@code localhost:3000} concedes
 * nothing to the attacker this exists to stop.
 *
 * <p>GET and HEAD are not checked. They change nothing, and the same-origin policy already
 * stops another page reading what comes back.
 */
@Component
@Order(1)
public class LocalOriginFilter extends OncePerRequestFilter {

    /** Methods that can change something. GET/HEAD/OPTIONS cannot. */
    private static final Set<String> STATE_CHANGING = Set.of("POST", "PUT", "PATCH", "DELETE");

    /** Hostnames that mean "this machine". */
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "::1", "[::1]");

    /**
     * Sent on every response, including GETs.
     *
     * <p>{@code frame-ancestors} and {@code X-Frame-Options} close the clickjacking route,
     * which matters more here than usual: framing would give a hostile page the DELETEs and
     * PUTs that the origin check above already puts out of reach, by borrowing the user's own
     * clicks instead of sending its own requests.
     *
     * <p>{@code 'unsafe-inline'} is present because the frontend is one inline script by
     * design. It is not a loophole being tolerated so much as an accurate description: the
     * value here is in {@code connect-src 'self'} and {@code default-src 'self'}, which mean
     * that even a successful injection has nowhere off-machine to send what it steals — the
     * same guarantee the rest of this project is built around.
     */
    private static final String CSP = String.join("; ",
            "default-src 'self'",
            "script-src 'self' 'unsafe-inline'",
            "style-src 'self' 'unsafe-inline'",
            "img-src 'self' data:",
            "connect-src 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'",
            "base-uri 'none'");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        response.setHeader("Content-Security-Policy", CSP);
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("X-Content-Type-Options", "nosniff");
        // Do not leak which screen the user was on to anything they navigate out to.
        response.setHeader("Referrer-Policy", "same-origin");

        if (STATE_CHANGING.contains(request.getMethod()) && !fromThisMachine(request)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "Refused: this request did not come from the app running on this machine.");
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * True unless a header positively says the request came from somewhere else.
     *
     * <p>{@code Origin} is checked first because it is the header a browser attaches
     * specifically to say where a state-changing request came from. {@code Referer} is the
     * fallback for anything that sends one and no {@code Origin}.
     */
    private boolean fromThisMachine(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.isBlank() && !"null".equals(origin)) {
            return isLoopback(origin);
        }
        String referer = request.getHeader("Referer");
        if (referer != null && !referer.isBlank()) {
            return isLoopback(referer);
        }
        return true;
    }

    /** A URL whose host is this machine. Anything unparseable is treated as foreign. */
    private static boolean isLoopback(String url) {
        try {
            String host = new URI(url).getHost();
            return host != null && LOOPBACK.contains(host.toLowerCase());
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
