package com.example.gateway.controller;

import java.io.IOException;

import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import com.example.gateway.cache.CacheRespuestas;
import com.example.gateway.config.RutasConfig;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Para pruebas de otros paquetes: ProxyController como servlet al final de una cadena de filtros. */
public final class ProxyControllerPruebas {

    private ProxyControllerPruebas() {
    }

    public static HttpServlet comoServlet(RutasConfig rutas, CacheRespuestas cache, RestTemplate restTemplate) {
        ProxyController proxy = new ProxyController(rutas, cache, restTemplate);
        return new HttpServlet() {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
                String ruta = request.getRequestURI().toLowerCase();
                ResponseEntity<String> respuesta;
                if (ruta.startsWith("/api/estudiantes")) {
                    respuesta = proxy.proxyEstudiantes(request);
                } else if (ruta.startsWith("/api/materias")) {
                    respuesta = proxy.proxyMaterias(request);
                } else if (ruta.startsWith("/api/inscripciones")) {
                    respuesta = proxy.proxyInscripciones(request);
                } else {
                    respuesta = proxy.proxyAutenticacion(request);
                }
                response.setStatus(respuesta.getStatusCode().value());
                respuesta.getHeaders().forEach((nombre, valores) -> valores.forEach(v -> response.addHeader(nombre, v)));
                if (respuesta.getBody() != null) {
                    response.getWriter().write(respuesta.getBody());
                }
            }
        };
    }
}
