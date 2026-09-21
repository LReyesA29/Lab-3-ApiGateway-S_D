package com.example.gateway.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Filtro que exige un JWT válido en el header Authorization para cualquier ruta
 * bajo /api/**, excepto el login. No depende de Spring Security completo.
 */
@Component
public class JwtFilter extends OncePerRequestFilter {

    @Autowired
    private JwtUtil jwtUtil;

    private static final String RUTA_LOGIN = "/api/auth/login";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        if (RUTA_LOGIN.equals(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            responderNoAutorizado(response, "Falta el header Authorization: Bearer <token>");
            return;
        }

        String token = header.substring(7);
        try {
            jwtUtil.validarYObtenerUsuario(token);
        } catch (Exception ex) {
            responderNoAutorizado(response, "Token inválido o expirado");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void responderNoAutorizado(HttpServletResponse response, String mensaje) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\": \"" + mensaje + "\"}");
    }
}
