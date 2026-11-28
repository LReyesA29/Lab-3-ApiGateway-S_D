package com.example.gateway.datalake;

import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

/**
 * Oculta X-User-Id y X-User-Role de la petición del cliente. Esas cabeceras solo
 * las pone el Gateway (a partir del JWT ya validado) al llamar a los módulos; si
 * llegan desde afuera son un intento de suplantar a otro usuario y se descartan.
 */
class SinCabecerasDeIdentidad extends HttpServletRequestWrapper {

    static final Set<String> DESCARTADAS = Set.of("x-user-id", "x-user-role");

    SinCabecerasDeIdentidad(HttpServletRequest request) {
        super(request);
    }

    private static boolean descartada(String nombre) {
        return nombre != null && DESCARTADAS.contains(nombre.toLowerCase());
    }

    @Override
    public String getHeader(String nombre) {
        return descartada(nombre) ? null : super.getHeader(nombre);
    }

    @Override
    public Enumeration<String> getHeaders(String nombre) {
        return descartada(nombre) ? Collections.emptyEnumeration() : super.getHeaders(nombre);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        List<String> nombres = Collections.list(super.getHeaderNames());
        nombres.removeIf(SinCabecerasDeIdentidad::descartada);
        return Collections.enumeration(nombres);
    }
}
