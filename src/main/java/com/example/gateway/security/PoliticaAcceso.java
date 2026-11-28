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
 * - ESTUDIANTE: GET sobre los microservicios y el detalle de estudiante, y además
 *   inscribirse (POST /api/inscripciones) y cancelar una inscripción
 *   (DELETE /api/inscripciones/{id}). Cualquier otra escritura es solo de ADMIN.
 * <p>
 * Limitación: el Gateway no comprueba que el estudianteId del cuerpo (ni la
 * inscripción que se cancela) sea del usuario del token, porque las cuentas de
 * Autenticación no están vinculadas a los estudiantes. Un ESTUDIANTE puede, por
 * tanto, inscribir o cancelar en nombre de otro estudiante.
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

    // Escrituras del ESTUDIANTE: patrones exactos (un solo segmento para el id), de modo
    // que p. ej. DELETE /api/inscripciones/1/notas o POST /api/inscripciones/lote no entran
    private static final List<PathPattern> INSCRIBIRSE = compilar("/api/inscripciones");
    private static final List<PathPattern> CANCELAR_INSCRIPCION = compilar("/api/inscripciones/{id}");

    public boolean esPublica(String ruta) {
        return coincide(RUTAS_PUBLICAS, ruta);
    }

    public boolean permite(String rol, String metodo, String ruta) {
        if (ROL_ADMIN.equals(rol)) {
            return true;
        }
        if (ROL_ESTUDIANTE.equals(rol)) {
            if (HttpMethod.GET.matches(metodo)) {
                return coincide(RUTAS_LECTURA_ESTUDIANTE, ruta);
            }
            if (HttpMethod.POST.matches(metodo)) {
                return coincide(INSCRIBIRSE, ruta);
            }
            if (HttpMethod.DELETE.matches(metodo)) {
                return coincide(CANCELAR_INSCRIPCION, ruta);
            }
            return false;
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
