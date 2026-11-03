package com.example.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;
import java.util.List;

/**
 * CORS global del Gateway para que el frontend lo consuma directamente desde otro origen.
 * <p>
 * El origen permitido llega solo por la variable de entorno FRONTEND_URL (obligatoria;
 * admite varios separados por coma, p. ej. el local y el de producción).
 * <p>
 * Además del mapeo de WebMvcConfigurer se registra un CorsFilter con la misma
 * configuración y máxima prioridad: JwtAuthenticationFilter corre antes que el
 * DispatcherServlet, así que sin este filtro el preflight (OPTIONS) recibiría 401 y los
 * 401/403 del filtro JWT saldrían sin cabeceras CORS (el navegador los mostraría como
 * "error de CORS" y el frontend no podría leer el código).
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    static final List<String> METODOS_PERMITIDOS = List.of("GET", "POST", "PUT", "DELETE", "OPTIONS");
    static final List<String> CABECERAS_PERMITIDAS = List.of("Authorization", "Content-Type", "Accept");
    // Cabeceras de respuesta que el JavaScript del frontend puede leer (diagnóstico del caché;
    // X-Cache-Gateway es la copia que sobrevive a CloudFront)
    static final List<String> CABECERAS_EXPUESTAS = List.of("X-Cache", "X-Cache-Gateway");
    // El navegador guarda en caché la respuesta del preflight durante este tiempo
    private static final long MAX_AGE_SEGUNDOS = 3600;

    private final CorsConfiguration configuracion;

    public CorsConfig(@Value("${cors.allowed-origins}") String origenes) {
        this.configuracion = crearConfiguracion(origenes);
    }

    static CorsConfiguration crearConfiguracion(String origenes) {
        List<String> permitidos = Arrays.stream(origenes.split(","))
                .map(String::trim)
                .filter(origen -> !origen.isEmpty())
                // Un origen nunca termina en "/": "http://localhost:4200/" no coincidiría
                .map(origen -> origen.endsWith("/") ? origen.substring(0, origen.length() - 1) : origen)
                .toList();

        if (permitidos.isEmpty()) {
            throw new IllegalStateException("La variable de entorno FRONTEND_URL es obligatoria (p. ej. http://localhost:4200)");
        }
        if (permitidos.contains("*")) {
            throw new IllegalStateException("FRONTEND_URL debe indicar el origen exacto del frontend; no se admite \"*\"");
        }

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(permitidos);
        config.setAllowedMethods(METODOS_PERMITIDOS);
        config.setAllowedHeaders(CABECERAS_PERMITIDAS);
        config.setExposedHeaders(CABECERAS_EXPUESTAS);
        config.setMaxAge(MAX_AGE_SEGUNDOS);
        return config;
    }

    /** Mapeo de Spring MVC (aplica a los controladores). */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(configuracion.getAllowedOrigins().toArray(String[]::new))
                .allowedMethods(METODOS_PERMITIDOS.toArray(String[]::new))
                .allowedHeaders(CABECERAS_PERMITIDAS.toArray(String[]::new))
                .exposedHeaders(CABECERAS_EXPUESTAS.toArray(String[]::new))
                .maxAge(MAX_AGE_SEGUNDOS);
    }

    /** Filtro de servlet que corre antes que JwtAuthenticationFilter (ver comentario de la clase). */
    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilter() {
        FilterRegistrationBean<CorsFilter> registro = new FilterRegistrationBean<>(new CorsFilter(fuente()));
        registro.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registro;
    }

    UrlBasedCorsConfigurationSource fuente() {
        UrlBasedCorsConfigurationSource fuente = new UrlBasedCorsConfigurationSource();
        fuente.registerCorsConfiguration("/**", configuracion);
        return fuente;
    }
}
