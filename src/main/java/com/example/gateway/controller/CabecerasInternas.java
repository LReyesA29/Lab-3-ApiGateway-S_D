package com.example.gateway.controller;

import java.util.List;
import java.util.function.Function;

import org.springframework.http.HttpHeaders;

import com.example.gateway.datalake.RegistroPeticionesFilter;
import com.example.gateway.security.JwtAuthenticationFilter;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Cabeceras que el Gateway envía a los módulos en TODAS sus llamadas: las del proxy
 * (ProxyController) y las de los endpoints de composición (DetalleController).
 * Es el único lugar donde se agrega una cabecera nueva.
 */
final class CabecerasInternas {

    /** Una cabecera reenviada y de dónde sale su valor (null = no se envía). */
    record CabeceraReenviada(String nombre, Function<HttpServletRequest, Object> valor) {
    }

    static final List<CabeceraReenviada> CABECERAS_REENVIADAS = List.of(
            // Tal cual la envió el cliente (POST /api/inscripciones, para que un reintento no duplique)
            new CabeceraReenviada("Idempotency-Key", request -> request.getHeader("Idempotency-Key")),
            // Del atributo, no de la cabecera: si el cliente no lo envió, lo generó el Gateway
            new CabeceraReenviada(RegistroPeticionesFilter.CABECERA_TRACE_ID,
                    request -> request.getAttribute(RegistroPeticionesFilter.ATRIBUTO_TRACE_ID)),
            // Identidad del JWT ya validado. Si el cliente las envía, RegistroPeticionesFilter
            // las descarta: nunca se toman de la petición
            new CabeceraReenviada("X-User-Id",
                    request -> request.getAttribute(JwtAuthenticationFilter.ATRIBUTO_USUARIO_ID)),
            new CabeceraReenviada("X-User-Role",
                    request -> request.getAttribute(JwtAuthenticationFilter.ATRIBUTO_ROL)));

    private CabecerasInternas() {
    }

    static HttpHeaders para(HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        for (CabeceraReenviada cabecera : CABECERAS_REENVIADAS) {
            Object valor = cabecera.valor().apply(request);
            if (valor != null && !valor.toString().isBlank()) {
                headers.set(cabecera.nombre(), valor.toString());
            }
        }
        return headers;
    }
}
