package com.example.gateway.cache;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Caché compartido del Gateway en Redis, con el patrón cache-aside:
 * el Gateway busca primero en Redis; si no está (MISS), consulta al módulo y
 * guarda la respuesta con un TTL; si está (HIT), responde sin llamar al módulo.
 * <p>
 * Formato de la clave: {@code gw:cache:{recurso}:v{version}:{ruta}?{query ordenada}}.
 * Los parámetros de la consulta (paginación, orden y filtros) se ordenan para que
 * {@code ?a=1&b=2} y {@code ?b=2&a=1} compartan la misma entrada.
 * <p>
 * Invalidación por versión: una escritura sobre el recurso incrementa
 * {@code gw:cache:{recurso}:version}. Las claves viejas ya no se consultan y
 * Redis las borra solo cuando vence su TTL.
 * <p>
 * Degradación: si Redis falla, se registra en el log, se deja de consultar
 * durante {@value #PAUSA_TRAS_FALLO_MS} ms y el Gateway sigue funcionando sin
 * caché (más lento, pero sin errores para el cliente).
 */
@Component
public class CacheRespuestas {

    private static final Logger log = LoggerFactory.getLogger(CacheRespuestas.class);

    static final String PREFIJO = "gw:cache:";
    static final long PAUSA_TRAS_FALLO_MS = 30_000;

    private final StringRedisTemplate redis;
    private final boolean habilitado;
    private final Duration ttl;

    // Momento (epoch ms) hasta el que no se intenta usar Redis después de un fallo
    private volatile long enPausaHasta = 0;

    private final AtomicLong aciertos = new AtomicLong();
    private final AtomicLong fallos = new AtomicLong();
    private final AtomicLong erroresRedis = new AtomicLong();

    public CacheRespuestas(StringRedisTemplate redis,
                           @Value("${cache.habilitado:true}") boolean habilitado,
                           @Value("${cache.ttl-segundos:120}") long ttlSegundos) {
        if (ttlSegundos <= 0) {
            throw new IllegalStateException("CACHE_TTL_SEGUNDOS debe ser mayor que 0");
        }
        this.redis = redis;
        this.habilitado = habilitado;
        this.ttl = Duration.ofSeconds(ttlSegundos);
    }

    /**
     * Arma la clave de una lectura con la versión vigente del recurso.
     * Se calcula una sola vez por petición, antes de ir al módulo: si mientras
     * tanto llega una escritura, la respuesta vieja queda guardada en una
     * versión que nadie vuelve a leer.
     *
     * @return vacío si el caché está deshabilitado o Redis no responde
     */
    public Optional<String> claveDe(String recurso, String ruta, String query) {
        if (!disponible()) {
            return Optional.empty();
        }
        try {
            String version = redis.opsForValue().get(claveVersion(recurso));
            String q = normalizarQuery(query);
            return Optional.of(PREFIJO + recurso + ":v" + (version != null ? version : "0") + ":"
                    + ruta + (q.isEmpty() ? "" : "?" + q));
        } catch (RuntimeException ex) {
            registrarError("leer la versión de " + recurso, ex);
            return Optional.empty();
        }
    }

    /** Busca una respuesta guardada y cuenta el acierto o el fallo para el hit ratio. */
    public Optional<String> leer(String clave) {
        try {
            String valor = redis.opsForValue().get(clave);
            if (valor != null) {
                aciertos.incrementAndGet();
                return Optional.of(valor);
            }
        } catch (RuntimeException ex) {
            registrarError("leer", ex);
        }
        fallos.incrementAndGet();
        return Optional.empty();
    }

    /** Guarda una respuesta con el TTL configurado. Un error aquí no afecta la respuesta al cliente. */
    public void guardar(String clave, String cuerpo) {
        try {
            redis.opsForValue().set(clave, cuerpo, ttl);
        } catch (RuntimeException ex) {
            registrarError("guardar", ex);
        }
    }

    /**
     * Invalida todo lo cacheado del recurso subiendo su versión. Se intenta
     * siempre, aunque Redis haya fallado hace poco, para no dejar datos viejos
     * cuando vuelva.
     */
    public void invalidar(String recurso) {
        if (!habilitado) {
            return;
        }
        try {
            Long nueva = redis.opsForValue().increment(claveVersion(recurso));
            log.info("Caché de {} invalidado (versión {})", recurso, nueva);
        } catch (RuntimeException ex) {
            registrarError("invalidar " + recurso, ex);
        }
    }

    public Map<String, Object> estadisticas() {
        long hits = aciertos.get();
        long misses = fallos.get();
        long total = hits + misses;
        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("habilitado", habilitado);
        datos.put("ttlSegundos", ttl.toSeconds());
        datos.put("redisEnPausa", System.currentTimeMillis() < enPausaHasta);
        datos.put("hits", hits);
        datos.put("misses", misses);
        datos.put("hitRatio", total == 0 ? 0.0 : Math.round(hits * 1000.0 / total) / 10.0);
        datos.put("erroresRedis", erroresRedis.get());
        return datos;
    }

    public void reiniciarEstadisticas() {
        aciertos.set(0);
        fallos.set(0);
        erroresRedis.set(0);
    }

    static String normalizarQuery(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        return Arrays.stream(query.split("&"))
                .filter(parametro -> !parametro.isBlank())
                .sorted()
                .collect(Collectors.joining("&"));
    }

    private static String claveVersion(String recurso) {
        return PREFIJO + recurso + ":version";
    }

    private boolean disponible() {
        return habilitado && System.currentTimeMillis() >= enPausaHasta;
    }

    private void registrarError(String operacion, RuntimeException ex) {
        erroresRedis.incrementAndGet();
        enPausaHasta = System.currentTimeMillis() + PAUSA_TRAS_FALLO_MS;
        log.warn("Redis no disponible al {} ({}; causa: {}). El Gateway sigue sin caché durante {} s",
                operacion, ex.getMessage(), causaDeFondo(ex), PAUSA_TRAS_FALLO_MS / 1000);
    }

    /**
     * La excepción más interna (red, TLS, contraseña o tiempo de espera). El mensaje
     * de afuera ("Unable to connect to Redis") es el mismo para todas esas fallas.
     */
    static String causaDeFondo(Throwable ex) {
        Throwable causa = ex;
        while (causa.getCause() != null && causa.getCause() != causa) {
            causa = causa.getCause();
        }
        return causa == ex ? "sin causa adicional" : causa.getClass().getSimpleName() + ": " + causa.getMessage();
    }
}
