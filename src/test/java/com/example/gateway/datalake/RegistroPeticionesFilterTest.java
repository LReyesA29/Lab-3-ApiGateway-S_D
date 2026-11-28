package com.example.gateway.datalake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.filter.CorsFilter;

import com.example.gateway.cache.CacheRespuestas;
import com.example.gateway.config.CorsConfigPruebas;
import com.example.gateway.config.RutasConfig;
import com.example.gateway.controller.ProxyControllerPruebas;
import com.example.gateway.security.JwtAuthenticationFilter;
import com.example.gateway.security.JwtUtil;
import com.example.gateway.security.PoliticaAcceso;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Cadena real del Gateway: RegistroPeticionesFilter -> CorsFilter -> JwtAuthenticationFilter
 * -> ProxyController, con los módulos simulados por MockRestServiceServer y la salida
 * del datalake capturada en memoria.
 */
class RegistroPeticionesFilterTest {

    private static final String SECRETO = "secreto-solo-para-pruebas-de-32-bytes";
    private static final String ESTUDIANTES = "http://estudiantes:3001";
    private static final String MATERIAS = "http://materias:3002";
    private static final String INSCRIPCIONES = "http://inscripciones:3003";
    private static final String AUTENTICACION = "http://auth:3004";
    private static final Set<String> CAMPOS = Set.of("ts", "service", "instance", "trace_id", "method", "path",
            "query", "status", "latency_ms", "user_id", "role", "idempotency_key", "error_type", "request_body");
    private static final Pattern TS = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<String> lineas = new ArrayList<>();
    private SalidaDatalake salida = (ts, json) -> lineas.add(json);
    private MockRestServiceServer modulos;
    private HttpServlet proxy;

    @BeforeEach
    void preparar() {
        RutasConfig rutas = mock(RutasConfig.class);
        when(rutas.getEstudiantesUrl()).thenReturn(ESTUDIANTES);
        when(rutas.getMateriasUrl()).thenReturn(MATERIAS);
        when(rutas.getInscripcionesUrl()).thenReturn(INSCRIPCIONES);
        when(rutas.getAutenticacionUrl()).thenReturn(AUTENTICACION);
        RestTemplate restTemplate = new RestTemplate();
        modulos = MockRestServiceServer.bindTo(restTemplate).build();
        proxy = ProxyControllerPruebas.comoServlet(rutas, mock(CacheRespuestas.class), restTemplate);
    }

    // ---------- utilidades ----------

    private MockHttpServletResponse ejecutar(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        RegistroPeticionesFilter registro = new RegistroPeticionesFilter(salida, "gateway-7f9c");
        CorsFilter cors = new CorsFilter(CorsConfigPruebas.fuente("http://localhost:4200"));
        JwtAuthenticationFilter jwt = new JwtAuthenticationFilter(new JwtUtil(SECRETO), new PoliticaAcceso());
        new MockFilterChain(proxy, registro, cors, jwt).doFilter(request, response);
        return response;
    }

    private static MockHttpServletRequest peticion(String metodo, String ruta) {
        MockHttpServletRequest request = new MockHttpServletRequest(metodo, ruta);
        request.setServletPath(ruta);
        return request;
    }

