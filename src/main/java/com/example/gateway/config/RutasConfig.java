package com.example.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Expone las URLs base de los tres microservicios a partir de la configuración
 * en application.properties, para que los controladores no dependan de valores
 * fijos en el código.
 */
@Component
public class RutasConfig {

    @Value("${modulos.estudiantes.url}")
    private String estudiantesUrl;

    @Value("${modulos.materias.url}")
    private String materiasUrl;

    @Value("${modulos.inscripciones.url}")
    private String inscripcionesUrl;

    public String getEstudiantesUrl() {
        return estudiantesUrl;
    }

    public String getMateriasUrl() {
        return materiasUrl;
    }

    public String getInscripcionesUrl() {
        return inscripcionesUrl;
    }
}
