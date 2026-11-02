package com.example.gateway.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class CacheRespuestasTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valores;
    private CacheRespuestas cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void preparar() {
        redis = mock(StringRedisTemplate.class);
        valores = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valores);
        cache = new CacheRespuestas(redis, true, 120);
    }

    @Test
    void laClaveIncluyeVersionRutaYParametrosOrdenados() {
        when(valores.get("gw:cache:materias:version")).thenReturn("3");

        Optional<String> clave = cache.claveDe("materias", "/api/materias", "sortBy=nombre&pageNumber=0");

        assertEquals("gw:cache:materias:v3:/api/materias?pageNumber=0&sortBy=nombre", clave.orElseThrow());
    }

    @Test
    void elMismoFiltroEnOtroOrdenDaLaMismaClave() {
        assertEquals(CacheRespuestas.normalizarQuery("b=2&a=1"), CacheRespuestas.normalizarQuery("a=1&b=2"));
    }

    @Test
    void hitYMissSeCuentanParaElHitRatio() {
        when(valores.get("clave-con-dato")).thenReturn("{\"content\":[]}");
        when(valores.get("clave-vacia")).thenReturn(null);

        assertTrue(cache.leer("clave-con-dato").isPresent());
        assertFalse(cache.leer("clave-vacia").isPresent());

        assertEquals(1L, cache.estadisticas().get("hits"));
        assertEquals(1L, cache.estadisticas().get("misses"));
        assertEquals(50.0, cache.estadisticas().get("hitRatio"));
    }

    @Test
    void guardarUsaElTtlConfigurado() {
        cache.guardar("k", "v");
        verify(valores).set("k", "v", Duration.ofSeconds(120));
    }

    @Test
    void invalidarSubeLaVersionDelRecurso() {
        cache.invalidar("materias");
        verify(valores).increment("gw:cache:materias:version");
    }

    @Test
    void siRedisFallaNoLanzaErrorYQuedaEnPausa() {
        when(valores.get(anyString())).thenThrow(new RedisConnectionFailureException("Redis caído"));

        assertTrue(cache.claveDe("materias", "/api/materias", null).isEmpty());
        assertEquals(true, cache.estadisticas().get("redisEnPausa"));

        // Durante la pausa ni siquiera se intenta hablar con Redis
        cache.claveDe("materias", "/api/materias", null);
        verify(valores, times(1)).get(anyString());
    }

    @Test
    void conElCacheDeshabilitadoNoSeUsaRedis() {
        CacheRespuestas apagado = new CacheRespuestas(redis, false, 120);

        assertTrue(apagado.claveDe("materias", "/api/materias", null).isEmpty());
        apagado.invalidar("materias");

        verify(valores, never()).get(anyString());
        verify(valores, never()).increment(any());
    }
}
