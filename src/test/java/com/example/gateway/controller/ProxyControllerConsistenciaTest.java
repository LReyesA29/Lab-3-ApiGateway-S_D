package com.example.gateway.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import com.example.gateway.cache.CacheRespuestas;
import com.example.gateway.config.RutasConfig;

/**
 * Reenvío de Idempotency-Key / Idempotent-Replayed y consistencia del caché de
 * Materias tras escribir en Inscripciones. Los módulos se simulan con
 * MockRestServiceServer y Redis con un mock de CacheRespuestas.
 */
class ProxyControllerConsistenciaTest {

    private static final String INSCRIPCIONES = "http://inscripciones:3003";
    private static final String MATERIAS = "http://materias:3002";
    private static final String CUERPO = "{\"estudianteId\":1,\"cursoId\":7}";

    private final RutasConfig rutas = mock(RutasConfig.class);
    private final CacheRespuestas cache = mock(CacheRespuestas.class);
    private MockRestServiceServer modulos;
    private ProxyController proxy;

    @BeforeEach
    void preparar() {
        when(rutas.getInscripcionesUrl()).thenReturn(INSCRIPCIONES);
        when(rutas.getMateriasUrl()).thenReturn(MATERIAS);
        RestTemplate restTemplate = new RestTemplate();
        modulos = MockRestServiceServer.bindTo(restTemplate).build();
        proxy = new ProxyController(rutas, cache, restTemplate);
    }

    private static MockHttpServletRequest inscripcion(String metodo, String ruta, String claveIdempotencia) {
        MockHttpServletRequest request = new MockHttpServletRequest(metodo, ruta);
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(CUERPO.getBytes(StandardCharsets.UTF_8));
        if (claveIdempotencia != null) {
            request.addHeader("Idempotency-Key", claveIdempotencia);
        }
        return request;
    }

    // ---------- Cabeceras de idempotencia ----------

    @Test
    void seReenviaIdempotencyKeyAlModulo() {
        modulos.expect(requestTo(INSCRIPCIONES + "/api/inscripciones"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "8c1f-clave-unica"))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("{\"id\":10}"));

        ResponseEntity<String> respuesta = proxy.proxyInscripciones(
                inscripcion("POST", "/api/inscripciones", "8c1f-clave-unica"));