    private static MockHttpServletRequest conJson(MockHttpServletRequest request, String json) {
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(json.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private static MockHttpServletRequest conToken(MockHttpServletRequest request, String rol) {
        String token = Jwts.builder()
                .claim("id", 7)
                .claim("usuario", "ana")
                .claim("rol", rol)
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(SECRETO.getBytes(StandardCharsets.UTF_8)))
                .compact();
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    private JsonNode unicaLinea() throws Exception {
        assertEquals(1, lineas.size(), "debe haber exactamente una línea por petición: " + lineas);
        String linea = lineas.get(0);
        assertFalse(linea.contains("\n"), "la línea debe ser JSON compacto");
        JsonNode json = MAPPER.readTree(linea);
        List<String> campos = new ArrayList<>();
        json.fieldNames().forEachRemaining(campos::add);
        assertEquals(CAMPOS, Set.copyOf(campos), "todos los campos, también los null");
        return json;
    }

    // ---------- una línea con todos los campos ----------

    @Test
    void unaLineaPorPeticionConTodosLosCampos() throws Exception {
        modulos.expect(requestTo(INSCRIPCIONES + "/api/inscripciones?origen=web"))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("{\"id\":10}"));

        MockHttpServletRequest request = conToken(conJson(peticion("POST", "/api/inscripciones"),
                "{\"estudianteId\":3,\"cursoId\":5}"), "ADMIN");
        request.setQueryString("origen=web");
        request.addHeader("Idempotency-Key", "k-123");
        MockHttpServletResponse response = ejecutar(request);

        assertEquals(201, response.getStatus());
        JsonNode linea = unicaLinea();
        assertTrue(TS.matcher(linea.get("ts").asText()).matches(), linea.get("ts").asText());
        assertEquals("gateway", linea.get("service").asText());
        assertEquals("gateway-7f9c", linea.get("instance").asText());
        assertEquals(response.getHeader("X-Trace-Id"), linea.get("trace_id").asText());
        assertEquals("POST", linea.get("method").asText());
        assertEquals("/api/inscripciones", linea.get("path").asText());
        assertEquals("origen=web", linea.get("query").asText());
        assertEquals(201, linea.get("status").asInt());
        assertTrue(linea.get("latency_ms").isIntegralNumber() && linea.get("latency_ms").asLong() >= 0);
        assertEquals("7", linea.get("user_id").asText());
        assertEquals("ADMIN", linea.get("role").asText());
        assertEquals("k-123", linea.get("idempotency_key").asText());
        assertTrue(linea.get("error_type").isNull());
        assertEquals(MAPPER.readTree("{\"estudianteId\":3,\"cursoId\":5}"), linea.get("request_body"));
    }

    @Test
    void sinQueryNiCuerpoNiTokenLosCamposSonNull() throws Exception {
        modulos.expect(requestTo(AUTENTICACION + "/api/auth/login")).andRespond(withSuccess());

        ejecutar(peticion("GET", "/api/auth/login"));

        JsonNode linea = unicaLinea();
        for (String campo : List.of("query", "user_id", "role", "idempotency_key", "error_type", "request_body")) {
            assertTrue(linea.get(campo).isNull(), campo + " debe ser null");
        }
    }

    // ---------- trace_id ----------

    @Test
    void seReutilizaElTraceIdRecibidoSeDevuelveYSePropaga() throws Exception {
        modulos.expect(requestTo(ESTUDIANTES + "/api/estudiantes/3"))
                .andExpect(header("X-Trace-Id", "a1b2c3d4e5f60718"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        MockHttpServletRequest request = conToken(peticion("GET", "/api/estudiantes/3"), "ESTUDIANTE");
        request.addHeader("x-trace-id", "a1b2c3d4e5f60718");
        MockHttpServletResponse response = ejecutar(request);

        modulos.verify();
        assertEquals("a1b2c3d4e5f60718", response.getHeader("X-Trace-Id"));
        assertEquals("a1b2c3d4e5f60718", unicaLinea().get("trace_id").asText());
    }

    @Test
    void siNoLlegaTraceIdSeGeneraUnoYEseMismoSePropaga() throws Exception {
        List<String> enviados = new ArrayList<>();
        modulos.expect(requestTo(ESTUDIANTES + "/api/estudiantes/3"))
                .andExpect(req -> enviados.add(req.getHeaders().getFirst("X-Trace-Id")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        MockHttpServletResponse response = ejecutar(conToken(peticion("GET", "/api/estudiantes/3"), "ESTUDIANTE"));

        String generado = response.getHeader("X-Trace-Id");
        assertTrue(generado.matches("[0-9a-f]{16}"), generado);
        assertEquals(List.of(generado), enviados);
        assertEquals(generado, unicaLinea().get("trace_id").asText());
    }

    @Test
    void cadaPeticionSinTraceIdRecibeUnoDistinto() throws Exception {
        MockHttpServletResponse primera = ejecutar(peticion("GET", "/api/estudiantes"));
        MockHttpServletResponse segunda = ejecutar(peticion("GET", "/api/estudiantes"));
        assertNotEquals(primera.getHeader("X-Trace-Id"), segunda.getHeader("X-Trace-Id"));
    }

    @Test
    void unTraceIdRecibidoConCaracteresInvalidosSeReemplaza() throws Exception {
        MockHttpServletRequest request = peticion("GET", "/api/estudiantes");
        request.addHeader("X-Trace-Id", "<script>alert(1)</script>");
        assertTrue(ejecutar(request).getHeader("X-Trace-Id").matches("[0-9a-f]{16}"));
    }

    // ---------- identidad ----------

    @Test
    void laIdentidadSaleDelTokenYSeDescartaLaQueEnviaElCliente() throws Exception {
        modulos.expect(requestTo(ESTUDIANTES + "/api/estudiantes/3"))
                .andExpect(header("X-User-Id", "7"))
                .andExpect(header("X-User-Role", "ESTUDIANTE"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        MockHttpServletRequest request = conToken(peticion("GET", "/api/estudiantes/3"), "ESTUDIANTE");
        request.addHeader("X-User-Id", "1");
        request.addHeader("X-User-Role", "ADMIN");
        ejecutar(request);

        modulos.verify();
        JsonNode linea = unicaLinea();
        assertEquals("7", linea.get("user_id").asText());
        assertEquals("ESTUDIANTE", linea.get("role").asText());
    }

    @Test
    void sinTokenNoSeEnviaIdentidadAunqueElClienteLaFalsifique() throws Exception {
        modulos.expect(requestTo(AUTENTICACION + "/api/auth/login"))
                .andExpect(req -> assertFalse(req.getHeaders().containsHeader("X-User-Id")))
                .andExpect(req -> assertFalse(req.getHeaders().containsHeader("X-User-Role")))
                .andRespond(withSuccess("{\"token\":\"t\"}", MediaType.APPLICATION_JSON));

        MockHttpServletRequest request = conJson(peticion("POST", "/api/auth/login"), "{\"usuario\":\"a\"}");
        request.addHeader("X-User-Id", "1");
        request.addHeader("X-User-Role", "ADMIN");
        ejecutar(request);

        modulos.verify();
        assertTrue(unicaLinea().get("user_id").isNull());
    }

    // ---------- request_body ----------

    @Test
    void elCuerpoDelLoginNuncaSeGuarda() throws Exception {
        modulos.expect(requestTo(AUTENTICACION + "/api/auth/login"))
                .andExpect(content().string("{\"usuario\":\"admin\",\"clave\":\"S3creta!\"}"))
                .andRespond(withSuccess("{\"token\":\"t\"}", MediaType.APPLICATION_JSON));

        MockHttpServletResponse response = ejecutar(
                conJson(peticion("POST", "/api/auth/login"), "{\"usuario\":\"admin\",\"clave\":\"S3creta!\"}"));

        modulos.verify();
        assertEquals(200, response.getStatus());
        assertEquals("<omitido>", unicaLinea().get("request_body").asText());
        assertFalse(lineas.get(0).contains("S3creta!"));
    }

    @Test
    void elCuerpoDelRegistroYVariantesDeLaRutaTampocoSeGuardan() throws Exception {
        modulos.expect(requestTo(AUTENTICACION + "/api/auth/registro")).andRespond(withStatus(HttpStatus.CREATED));
        modulos.expect(requestTo(AUTENTICACION + "/API/Auth/login/")).andRespond(withSuccess());

        ejecutar(conToken(conJson(peticion("POST", "/api/auth/registro"), "{\"clave\":\"x1\"}"), "ADMIN"));
        ejecutar(conToken(conJson(peticion("POST", "/API/Auth//login/"), "{\"clave\":\"x2\"}"), "ADMIN"));

        assertEquals(2, lineas.size());
        lineas.forEach(linea -> assertTrue(linea.contains("\"request_body\":\"<omitido>\""), linea));
    }

    @Test
    void unMultipartGrandeLlegaIntactoAlModuloYSeRegistraComoNoJson() throws Exception {
        // Fotografía de ~40 KB: más que el límite de la copia (16 KB)
        byte[] foto = new byte[40_000];
        new java.util.Random(7).nextBytes(foto);
        byte[] multipart = multipart("foto", "foto.jpg", "image/jpeg", foto);
        modulos.expect(requestTo(ESTUDIANTES + "/api/estudiantes/3/foto"))
                .andExpect(content().bytes(multipart))
                .andExpect(header("Content-Type", org.hamcrest.Matchers.containsString("boundary=lim1t3")))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        MockHttpServletRequest request = conToken(peticion("POST", "/api/estudiantes/3/foto"), "ADMIN");
        request.setContentType("multipart/form-data; boundary=lim1t3");
        request.setContent(multipart);
        MockHttpServletResponse response = ejecutar(request);

        modulos.verify();
        assertEquals(200, response.getStatus());
        assertEquals("<no-json>", unicaLinea().get("request_body").asText());
    }

    @Test
    void unPdfDeContenidoTematicoLlegaIntactoAMaterias() throws Exception {
        byte[] pdf = ("%PDF-1.7\n" + "x".repeat(20_000) + "\n%%EOF").getBytes(StandardCharsets.ISO_8859_1);
        byte[] multipart = multipart("archivo", "tema.pdf", "application/pdf", pdf);
        modulos.expect(requestTo(MATERIAS + "/api/materias/5/contenido"))
                .andExpect(content().bytes(multipart))
                .andRespond(withStatus(HttpStatus.CREATED));

        MockHttpServletRequest request = conToken(peticion("POST", "/api/materias/5/contenido"), "ADMIN");
        request.setContentType("multipart/form-data; boundary=lim1t3");
        request.setContent(multipart);
        assertEquals(201, ejecutar(request).getStatus());

        modulos.verify();
        assertEquals("<no-json>", unicaLinea().get("request_body").asText());
    }

    @Test
    void unJsonDeMasDe16KbSeReenviaCompletoPeroNoSeGuarda() throws Exception {
        String grande = "{\"descripcion\":\"" + "a".repeat(20_000) + "\"}";
        modulos.expect(requestTo(MATERIAS + "/api/materias"))
                .andExpect(content().string(grande))
                .andRespond(withStatus(HttpStatus.CREATED));

        ejecutar(conToken(conJson(peticion("POST", "/api/materias"), grande), "ADMIN"));

        modulos.verify();
        assertEquals("<demasiado-grande>", unicaLinea().get("request_body").asText());
    }

    @Test
    void elCuerpoDeUnaPeticionRechazadaAntesDelProxyTambienSeRegistra() throws Exception {
        MockHttpServletResponse response = ejecutar(
                conJson(peticion("POST", "/api/inscripciones"), "{\"cursoId\":5}"));

        assertEquals(401, response.getStatus());
        assertEquals(MAPPER.readTree("{\"cursoId\":5}"), unicaLinea().get("request_body"));
    }

    // ---------- error_type ----------

    @Test
    void errorTypeSaleConElCodigoDeNegocioDelModulo() throws Exception {
        modulos.expect(requestTo(INSCRIPCIONES + "/api/inscripciones"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"El curso no tiene cupos\",\"errorType\":\"CupoAgotado\"}"));

        MockHttpServletResponse response = ejecutar(
                conToken(conJson(peticion("POST", "/api/inscripciones"), "{\"cursoId\":5}"), "ADMIN"));

        // La respuesta al cliente no cambia
        assertEquals(409, response.getStatus());
        assertEquals("{\"message\":\"El curso no tiene cupos\",\"errorType\":\"CupoAgotado\"}",
                response.getContentAsString());
        JsonNode linea = unicaLinea();
        assertEquals(409, linea.get("status").asInt());
        assertEquals("CupoAgotado", linea.get("error_type").asText());
    }

    @Test
    void errorTypeDelGatewayEn401Y403() throws Exception {
        ejecutar(peticion("GET", "/api/estudiantes"));
        ejecutar(conToken(peticion("DELETE", "/api/estudiantes/3"), "ESTUDIANTE"));

        assertEquals(2, lineas.size());
        assertEquals("TokenInvalido", MAPPER.readTree(lineas.get(0)).get("error_type").asText());
        assertEquals("AccesoDenegado", MAPPER.readTree(lineas.get(1)).get("error_type").asText());
    }

    @Test
    void errorTypeModuloNoDisponibleEn502() throws Exception {
        modulos.expect(requestTo(ESTUDIANTES + "/api/estudiantes"))
                .andRespond(req -> {
                    throw new java.net.ConnectException("conexión rechazada");
                });

        MockHttpServletResponse response = ejecutar(conToken(peticion("GET", "/api/estudiantes"), "ADMIN"));

        assertEquals(502, response.getStatus());
        assertEquals("ModuloNoDisponible", unicaLinea().get("error_type").asText());
    }

    @Test
    void un401DelModuloSinErrorTypeNoSeConfundeConTokenInvalido() throws Exception {
        // Credenciales incorrectas: el 401 lo da Autenticación, no el filtro JWT
        modulos.expect(requestTo(AUTENTICACION + "/api/auth/login"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("{\"message\":\"Credenciales inválidas\"}"));

        ejecutar(conJson(peticion("POST", "/api/auth/login"), "{\"usuario\":\"a\",\"clave\":\"b\"}"));

        assertTrue(unicaLinea().get("error_type").isNull());
    }

    // ---------- registrar nunca rompe la petición ----------

    @Test
    void unErrorAlEscribirElLogNoCambiaLaRespuesta() throws Exception {
        salida = (ts, json) -> {
            throw new IllegalStateException("disco lleno");
        };
        modulos.expect(requestTo(INSCRIPCIONES + "/api/inscripciones"))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("{\"id\":10}"));

        MockHttpServletResponse response = ejecutar(
                conToken(conJson(peticion("POST", "/api/inscripciones"), "{\"cursoId\":5}"), "ADMIN"));

        modulos.verify();
        assertEquals(201, response.getStatus());
        assertEquals("{\"id\":10}", response.getContentAsString());
        assertTrue(response.getHeader("X-Trace-Id").matches("[0-9a-f]{16}"));
    }

    @Test
    void unaExcepcionDelControladorSeRegistraComo500YSeRelanza() throws Exception {
        MockHttpServletRequest request = peticion("GET", "/api/estudiantes");
        RegistroPeticionesFilter registro = new RegistroPeticionesFilter(salida, null);
        HttpServlet roto = new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) {
                throw new IllegalStateException("fallo inesperado");
            }
        };

        assertThrows(IllegalStateException.class,
                () -> new MockFilterChain(roto, registro).doFilter(request, new MockHttpServletResponse()));
        JsonNode linea = unicaLinea();
        assertEquals(500, linea.get("status").asInt());
        assertEquals("local", linea.get("instance").asText());
    }

    // ---------- rutas sin registro ----------

    @Test
    void saludYDocumentacionNoSeRegistranPeroLlevanTraceId() throws Exception {
        for (String ruta : List.of("/actuator/health", "/actuator/health/liveness", "/health",
                "/swagger-ui/index.html", "/v3/api-docs", "/api-docs")) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            new MockFilterChain(new HttpServlet() {
            }, new RegistroPeticionesFilter(salida, null)).doFilter(peticion("GET", ruta), response);
            assertTrue(response.getHeader("X-Trace-Id") != null, ruta);
        }
        assertTrue(lineas.isEmpty(), lineas.toString());
    }

    // ---------- helpers ----------

    private static byte[] multipart(String campo, String archivo, String tipo, byte[] datos) throws Exception {
        java.io.ByteArrayOutputStream salida = new java.io.ByteArrayOutputStream();
        salida.write(("--lim1t3\r\nContent-Disposition: form-data; name=\"" + campo + "\"; filename=\"" + archivo
                + "\"\r\nContent-Type: " + tipo + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        salida.write(datos);
        salida.write("\r\n--lim1t3--\r\n".getBytes(StandardCharsets.US_ASCII));
        return salida.toByteArray();
    }
}
