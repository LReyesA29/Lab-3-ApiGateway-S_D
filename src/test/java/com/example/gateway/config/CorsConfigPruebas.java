package com.example.gateway.config;

import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/** Para pruebas de otros paquetes: la misma configuración CORS que usa el Gateway. */
public final class CorsConfigPruebas {

    private CorsConfigPruebas() {
    }

    public static UrlBasedCorsConfigurationSource fuente(String origenes) {
        return new CorsConfig(origenes).fuente();
    }
}
