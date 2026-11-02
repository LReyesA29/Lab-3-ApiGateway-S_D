package com.example.gateway.security;

import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.List;

/**
 * Reglas de autorización (RBAC) del Gateway:
 * - Rutas públicas: login y salud, sin token.
 * - ADMIN: cualquier método sobre cualquier ruta.
 * - ESTUDIANTE: solo GET sobre los microservicios y el detalle de estudiante.
 */
@Component
public class PoliticaAcceso {

    public static final String ROL_ADMIN = "ADMIN";
    public static final String ROL_ESTUDIANTE = "ESTUDIANTE";

    private static final List<PathPattern> RUTAS_PUBLICAS = compilar(
            "/api/auth/login",
            "/actuator/health",
            "/actuator/health/**");

    private static final List<PathPattern> RUTAS_LECTURA_ESTUDIANTE = compilar(
            "/api/estudiantes/**",
            "/api/materias/**",
            "/api/inscripciones/**",
            "/api/estudiantes/{id}/detalle");

    public boolean esPublica(String ruta) {
        return coincide(RUTAS_PUBLICAS, ruta);
    }

    public boolean permite(String rol, String metodo, String ruta) {
        if (ROL_ADMIN.equals(rol)) {
            return true;
        }
        if (ROL_ESTUDIANTE.equals(rol)) {
            return HttpMethod.GET.matches(metodo) && coincide(RUTAS_LECTURA_ESTUDIANTE, ruta);
        }
        return false;
    }

    private static boolean coincide(List<PathPattern> patrones, String ruta) {
        PathContainer camino = PathContainer.parsePath(ruta);
        return patrones.stream().anyMatch(patron -> patron.matches(camino));
    }

    private static List<PathPattern> compilar(String... patrones) {
        PathPatternParser parser = new PathPatternParser();
        return List.of(patrones).stream().map(parser::parse).toList();
    }
}
