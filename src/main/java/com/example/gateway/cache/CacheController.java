package com.example.gateway.cache;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Diagnóstico del caché: aciertos, fallos y hit ratio desde que arrancó esta
 * instancia del Gateway. Como no está en las rutas de lectura del rol
 * ESTUDIANTE, solo un ADMIN puede usarlo (ver PoliticaAcceso).
 */
@RestController
@RequestMapping("/api/cache")
public class CacheController {

    private final CacheRespuestas cache;

    public CacheController(CacheRespuestas cache) {
        this.cache = cache;
    }

    @GetMapping("/estadisticas")
    public Map<String, Object> estadisticas() {
        return cache.estadisticas();
    }

    /** Pone los contadores en cero, por ejemplo antes de una medición. */
    @DeleteMapping("/estadisticas")
    public ResponseEntity<Void> reiniciar() {
        cache.reiniciarEstadisticas();
        return ResponseEntity.noContent().build();
    }
}
