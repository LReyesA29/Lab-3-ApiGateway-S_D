package com.example.gateway.controller;

import com.example.gateway.config.RutasConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Endpoint de composicion (patron Backend for Frontend). Orquesta llamadas HTTP a
 * los tres microservicios y devuelve un unico JSON con la informacion combinada
 * de un estudiante: sus datos, sus inscripciones, el curso de cada una y el
 * docente de cada curso.
 *
 * El contrato de entrada/salida de este endpoint esta documentado en el README.
 */
@RestController
public class DetalleController {

    // Nombre del campo dentro de cada inscripción que trae el id del curso
    private static final String CAMPO_CURSO_ID = "cursoId";

    // Nombre del campo dentro de cada curso que trae el id del docente
    private static final String CAMPO_DOCENTE_ID = "docenteId";

    // Clave que contiene el arreglo de inscripciones dentro de la respuesta paginada
    private static final String CAMPO_LISTA_INSCRIPCIONES = "data";

    // Rutas de cada microservicio consumidas por este endpoint
    private static final String RUTA_ESTUDIANTE = "/api/estudiantes/%d";
    private static final String RUTA_INSCRIPCIONES_POR_ESTUDIANTE = "/api/inscripciones?estudianteId=%d";
    private static final String RUTA_CURSO = "/api/cursos/%s";
    private static final String RUTA_DOCENTE = "/api/docentes/%s";

    @Autowired
    private RutasConfig rutasConfig;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    @GetMapping("/api/estudiantes/{id}/detalle")
    public ResponseEntity<?> detalle(@PathVariable int id) {
        try {
            // 1. Estudiante — se usa tal cual, no se le agrega nada
            JsonNode estudiante = obtenerJson(
                    rutasConfig.getEstudiantesUrl() + String.format(RUTA_ESTUDIANTE, id));

            // 2. Inscripciones del estudiante (ya deberían traer sus notas anidadas)
            JsonNode respuestaInscripciones = obtenerJson(
                    rutasConfig.getInscripcionesUrl() + String.format(RUTA_INSCRIPCIONES_POR_ESTUDIANTE, id));

            JsonNode listaInscripciones = (CAMPO_LISTA_INSCRIPCIONES != null
                    && respuestaInscripciones.has(CAMPO_LISTA_INSCRIPCIONES))
                    ? respuestaInscripciones.get(CAMPO_LISTA_INSCRIPCIONES)
                    : respuestaInscripciones;

            // 3-4. Por cada inscripción: su curso, y el docente de ese curso
            for (JsonNode inscripcion : listaInscripciones) {
                String cursoId = inscripcion.get(CAMPO_CURSO_ID).asText();
                JsonNode curso = obtenerJson(rutasConfig.getMateriasUrl() + String.format(RUTA_CURSO, cursoId));

                String docenteId = curso.get(CAMPO_DOCENTE_ID).asText();
                JsonNode docente = obtenerJson(rutasConfig.getMateriasUrl() + String.format(RUTA_DOCENTE, docenteId));

                ((ObjectNode) curso).set("docente", docente);
                ((ObjectNode) inscripcion).set("curso", curso);
            }

            // 5. Ensamblar todo en un solo JSON de respuesta
            ObjectNode resultado = mapper.createObjectNode();
            resultado.set("estudiante", estudiante);
            resultado.set("inscripciones", listaInscripciones);

            return ResponseEntity.ok(resultado);

        } catch (HttpStatusCodeException ex) {
            // Uno de los módulos respondió con error propio (ej. estudiante no existe)
            return ResponseEntity.status(ex.getStatusCode()).body(ex.getResponseBodyAsString());

        } catch (ResourceAccessException ex) {
            // Uno de los módulos no respondió en absoluto
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "Uno de los módulos no respondió al construir el detalle"));

        } catch (Exception ex) {
            // Respuesta de un módulo con una forma distinta a la esperada
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "No se pudo componer el detalle: " + ex.getMessage()));
        }
    }

    private JsonNode obtenerJson(String url) throws Exception {
        String cuerpo = restTemplate.getForObject(url, String.class);
        return mapper.readTree(cuerpo);
    }
}
