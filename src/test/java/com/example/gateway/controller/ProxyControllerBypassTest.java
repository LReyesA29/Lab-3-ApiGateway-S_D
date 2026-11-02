package com.example.gateway.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.example.gateway.security.JwtAuthenticationFilter;
import com.example.gateway.security.PoliticaAcceso;

class ProxyControllerBypassTest {

    private MockHttpServletRequest peticion(String rol, String cabecera) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/materias");
        if (rol != null) {
            request.setAttribute(JwtAuthenticationFilter.ATRIBUTO_ROL, rol);
        }
        if (cabecera != null) {
            request.addHeader("X-Cache-Bypass", cabecera);
        }
        return request;
    }

    @Test
    void unAdminConLaCabeceraSaltaElCache() {
        assertTrue(ProxyController.pideSaltarCache(peticion(PoliticaAcceso.ROL_ADMIN, "true")));
    }

    @Test
    void unEstudianteConLaCabeceraNoSaltaElCache() {
        assertFalse(ProxyController.pideSaltarCache(peticion(PoliticaAcceso.ROL_ESTUDIANTE, "true")));
    }

    @Test
    void sinRolNoSaltaElCacheAunqueTraigaLaCabecera() {
        assertFalse(ProxyController.pideSaltarCache(peticion(null, "true")));
    }

    @Test
    void unAdminSinLaCabeceraUsaElCache() {
        assertFalse(ProxyController.pideSaltarCache(peticion(PoliticaAcceso.ROL_ADMIN, null)));
        assertFalse(ProxyController.pideSaltarCache(peticion(PoliticaAcceso.ROL_ADMIN, "false")));
    }
}
