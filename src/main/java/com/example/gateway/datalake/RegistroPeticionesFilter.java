package com.example.gateway.datalake;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import com.example.gateway.security.JwtAuthenticationFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Datalake de peticiones: al terminar cada petición imprime UNA línea JSON con el
 * contrato común a todos los módulos (ver README, sección "Datalake de peticiones").
 * <p>
 * Corre con la máxima prioridad, antes del CorsFilter y de JwtAuthenticationFilter,
 * para que también queden registrados (y lleven X-Trace-Id) los rechazos de CORS y
 * los 401/403 del JWT. Además:
 * <ul>
 *   <li>lee el X-Trace-Id recibido o genera uno, lo deja en el atributo
 *       {@link #ATRIBUTO_TRACE_ID} (de ahí lo toman los controladores para
 *       propagarlo) y lo devuelve en la respuesta;</li>
 *   <li>descarta X-User-Id / X-User-Role que lleguen del cliente
 *       ({@link SinCabecerasDeIdentidad});</li>
 *   <li>copia el cuerpo con ContentCachingRequestWrapper a medida que ProxyController
 *       lo lee, sin consumirlo: el proxy sigue recibiendo los bytes intactos (multipart
 *       incluido) y solo se guardan en memoria los primeros 16 KB.</li>
 * </ul>
 * Registrar nunca rompe la petición: todo error se atrapa y queda en el log normal.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RegistroPeticionesFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RegistroPeticionesFilter.class);

    public static final String CABECERA_TRACE_ID = "X-Trace-Id";
    public static final String ATRIBUTO_TRACE_ID = "datalake.traceId";
    /** Motivo de negocio de un error (lo pone quien lo conoce, p. ej. el errorType de un módulo). */
    public static final String ATRIBUTO_ERROR_TYPE = "datalake.errorType";
    /** La respuesta es un error reenviado tal cual desde un módulo (no lo generó el Gateway). */
    static final String ATRIBUTO_ERROR_DEL_MODULO = "datalake.errorDelModulo";

    static final String SERVICIO = "gateway";
    static final int LIMITE_CUERPO = 16 * 1024;
    static final String OMITIDO = "<omitido>";
    static final String NO_JSON = "<no-json>";
    static final String DEMASIADO_GRANDE = "<demasiado-grande>";

    // Un trace_id recibido se reutiliza si tiene una forma razonable; si no (vacío,
    // demasiado largo o con caracteres raros), se genera uno nuevo
    private static final Pattern TRACE_ID_VALIDO = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    // Rutas que no se registran (salud y documentación); sí llevan X-Trace-Id
    private static final List<String> RUTAS_SIN_REGISTRO =
            List.of("/health", "/actuator", "/swagger-ui", "/swagger-ui.html", "/v3/api-docs", "/api-docs");
    // Nunca se guarda el cuerpo de Autenticación (login y registro traen contraseñas)
    private static final String RUTA_AUTENTICACION = "/api/auth";
    private static final int LIMITE_CUERPO_ERROR = 64 * 1024;

    private static final DateTimeFormatter FORMATO_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SalidaDatalake salida;
    private final String instancia;

    public RegistroPeticionesFilter(SalidaDatalake salida, @Value("${HOSTNAME:}") String hostname) {
        this.salida = salida;
        this.instancia = instanciaDe(hostname);
    }

    static String instanciaDe(String hostname) {
        return hostname == null || hostname.isBlank() ? "local" : hostname.trim();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long inicio = System.nanoTime();
        Instant llegada = Instant.now();

        String traceId = traceIdDe(request);
        request.setAttribute(ATRIBUTO_TRACE_ID, traceId);
        response.setHeader(CABECERA_TRACE_ID, traceId);

        HttpServletRequest sinIdentidad = new SinCabecerasDeIdentidad(request);
        if (!seRegistra(request)) {
            chain.doFilter(sinIdentidad, response);
            return;
        }

        // Límite +1 byte: si la copia llega a superar 16 KB, el cuerpo es "demasiado grande"
        ContentCachingRequestWrapper conCopia = new ContentCachingRequestWrapper(sinIdentidad, LIMITE_CUERPO + 1);
        boolean completo = false;
        try {
            chain.doFilter(conCopia, response);
            completo = true;
        } finally {
            registrar(conCopia, response, traceId, llegada, inicio, completo);
        }
    }

    private void registrar(ContentCachingRequestWrapper request, HttpServletResponse response, String traceId,
                           Instant llegada, long inicio, boolean completo) {
        try {
            long latenciaMs = (System.nanoTime() - inicio) / 1_000_000;
            // Si la cadena lanzó una excepción, el contenedor responderá 500
            int status = completo ? response.getStatus() : 500;

            Map<String, Object> linea = new LinkedHashMap<>();
            linea.put("ts", FORMATO_TS.format(llegada));
            linea.put("service", SERVICIO);
            linea.put("instance", instancia);
            linea.put("trace_id", traceId);
            linea.put("method", request.getMethod());
            linea.put("path", request.getRequestURI());
            String query = request.getQueryString();
            linea.put("query", query == null || query.isEmpty() ? null : query);
            linea.put("status", status);
            linea.put("latency_ms", latenciaMs);
            linea.put("user_id", texto(request.getAttribute(JwtAuthenticationFilter.ATRIBUTO_USUARIO_ID)));
            linea.put("role", texto(request.getAttribute(JwtAuthenticationFilter.ATRIBUTO_ROL)));
            linea.put("idempotency_key", request.getHeader("Idempotency-Key"));
            linea.put("error_type", errorTypeDe(request, status));
            linea.put("request_body", cuerpoDe(request, response, completo));

            salida.escribir(llegada, MAPPER.writeValueAsString(linea));
        } catch (Exception ex) {
            log.warn("No se pudo registrar la petición {} en el datalake: {}", traceId, ex.toString());
        }
    }

    // ---------- trace_id ----------

    static String traceIdDe(HttpServletRequest request) {
        String recibido = request.getHeader(CABECERA_TRACE_ID);
        if (recibido != null) {
            recibido = recibido.trim();
            if (TRACE_ID_VALIDO.matcher(recibido).matches()) {
                return recibido;
            }
        }
        return nuevoTraceId();
    }

    /** 16 caracteres hexadecimales aleatorios. */
    static String nuevoTraceId() {
        return String.format("%016x", ThreadLocalRandom.current().nextLong());
    }

    // ---------- error_type ----------

    /**
     * Lo llaman los controladores cuando reenvían un error de un módulo: si su cuerpo
     * JSON trae "errorType", ese es el error_type de la línea. Nunca lanza excepciones.
     */
    public static void marcarErrorDelModulo(HttpServletRequest request, String cuerpo) {
        try {
            request.setAttribute(ATRIBUTO_ERROR_DEL_MODULO, Boolean.TRUE);
            if (cuerpo == null || cuerpo.isBlank() || cuerpo.length() > LIMITE_CUERPO_ERROR) {
                return;
            }
            JsonNode errorType = MAPPER.readTree(cuerpo).get("errorType");
            if (errorType != null && errorType.isTextual() && !errorType.asText().isBlank()) {
                request.setAttribute(ATRIBUTO_ERROR_TYPE, errorType.asText());
            }
        } catch (Exception ex) {
            // Cuerpo que no es JSON: no hay errorType
        }
    }

    static String errorTypeDe(HttpServletRequest request, int status) {
        Object propio = request.getAttribute(ATRIBUTO_ERROR_TYPE);
        if (propio instanceof String texto && !texto.isBlank()) {
            return texto;
        }
        // Un error reenviado desde un módulo sin errorType (p. ej. credenciales
        // incorrectas en el login) no es un error del Gateway
        if (Boolean.TRUE.equals(request.getAttribute(ATRIBUTO_ERROR_DEL_MODULO))) {
            return null;
        }
        return switch (status) {
            case 401 -> "TokenInvalido";
            case 403 -> "AccesoDenegado";
            case 502 -> "ModuloNoDisponible";
            default -> null;
        };
    }

    // ---------- request_body ----------

    private Object cuerpoDe(ContentCachingRequestWrapper request, HttpServletResponse response, boolean completo) {
        boolean hayCuerpo = request.getContentLengthLong() > 0
                || request.getHeader("Transfer-Encoding") != null
                || request.getContentAsByteArray().length > 0;
        if (!hayCuerpo) {
            return null;
        }
        if (esDeAutenticacion(rutaNormalizada(request))) {
            return OMITIDO;
        }
        if (!esJson(request.getContentType())) {
            return NO_JSON;
        }
        if (request.getContentLengthLong() > LIMITE_CUERPO) {
            return DEMASIADO_GRANDE;
        }

        byte[] copia = request.getContentAsByteArray();
        if (copia.length == 0 && completo) {
            copia = leerCuerpoNoLeido(request, response);
        }
        if (copia.length > LIMITE_CUERPO) {
            return DEMASIADO_GRANDE;
        }
        if (copia.length == 0) {
            return null;
        }
        try {
            return MAPPER.readTree(copia);
        } catch (IOException ex) {
            return NO_JSON;
        }
    }

    /**
     * El cuerpo de una petición rechazada antes de llegar al proxy (401, 403) nadie lo
     * leyó. Se envía primero la respuesta al cliente y después se leen, como máximo,
     * 16 KB + 1 byte para la copia.
     */
    private static byte[] leerCuerpoNoLeido(ContentCachingRequestWrapper request, HttpServletResponse response) {
        try {
            response.flushBuffer();
            InputStream entrada = request.getInputStream();
            byte[] bloque = new byte[4096];
            int total = 0;
            int leidos;
            while (total <= LIMITE_CUERPO && (leidos = entrada.read(bloque)) != -1) {
                total += leidos;
            }
        } catch (IOException | RuntimeException ex) {
            // Cliente desconectado o cuerpo ya consumido: se usa lo que haya
        }
        return request.getContentAsByteArray();
    }

    private static boolean esJson(String contentType) {
        if (contentType == null) {
            return false;
        }
        try {
            MediaType tipo = MediaType.parseMediaType(contentType);
            return MediaType.APPLICATION_JSON.isCompatibleWith(tipo) || tipo.getSubtype().endsWith("+json");
        } catch (RuntimeException ex) {
            return false;
        }
    }

    // ---------- rutas ----------

    private static boolean seRegistra(HttpServletRequest request) {
        String ruta = rutaNormalizada(request);
        return RUTAS_SIN_REGISTRO.stream().noneMatch(prefijo -> ruta.equals(prefijo) || ruta.startsWith(prefijo + "/"));
    }

    static boolean esDeAutenticacion(String ruta) {
        return ruta.equals(RUTA_AUTENTICACION) || ruta.startsWith(RUTA_AUTENTICACION + "/");
    }

    /**
     * Ruta decodificada y normalizada por el contenedor, en minúsculas y sin barras
     * repetidas ni final: "/API/Auth//login/" también cuenta como Autenticación.
     */
    static String rutaNormalizada(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        String pathInfo = request.getPathInfo();
        String ruta = (servletPath != null ? servletPath : "") + (pathInfo != null ? pathInfo : "");
        if (ruta.isEmpty()) {
            ruta = request.getRequestURI() != null ? request.getRequestURI() : "/";
        }
        ruta = ruta.toLowerCase().replaceAll("/{2,}", "/");
        return ruta.length() > 1 && ruta.endsWith("/") ? ruta.substring(0, ruta.length() - 1) : ruta;
    }

    private static String texto(Object valor) {
        return valor == null ? null : valor.toString();
    }
}
