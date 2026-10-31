package com.example.gateway.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.CorsFilter;

import com.example.gateway.security.JwtAuthenticationFilter;
import com.example.gateway.security.JwtUtil;
import com.example.gateway.security.PoliticaAcceso;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

class CorsConfigTest {

    private static final String SECRETO = "secreto-solo-para-pruebas-de-32-bytes";
    private static final String FRONTEND = "http://localhost:4200";

    private final CorsConfig corsConfig = new CorsConfig(FRONTEND + "/ , https://app.ejemplo.com");
    private final CorsFilter corsFilter = new CorsFilter(corsConfig.fuente());
    private final JwtAuthenticationFilter jwtFilter =
            new JwtAuthenticationFilter(new JwtUtil(SECRETO), new PoliticaAcceso());

    /** Cadena real del servidor: CorsFilter (máxima prioridad) -> JwtAuthenticationFilter -> controlador. */
    private MockHttpServletResponse ejecutar(MockHttpServletRequest request, AtomicBoolean llegoAlControlador)
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        HttpServlet controlador = new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) {
                llegoAlControlador.set(true);
                resp.setStatus(200);
            }
        };
        new MockFilterChain(controlador, corsFilter, jwtFilter).doFilter(request, response);
        return response;
    }

    private static MockHttpServletRequest peticion(String metodo, String ruta, String origen) {
        MockHttpServletRequest request = new MockHttpServletRequest(metodo, ruta);
        request.setServletPath(ruta);
        if (origen != null) {
            request.addHeader("Origin", origen);
        }
        return request;
    }

    private static MockHttpServletRequest preflight(String origen, String metodo) {
        MockHttpServletRequest request = peticion("OPTIONS", "/api/inscripciones/1", origen);
        request.addHeader("Access-Control-Request-Method", metodo);
        request.addHeader("Access-Control-Request-Headers", "authorization, content-type");
        return request;
    }

    private static String token(String rol) {
        return Jwts.builder()
                .claim("usuario", "prueba")
                .claim("rol", rol)
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(SECRETO.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    // ---------- Preflight ----------

    @Test
    void preflightDelFrontendSeRespondeSinTokenConLasCabecerasCors() throws Exception {
        AtomicBoolean controlador = new AtomicBoolean();
        MockHttpServletResponse response = ejecutar(preflight(FRONTEND, "DELETE"), controlador);

        assertEquals(200, response.getStatus());
        assertEquals(FRONTEND, response.getHeader("Access-Control-Allow-Origin"));
        assertTrue(response.getHeader("Access-Control-Allow-Methods").contains("DELETE"));
        String cabeceras = response.getHeader("Access-Control-Allow-Headers").toLowerCase();
        assertTrue(cabeceras.contains("authorization") && cabeceras.contains("content-type"));
        assertEquals("3600", response.getHeader("Access-Control-Max-Age"));
        // El preflight se responde en el CorsFilter: no llega al proxy ni a los microservicios
        assertFalse(controlador.get());
    }

    @Test
    void preflightDeOtroOrigenSeRechaza() throws Exception {
        MockHttpServletResponse response = ejecutar(preflight("https://sitio-malicioso.com", "GET"), new AtomicBoolean());
        assertEquals(403, response.getStatus());
        assertNull(response.getHeader("Access-Control-Allow-Origin"));
    }

    @Test
    void preflightConMetodoNoPermitidoSeRechaza() throws Exception {
        assertEquals(403, ejecutar(preflight(FRONTEND, "PATCH"), new AtomicBoolean()).getStatus());
    }

    @Test
    void elFiltroJwtDejaPasarElPreflightAunqueNoHayaCorsFilterAntes() throws Exception {
        MockFilterChain cadena = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();
        jwtFilter.doFilter(preflight(FRONTEND, "POST"), response, cadena);

        assertEquals(200, response.getStatus());
        assertTrue(cadena.getRequest() != null, "el preflight debe continuar la cadena sin exigir token");
    }

    // ---------- Peticiones reales desde el navegador ----------

    @Test
    void elOtroOrigenConfiguradoTambienSeAcepta() throws Exception {
        MockHttpServletResponse response = ejecutar(preflight("https://app.ejemplo.com", "PUT"), new AtomicBoolean());
        assertEquals(200, response.getStatus());
        assertEquals("https://app.ejemplo.com", response.getHeader("Access-Control-Allow-Origin"));
    }

    @Test
    void sinTokenResponde401ConCabecerasCorsParaQueElFrontendLoLea() throws Exception {
        AtomicBoolean controlador = new AtomicBoolean();
        MockHttpServletResponse response = ejecutar(peticion("GET", "/api/estudiantes", FRONTEND), controlador);

        assertEquals(401, response.getStatus());
        assertEquals(FRONTEND, response.getHeader("Access-Control-Allow-Origin"));
        assertFalse(controlador.get());
    }

    @Test
    void conTokenValidoLlegaAlControladorConCabecerasCors() throws Exception {
        MockHttpServletRequest request = peticion("POST", "/api/inscripciones", FRONTEND);
        request.addHeader("Authorization", "Bearer " + token("ADMIN"));
        AtomicBoolean controlador = new AtomicBoolean();
        MockHttpServletResponse response = ejecutar(request, controlador);

        assertTrue(controlador.get());
        assertEquals(200, response.getStatus());
        assertEquals(FRONTEND, response.getHeader("Access-Control-Allow-Origin"));
    }

    @Test
    void peticionDeOtroOrigenSeRechazaAntesDelControlador() throws Exception {
        MockHttpServletRequest request = peticion("GET", "/api/estudiantes", "https://sitio-malicioso.com");
        request.addHeader("Authorization", "Bearer " + token("ADMIN"));
        AtomicBoolean controlador = new AtomicBoolean();

        assertEquals(403, ejecutar(request, controlador).getStatus());
        assertFalse(controlador.get());
    }

    @Test
    void sinOrigenNoAplicaCors() throws Exception {
        // curl, otros servicios o el proxy de nginx (mismo origen): CORS no interviene
        MockHttpServletRequest request = peticion("GET", "/api/estudiantes", null);
        request.addHeader("Authorization", "Bearer " + token("ESTUDIANTE"));
        AtomicBoolean controlador = new AtomicBoolean();
        MockHttpServletResponse response = ejecutar(request, controlador);

        assertTrue(controlador.get());
        assertNull(response.getHeader("Access-Control-Allow-Origin"));
    }

    // ---------- Configuración ----------

    @Test
    void normalizaLosOrigenesDeFrontendUrl() {
        assertEquals(List.of(FRONTEND, "https://app.ejemplo.com"),
                CorsConfig.crearConfiguracion(" http://localhost:4200/ ,https://app.ejemplo.com,").getAllowedOrigins());
        assertEquals(CorsConfig.METODOS_PERMITIDOS,
                CorsConfig.crearConfiguracion(FRONTEND).getAllowedMethods());
    }

    @Test
    void frontendUrlVacioOComodinImpideArrancar() {
        assertThrows(IllegalStateException.class, () -> new CorsConfig(" "));
        assertThrows(IllegalStateException.class, () -> new CorsConfig("*"));
    }
}
