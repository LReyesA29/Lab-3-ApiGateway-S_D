package com.example.gateway.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

class JwtAuthenticationFilterTest {

    private static final String SECRETO = "secreto-solo-para-pruebas-de-32-bytes";
    private static final String OTRO_SECRETO = "otro-secreto-distinto-de-32-bytes-xx";

    private final JwtAuthenticationFilter filtro =
            new JwtAuthenticationFilter(new JwtUtil(SECRETO), new PoliticaAcceso());

    // ---------- Rutas públicas ----------

    @ParameterizedTest
    @CsvSource({ "POST, /api/auth/login", "GET, /actuator/health", "GET, /actuator/health/liveness" })
    void rutasPublicasNoExigenToken(String metodo, String ruta) throws Exception {
        assertEquals(200, ejecutar(metodo, ruta, null).getStatus());
    }

    // ---------- 401: sin token, mal formado, firma ajena, expirado ----------

    @Test
    void sinTokenResponde401() throws Exception {
        assertEquals(401, ejecutar("GET", "/api/estudiantes", null).getStatus());
    }

    @Test
    void headerSinBearerResponde401() throws Exception {
        MockHttpServletRequest request = peticion("GET", "/api/estudiantes");
        request.addHeader("Authorization", "Basic YWRtaW46YWRtaW4=");
        assertEquals(401, filtrar(request).getStatus());
    }

    @Test
    void tokenMalFormadoResponde401() throws Exception {
        assertEquals(401, ejecutar("GET", "/api/estudiantes", "esto.no.es-un-jwt").getStatus());
    }

    @Test
    void tokenFirmadoConOtroSecretoResponde401() throws Exception {
        String token = token("ADMIN", OTRO_SECRETO, 60_000);
        assertEquals(401, ejecutar("GET", "/api/estudiantes", token).getStatus());
    }

    @Test
    void tokenExpiradoResponde401() throws Exception {
        MockHttpServletResponse response = ejecutar("GET", "/api/estudiantes", token("ADMIN", SECRETO, -60_000));
        assertEquals(401, response.getStatus());
        assertEquals("{\"error\": \"Token expirado\"}", response.getContentAsString(StandardCharsets.UTF_8));
    }

    // ---------- ESTUDIANTE ----------

    @ParameterizedTest
    @CsvSource({
            "/api/estudiantes",
            "/api/estudiantes/6501f8a2c9d4e5f6a7b8c9d0",
            "/api/estudiantes/6501f8a2c9d4e5f6a7b8c9d0/detalle",
            "/api/materias/cursos/1",
            "/api/inscripciones" })
    void estudiantePuedeHacerGetALosMicroservicios(String ruta) throws Exception {
        assertEquals(200, ejecutar("GET", ruta, token("ESTUDIANTE", SECRETO, 60_000)).getStatus());
    }

    @ParameterizedTest
    @CsvSource({
            "POST, /api/inscripciones",
            "PUT, /api/estudiantes/1",
            "PATCH, /api/materias/cursos/1",
            "DELETE, /api/inscripciones/1",
            "GET, /api/cursos/1/detalle" })
    void estudianteSinPermisoResponde403(String metodo, String ruta) throws Exception {
        assertEquals(403, ejecutar(metodo, ruta, token("ESTUDIANTE", SECRETO, 60_000)).getStatus());
    }

    // ---------- ADMIN ----------

    @ParameterizedTest
    @CsvSource({
            "GET, /api/cursos/1/detalle",
            "POST, /api/inscripciones",
            "PUT, /api/estudiantes/1",
            "PATCH, /api/materias/cursos/1",
            "DELETE, /api/inscripciones/1" })
    void adminTieneAccesoTotal(String metodo, String ruta) throws Exception {
        assertEquals(200, ejecutar(metodo, ruta, token("ADMIN", SECRETO, 60_000)).getStatus());
    }

    // ---------- Rol ausente o desconocido ----------

    @Test
    void tokenSinRolResponde403() throws Exception {
        assertEquals(403, ejecutar("GET", "/api/estudiantes", token(null, SECRETO, 60_000)).getStatus());
    }

    @Test
    void rolDesconocidoResponde403() throws Exception {
        assertEquals(403, ejecutar("GET", "/api/estudiantes", token("DOCENTE", SECRETO, 60_000)).getStatus());
    }

    // ---------- Datos del token disponibles para los controladores ----------

    @Test
    void peticionAutorizadaExponeUsuarioYRol() throws Exception {
        MockHttpServletRequest request = peticion("GET", "/api/estudiantes");
        request.addHeader("Authorization", "Bearer " + token("ESTUDIANTE", SECRETO, 60_000));
        MockFilterChain cadena = new MockFilterChain();
        filtro.doFilter(request, new MockHttpServletResponse(), cadena);

        assertNotNull(cadena.getRequest());
        assertEquals("estudiante", request.getAttribute(JwtAuthenticationFilter.ATRIBUTO_USUARIO));
        assertEquals("ESTUDIANTE", request.getAttribute(JwtAuthenticationFilter.ATRIBUTO_ROL));
    }

    @Test
    void peticionRechazadaNoLlegaAlControlador() throws Exception {
        MockFilterChain cadena = new MockFilterChain();
        filtro.doFilter(peticion("GET", "/api/estudiantes"), new MockHttpServletResponse(), cadena);
        assertNull(cadena.getRequest());
    }

    // ---------- Configuración del secreto ----------

    @Test
    void secretoCortoImpideArrancar() {
        assertThrows(IllegalStateException.class, () -> new JwtUtil("corto"));
    }

    // ---------- Utilidades ----------

    private MockHttpServletResponse ejecutar(String metodo, String ruta, String token) throws Exception {
        MockHttpServletRequest request = peticion(metodo, ruta);
        if (token != null) {
            request.addHeader("Authorization", "Bearer " + token);
        }
        return filtrar(request);
    }

    private MockHttpServletResponse filtrar(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filtro.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private MockHttpServletRequest peticion(String metodo, String ruta) {
        MockHttpServletRequest request = new MockHttpServletRequest(metodo, ruta);
        // Con el DispatcherServlet en "/", el servletPath es la ruta completa normalizada
        request.setServletPath(ruta);
        return request;
    }

    /** Mismo formato que emite el módulo de Autenticación: { id, usuario, rol } en HS256. */
    private String token(String rol, String secreto, long vigenciaMs) {
        Date ahora = new Date();
        JwtBuilder builder = Jwts.builder()
                .claim("id", 2)
                .claim("usuario", "estudiante")
                .issuedAt(ahora)
                .expiration(new Date(ahora.getTime() + vigenciaMs))
                .signWith(Keys.hmacShaKeyFor(secreto.getBytes(StandardCharsets.UTF_8)));
        if (rol != null) {
            builder.claim("rol", rol);
        }
        return builder.compact();
    }
}
