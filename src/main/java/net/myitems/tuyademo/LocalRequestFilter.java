package net.myitems.tuyademo;

import java.io.IOException;
import java.util.Set;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Prevent a foreign web page from issuing actions against a loopback service. */
@Component
public class LocalRequestFilter extends OncePerRequestFilter {
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        res.setHeader("Cache-Control", "no-store");
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("Referrer-Policy", "no-referrer");
        res.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
        String host = req.getServerName();
        String origin = req.getHeader("Origin");
        boolean trustedHost = Set.of("localhost", "127.0.0.1", "[::1]").contains(host);
        boolean trustedOrigin = origin == null || origin.equals("http://" + req.getHeader("Host"));
        boolean mutation = !Set.of("GET", "HEAD", "OPTIONS").contains(req.getMethod());
        if (!trustedHost || !trustedOrigin || (mutation && !"1".equals(req.getHeader("X-Local-Request")))) {
            res.setStatus(403); res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write("{\"error\":{\"code\":\"ORIGIN\",\"message\":\"Откройте приложение по локальному адресу.\"}}");
            return;
        }
        chain.doFilter(req, res);
    }
}
