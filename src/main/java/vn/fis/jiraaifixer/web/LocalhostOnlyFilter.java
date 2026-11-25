package vn.fis.jiraaifixer.web;

import java.io.IOException;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Chặn request có Host khác localhost (chống DNS rebinding): app này chạy Claude với quyền sửa code
 * và giữ phiên Jira, chỉ dành cho người dùng trên chính máy này.
 */
@Component
public class LocalhostOnlyFilter extends OncePerRequestFilter {

    private static final Set<String> ALLOWED_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!ALLOWED_HOSTS.contains(request.getServerName())) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Chỉ truy cập qua localhost");
            return;
        }
        chain.doFilter(request, response);
    }
}