        modulos.verify();
        assertEquals(HttpStatus.CREATED, respuesta.getStatusCode());
        assertEquals("{\"id\":10}", respuesta.getBody());
    }

    @Test
    void sinIdempotencyKeyNoSeInventaLaCabecera() {
        modulos.expect(requestTo(INSCRIPCIONES + "/api/inscripciones"))
                .andExpect(headerDoesNotExist("Idempotency-Key"))
                .andRespond(withStatus(HttpStatus.CREATED).body("{}"));

        proxy.proxyInscripciones(inscripcion("POST", "/api/inscripciones", null));

        modulos.verify();
    }

    @Test
    void seDevuelveIdempotentReplayedAlCliente() {
        HttpHeaders delModulo = new HttpHeaders();
        delModulo.set("Idempotent-Replayed", "true");
        delModulo.set("X-Interna-Del-Modulo", "no-debe-salir");
        modulos.expect(requestTo(INSCRIPCIONES + "/api/inscripciones"))
                .andRespond(withStatus(HttpStatus.CREATED).headers(delModulo).body("{\"id\":10}"));

        ResponseEntity<String> respuesta = proxy.proxyInscripciones(
                inscripcion("POST", "/api/inscripciones", "8c1f-clave-unica"));

        assertEquals("true", respuesta.getHeaders().getFirst("Idempotent-Replayed"));
        // Solo se copian las cabeceras de la lista, no todas las del módulo
        assertNull(respuesta.getHeaders().getFirst("X-Interna-Del-Modulo"));
        assertEquals(MediaType.APPLICATION_JSON, respuesta.getHeaders().getContentType());
    }

    @Test
    void idempotentReplayedTambienSeDevuelveEnUnaRespuestaDeError() {
        HttpHeaders delModulo = new HttpHeaders();
        delModulo.set("Idempotent-Replayed", "true");
        modulos.expect(requestTo(INSCRIPCIONES + "/api/inscripciones"))
                .andRespond(withStatus(HttpStatus.CONFLICT).headers(delModulo).body("{\"error\":\"sin cupo\"}"));

        ResponseEntity<String> respuesta = proxy.proxyInscripciones(
                inscripcion("POST", "/api/inscripciones", "8c1f-clave-unica"));

        assertEquals(HttpStatus.CONFLICT, respuesta.getStatusCode());
        assertEquals("true", respuesta.getHeaders().getFirst("Idempotent-Replayed"));
    }

    @Test
    void sinIdempotentReplayedDelModuloNoSeAgrega() {
        modulos.expect(requestTo(INSCRIPCIONES + "/api/inscripciones"))
                .andRespond(withStatus(HttpStatus.CREATED).body("{}"));

        ResponseEntity<String> respuesta = proxy.proxyInscripciones(
                inscripcion("POST", "/api/inscripciones", "clave"));

        assertNull(respuesta.getHeaders().getFirst("Idempotent-Replayed"));
    }

    @Test
    void idempotencyKeyTambienSeReenviaAOtrosModulos() {
        // El reenvío es común a todas las rutas (un único lugar en el proxy)
        modulos.expect(requestTo(MATERIAS + "/api/materias"))
                .andExpect(header("Idempotency-Key", "otra-clave"))
                .andRespond(withStatus(HttpStatus.CREATED).body("{}"));

        MockHttpServletRequest request = inscripcion("POST", "/api/materias", "otra-clave");
        proxy.proxyMaterias(request);

        modulos.verify();
    }

    // ---------- Consistencia del caché de Materias ----------

    @Test
    void unaInscripcionCreadaInvalidaElCacheDeMaterias() {
        modulos.expect(requestTo(INSCRIPCIONES + "/api/inscripciones"))
                .andRespond(withStatus(HttpStatus.CREATED).body("{\"id\":10}"));

        proxy.proxyInscripciones(inscripcion("POST", "/api/inscripciones", "clave"));

        verify(cache).invalidar("materias");
    }

    @Test
    void unaCancelacionExitosaInvalidaElCacheDeMaterias() {
        modulos.expect(requestTo(INSCRIPCIONES + "/api/inscripciones/10"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        proxy.proxyInscripciones(new MockHttpServletRequest("DELETE", "/api/inscripciones/10"));

        verify(cache).invalidar("materias");
    }

    @Test
    void unConflictoNoInvalidaElCache() {
        modulos.expect(requestTo(INSCRIPCIONES + "/api/inscripciones"))
                .andRespond(withStatus(HttpStatus.CONFLICT).body("{\"error\":\"sin cupo\"}"));

        ResponseEntity<String> respuesta = proxy.proxyInscripciones(
                inscripcion("POST", "/api/inscripciones", "clave"));

        assertEquals(HttpStatus.CONFLICT, respuesta.getStatusCode());
        assertEquals("{\"error\":\"sin cupo\"}", respuesta.getBody());
        verify(cache, never()).invalidar("materias");
    }

    @Test
    void siInscripcionesNoRespondeNoSeInvalida() {
        modulos.expect(requestTo(INSCRIPCIONES + "/api/inscripciones"))
                .andRespond(request -> {
                    throw new java.net.ConnectException("conexión rechazada");
                });

        ResponseEntity<String> respuesta = proxy.proxyInscripciones(
                inscripcion("POST", "/api/inscripciones", "clave"));

        assertEquals(HttpStatus.BAD_GATEWAY, respuesta.getStatusCode());
        verify(cache, never()).invalidar("materias");
    }

    @Test
    void unaLecturaDeInscripcionesNoInvalida() {
        modulos.expect(requestTo(INSCRIPCIONES + "/api/inscripciones"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        proxy.proxyInscripciones(new MockHttpServletRequest("GET", "/api/inscripciones"));

        verify(cache, never()).invalidar("materias");
    }
}
