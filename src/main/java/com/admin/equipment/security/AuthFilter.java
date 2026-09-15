package com.admin.equipment.security;

import com.admin.equipment.model.AppUser;
import com.admin.equipment.repo.AppUserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 简单的 Bearer Token 鉴权过滤器：保护 /api/** ，放行登录与健康检查。 */
@Component
public class AuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final AppUserRepository userRepo;

    public AuthFilter(JwtUtil jwtUtil, AppUserRepository userRepo) {
        this.jwtUtil = jwtUtil;
        this.userRepo = userRepo;
    }

    private boolean isPublic(String path) {
        return path.equals("/api/health") || path.equals("/api/auth/login");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/") || isPublic(path)) {
            chain.doFilter(request, response);
            return;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            writeUnauthorized(response, "未提供登录凭证");
            return;
        }
        Long userId = jwtUtil.parseUserId(header.substring(7));
        if (userId == null) {
            writeUnauthorized(response, "登录状态无效或已过期");
            return;
        }
        AppUser user = userRepo.findById(userId).orElse(null);
        if (user == null) {
            writeUnauthorized(response, "用户不存在");
            return;
        }
        request.setAttribute("currentUser", user);
        chain.doFilter(request, response);
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"detail\":\"" + message + "\"}");
    }
}
