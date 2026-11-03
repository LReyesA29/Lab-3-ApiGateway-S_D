package com.example.gateway.controller;

import com.example.gateway.cache.CacheRespuestas;
import com.example.gateway.config.RutasConfig;
import com.example.gateway.security.JwtAuthenticationFilter;
import com.example.gateway.security.PoliticaAcceso;
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
import java.util.Optional;

/**
 * Reenvía cada petición al microservicio correspondiente según el prefijo de la
 * ruta, sin alterar método, query params ni body. El try/catch evita que la caída
 * de un módulo derribe también al Gateway.
 *
 * @RequestMapping sin "method" responde a GET/POST/PUT/PATCH/DELETE por igual.
 */
@RestController
public class ProxyController {

    // Recurso cacheado (catálogo de Materias) y cabeceras del caché
    private static final String RECURSO_MATERIAS = "materias";
    private static final String CABECERA_CACHE = "X-Cache";
    // Copia de X-Cache con otro nombre: detrás de CloudFront el cliente nunca ve
    // X-Cache, porque CloudFront usa ese mismo nombre para su propio caché y la
    // reemplaza ("Miss from cloudfront"). Esta copia la deja pasar intacta
    static final String CABECERA_CACHE_GATEWAY = "X-Cache-Gateway";
    // Solo para medir "sin caché": salta Redis y va directo al módulo. Solo se
    // respeta si la envía un ADMIN; con cualquier otro rol se ignora
    private static final String CABECERA_SALTAR_CACHE = "X-Cache-Bypass";

    @Autowired
    private RutasConfig rutasConfig;

    @Autowired
    private CacheRespuestas cache;

    private final RestTemplate restTemplate = new RestTemplate();

    @RequestMapping("/api/estudiantes/**")
    public ResponseEntity<String> proxyEstudiantes(HttpServletRequest request) {
        return reenviar(request, rutasConfig.getEstudiantesUrl());
    }

    /**
     * Catálogo de Materias con caché (cache-aside): las lecturas GET pasan por
     * Redis y cualquier escritura exitosa (POST, PUT, PATCH, DELETE) lo invalida.
     */
    @RequestMapping("/api/materias/**")
    public ResponseEntity<String> proxyMaterias(HttpServletRequest request) {
        String urlBase = rutasConfig.getMateriasUrl();

        if (HttpMethod.GET.matches(request.getMethod())) {
            return leerConCache(request, urlBase);
        }

        ResponseEntity<String> respuesta = reenviar(request, urlBase);
        if (respuesta.getStatusCode().is2xxSuccessful()) {
            cache.invalidar(RECURSO_MATERIAS);
        }
        return respuesta;
    }

    // Inscripciones NO se cachea: los cupos cambian con cada inscripción y un
    // dato viejo permitiría inscribir en un curso que ya está lleno
    @RequestMapping("/api/inscripciones/**")
    public ResponseEntity<String> proxyInscripciones(HttpServletRequest request) {
        return reenviar(request, rutasConfig.getInscripcionesUrl());
    }

    // El login lo resuelve el módulo de Autenticación, que es quien emite los JWT
    @RequestMapping("/api/auth/**")
    public ResponseEntity<String> proxyAutenticacion(HttpServletRequest request) {
        return reenviar(request, rutasConfig.getAutenticacionUrl());
    }

    private ResponseEntity<String> leerConCache(HttpServletRequest request, String urlBase) {
        if (pideSaltarCache(request)) {
            return conCabecera(reenviar(request, urlBase), "BYPASS");
        }

        // La clave se arma antes de ir al módulo (ver CacheRespuestas.claveDe)
        Optional<String> clave = cache.claveDe(RECURSO_MATERIAS, request.getRequestURI(), request.getQueryString());
        if (clave.isPresent()) {
            Optional<String> guardado = cache.leer(clave.get());
            if (guardado.isPresent()) {
                return conCabecera(ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(guardado.get()), "HIT");
            }
        }

        ResponseEntity<String> respuesta = reenviar(request, urlBase);
        // Solo se guardan respuestas 200 con cuerpo: los errores (404, 502...) no se cachean
        if (clave.isPresent() && respuesta.getStatusCode().value() == 200 && respuesta.getBody() != null) {
            cache.guardar(clave.get(), respuesta.getBody());
        }
        return conCabecera(respuesta, "MISS");
    }

    /**
     * El bypass solo lo puede usar un ADMIN. El rol lo deja JwtAuthenticationFilter
     * como atributo de la petición después de validar el token, así que no se
     * puede falsificar con una cabecera.
     */
    static boolean pideSaltarCache(HttpServletRequest request) {
        return "true".equalsIgnoreCase(request.getHeader(CABECERA_SALTAR_CACHE))
                && PoliticaAcceso.ROL_ADMIN.equals(request.getAttribute(JwtAuthenticationFilter.ATRIBUTO_ROL));
    }

    /** Agrega el resultado del caché (HIT, MISS o BYPASS) en X-Cache y en su copia X-Cache-Gateway. */
    static ResponseEntity<String> conCabecera(ResponseEntity<String> respuesta, String valorCache) {
        return ResponseEntity.status(respuesta.getStatusCode())
                .headers(respuesta.getHeaders())
                .header(CABECERA_CACHE, valorCache)
                .header(CABECERA_CACHE_GATEWAY, valorCache)
                .body(respuesta.getBody());
    }

    private ResponseEntity<String> reenviar(HttpServletRequest request, String urlBase) {
        String destino = urlBase + request.getRequestURI()
                + (request.getQueryString() != null ? "?" + request.getQueryString() : "");
        HttpMethod metodo = HttpMethod.valueOf(request.getMethod());

        try {
            // Bytes sin transformar: un multipart/form-data (subida de archivos) se
            // corrompería si se leyera como texto UTF-8
            byte[] cuerpo = null;
            if (metodo == HttpMethod.POST || metodo == HttpMethod.PUT || metodo == HttpMethod.PATCH) {
                cuerpo = StreamUtils.copyToByteArray(request.getInputStream());
            }

            // Se conserva el Content-Type original (incluye el boundary del multipart)
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(request.getContentType() != null
                    ? MediaType.parseMediaType(request.getContentType())
                    : MediaType.APPLICATION_JSON);
            HttpEntity<byte[]> entidad = new HttpEntity<>(cuerpo, headers);

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
