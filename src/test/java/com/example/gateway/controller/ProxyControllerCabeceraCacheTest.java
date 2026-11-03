package com.example.gateway.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class ProxyControllerCabeceraCacheTest {

    @Test
    void elResultadoDelCacheSaleEnXCacheYEnSuCopia() {
        ResponseEntity<String> resultado = ProxyController.conCabecera(
                ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body("{\"content\":[]}"), "HIT");

        assertEquals("X-Cache-Gateway", ProxyController.CABECERA_CACHE_GATEWAY);
        assertEquals("HIT", resultado.getHeaders().getFirst("X-Cache"));
        assertEquals("HIT", resultado.getHeaders().getFirst("X-Cache-Gateway"));
    }

    @Test
    void seConservanElEstadoElCuerpoYElTipoDeContenido() {
        ResponseEntity<String> original = ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"no existe\"}");

        ResponseEntity<String> resultado = ProxyController.conCabecera(original, "MISS");

        assertEquals(HttpStatus.NOT_FOUND, resultado.getStatusCode());
        assertEquals("{\"error\":\"no existe\"}", resultado.getBody());
        assertEquals(MediaType.APPLICATION_JSON, resultado.getHeaders().getContentType());
        assertEquals("MISS", resultado.getHeaders().getFirst("X-Cache"));
        assertEquals("MISS", resultado.getHeaders().getFirst("X-Cache-Gateway"));
    }
}
