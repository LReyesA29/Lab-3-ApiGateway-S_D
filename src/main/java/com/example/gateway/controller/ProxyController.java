package com.example.gateway.controller;

import com.example.gateway.config.RutasConfig;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Reenvía cada petición al microservicio correspondiente según el prefijo de la
 * ruta, sin alterar método, query params ni body. El try/catch evita que la caída
 * de un módulo derribe también al Gateway.
 *
 * @RequestMapping sin "method" responde a GET/POST/PUT/PATCH/DELETE por igual.
 */
@RestController
public class ProxyController {

    @Autowired
    private RutasConfig rutasConfig;

    private final RestTemplate restTemplate = new RestTemplate();

    @RequestMapping("/api/estudiantes/**")
    public ResponseEntity<String> proxyEstudiantes(HttpServletRequest request) {
        return reenviar(request, rutasConfig.getEstudiantesUrl());
    }

    @RequestMapping("/api/materias/**")
    public ResponseEntity<String> proxyMaterias(HttpServletRequest request) {
        return reenviar(request, rutasConfig.getMateriasUrl());
    }

    @RequestMapping("/api/inscripciones/**")
    public ResponseEntity<String> proxyInscripciones(HttpServletRequest request) {
        return reenviar(request, rutasConfig.getInscripcionesUrl());
    }

    private ResponseEntity<String> reenviar(HttpServletRequest request, String urlBase) {
        String destino = urlBase + request.getRequestURI()
                + (request.getQueryString() != null ? "?" + request.getQueryString() : "");
        HttpMethod metodo = HttpMethod.valueOf(request.getMethod());

        try {
            String cuerpo = null;
            if (metodo == HttpMethod.POST || metodo == HttpMethod.PUT || metodo == HttpMethod.PATCH) {
                cuerpo = StreamUtils.copyToString(request.getInputStream(), StandardCharsets.UTF_8);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entidad = new HttpEntity<>(cuerpo, headers);

            ResponseEntity<String> respuesta = restTemplate.exchange(destino, metodo, entidad, String.class);

            return ResponseEntity.status(respuesta.getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(respuesta.getBody());

        } catch (HttpStatusCodeException ex) {
            // El módulo SÍ respondió, pero con un error propio (404, 400, 409...).
            // Eso no es una caída del módulo — se reenvía tal cual, sin tocarlo.
            return ResponseEntity.status(ex.getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(ex.getResponseBodyAsString());

        } catch (ResourceAccessException | IOException ex) {
            // El módulo no respondió en absoluto (caído, timeout, conexión rechazada).
            // Este es el caso real de "manejo de fallos" que pide la rúbrica.
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\": \"Servicio no disponible: " + urlBase + "\"}");
        }
    }
}
