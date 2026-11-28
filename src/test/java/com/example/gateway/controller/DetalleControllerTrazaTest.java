package com.example.gateway.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import com.example.gateway.config.RutasConfig;
import com.example.gateway.datalake.RegistroPeticionesFilter;
import com.example.gateway.security.JwtAuthenticationFilter;

/** Los endpoints de composición propagan el mismo X-Trace-Id (y la identidad) a TODAS sus llamadas. */
class DetalleControllerTrazaTest {

    private static final String ESTUDIANTES = "http://estudiantes:3001";
    private static final String MATERIAS = "http://materias:3002";
    private static final String INSCRIPCIONES = "http://inscripciones:3003";
    private static final String TRACE = "0123456789abcdef";

    private final List<String> trazas = new ArrayList<>();
    private final List<String> usuarios = new ArrayList<>();
    private final List<String> roles = new ArrayList<>();
    private MockRestServiceServer modulos;
    private DetalleController detalle;

    @BeforeEach
    void preparar() {
        RutasConfig rutas = mock(RutasConfig.class);
        when(rutas.getEstudiantesUrl()).thenReturn(ESTUDIANTES);
        when(rutas.getMateriasUrl()).thenReturn(MATERIAS);
        when(rutas.getInscripcionesUrl()).thenReturn(INSCRIPCIONES);
        RestTemplate restTemplate = new RestTemplate();
        modulos = MockRestServiceServer.bindTo(restTemplate).build();
        detalle = new DetalleController(rutas, restTemplate);
    }

    private MockHttpServletRequest peticion() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/estudiantes/3/detalle");
        request.setAttribute(RegistroPeticionesFilter.ATRIBUTO_TRACE_ID, TRACE);
        request.setAttribute(JwtAuthenticationFilter.ATRIBUTO_USUARIO_ID, "7");
        request.setAttribute(JwtAuthenticationFilter.ATRIBUTO_ROL, "ESTUDIANTE");
        return request;
    }

    private void esperar(String url, String cuerpo) {
        modulos.expect(ExpectedCount.once(), requestTo(url))
                .andExpect(method(HttpMethod.GET))
                .andExpect(req -> {
                    trazas.add(req.getHeaders().getFirst("X-Trace-Id"));
                    usuarios.add(req.getHeaders().getFirst("X-User-Id"));
                    roles.add(req.getHeaders().getFirst("X-User-Role"));
                })
                .andRespond(withSuccess(cuerpo, MediaType.APPLICATION_JSON));
    }

    @Test
    void elDetalleDelEstudianteEnviaElMismoTraceIdATodasLasLlamadasInternas() {
        esperar(ESTUDIANTES + "/api/estudiantes/3", "{\"id\":3,\"nombre\":\"Ana\"}");
        esperar(INSCRIPCIONES + "/api/inscripciones?estudianteId=3",
                "{\"data\":[{\"id\":1,\"cursoId\":10},{\"id\":2,\"cursoId\":11}]}");
        esperar(MATERIAS + "/api/materias/cursos/10", "{\"id\":10,\"docenteId\":100}");
        esperar(MATERIAS + "/api/materias/docentes/100", "{\"id\":100}");
        esperar(MATERIAS + "/api/materias/cursos/11", "{\"id\":11,\"docenteId\":101}");
        esperar(MATERIAS + "/api/materias/docentes/101", "{\"id\":101}");

        ResponseEntity<?> respuesta = detalle.detalle("3", peticion());

        modulos.verify();
        assertEquals(HttpStatus.OK, respuesta.getStatusCode());
        assertEquals(6, trazas.size());
        assertEquals(Set.of(TRACE), Set.copyOf(trazas), "todas las llamadas internas con el mismo trace: " + trazas);
        assertEquals(Set.of("7"), Set.copyOf(usuarios));
        assertEquals(Set.of("ESTUDIANTE"), Set.copyOf(roles));
    }

    @Test
    void elDetalleDelCursoTambienPropagaElTraceId() {
        esperar(MATERIAS + "/api/materias/cursos/10", "{\"id\":10,\"docenteId\":100}");
        esperar(MATERIAS + "/api/materias/docentes/100", "{\"id\":100}");
        esperar(INSCRIPCIONES + "/api/inscripciones?cursoId=10", "{\"data\":[{\"estudianteId\":3}]}");
        esperar(ESTUDIANTES + "/api/estudiantes/3", "{\"id\":3}");

        detalle.detalleCurso("10", peticion());

        modulos.verify();
        assertEquals(List.of(TRACE, TRACE, TRACE, TRACE), trazas);
    }

    @Test
    void unErrorDeUnModuloSeDevuelveIgualYDejaSuErrorTypeParaElDatalake() {
        modulos.expect(requestTo(ESTUDIANTES + "/api/estudiantes/3"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"No existe\",\"errorType\":\"EstudianteNoEncontrado\"}"));
        MockHttpServletRequest request = peticion();

        ResponseEntity<?> respuesta = detalle.detalle("3", request);

        assertEquals(HttpStatus.NOT_FOUND, respuesta.getStatusCode());
        assertEquals("{\"message\":\"No existe\",\"errorType\":\"EstudianteNoEncontrado\"}", respuesta.getBody());
        assertEquals("EstudianteNoEncontrado", request.getAttribute(RegistroPeticionesFilter.ATRIBUTO_ERROR_TYPE));
    }

    @Test
    void sinIdentidadNoSeEnvianLasCabecerasDeUsuario() {
        esperar(ESTUDIANTES + "/api/estudiantes/3", "{\"id\":3}");
        esperar(INSCRIPCIONES + "/api/inscripciones?estudianteId=3", "{\"data\":[]}");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/estudiantes/3/detalle");
        request.setAttribute(RegistroPeticionesFilter.ATRIBUTO_TRACE_ID, TRACE);

        detalle.detalle("3", request);

        assertTrue(usuarios.stream().allMatch(u -> u == null));
        assertEquals(List.of(TRACE, TRACE), trazas);
    }
}
