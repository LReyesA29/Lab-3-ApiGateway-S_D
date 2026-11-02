package com.example.gateway.security;

import java.io.IOException;

import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Valida el JWT del header Authorization y aplica las reglas de PoliticaAcceso:
 * 401 si falta el token o es inválido/expirado, 403 si el rol no tiene permiso
 * para el método y la ruta. No depende de Spring Security completo.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String ATRIBUTO_USUARIO = "usuario";
    public static final String ATRIBUTO_ROL = "rol";

    private static final String PREFIJO_BEARER = "Bearer ";

    private final JwtUtil jwtUtil;
    private final PoliticaAcceso politicaAcceso;

    public JwtAuthenticationFilter(JwtUtil jwtUtil, PoliticaAcceso politicaAcceso) {
        this.jwtUtil = jwtUtil;
        this.politicaAcceso = politicaAcceso;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // Ruta ya decodificada y normalizada por el contenedor: evita que algo como
        // "/actuator/health/../api/..." pase por una ruta pública
        String ruta = rutaNormalizada(request);

        // Preflight CORS (OPTIONS con Origin y Access-Control-Request-Method): el navegador
        // nunca envía token en él. Normalmente ya lo respondió el CorsFilter (CorsConfig)
        if (CorsUtils.isPreFlightRequest(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (politicaAcceso.esPublica(ruta)) {
            filterChain.doFilter(request, response);
            return;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(PREFIJO_BEARER)) {
            responder(response, HttpServletResponse.SC_UNAUTHORIZED, "Falta el header Authorization: Bearer <token>");
            return;
        }

        Claims claims;
        try {
            claims = jwtUtil.validar(header.substring(PREFIJO_BEARER.length()).trim());
        } catch (ExpiredJwtException ex) {
            responder(response, HttpServletResponse.SC_UNAUTHORIZED, "Token expirado");
            return;
        } catch (JwtException | IllegalArgumentException ex) {
            responder(response, HttpServletResponse.SC_UNAUTHORIZED, "Token inválido o mal formado");
            return;
        }

        Object rol = claims.get(ATRIBUTO_ROL);
        if (!(rol instanceof String rolTexto) || !politicaAcceso.permite(rolTexto, request.getMethod(), ruta)) {
            responder(response, HttpServletResponse.SC_FORBIDDEN, "No tiene permisos para realizar esta operación");
            return;
        }

        request.setAttribute(ATRIBUTO_USUARIO, claims.get(ATRIBUTO_USUARIO));
        request.setAttribute(ATRIBUTO_ROL, rolTexto);
        filterChain.doFilter(request, response);
    }

    private String rutaNormalizada(HttpServletRequest request) {
        String pathInfo = request.getPathInfo();
        return request.getServletPath() + (pathInfo != null ? pathInfo : "");
    }

    private void responder(HttpServletResponse response, int status, String mensaje) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.getWriter().write("{\"error\": \"" + mensaje + "\"}");
    }
}
