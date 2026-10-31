package com.example.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// En producción estos valores llegan por variables de entorno (JWT_SECRET, *_URL, FRONTEND_URL)
@SpringBootTest(properties = {
        "jwt.secreto=secreto-solo-para-pruebas-de-32-bytes",
        "modulos.estudiantes.url=http://localhost:3001",
        "modulos.materias.url=http://localhost:3002",
        "modulos.inscripciones.url=http://localhost:3003",
        "modulos.autenticacion.url=http://localhost:3004",
        "cors.allowed-origins=http://localhost:4200"
})
class GatewayApplicationTests {

    @Test
    void contextLoads() {
        // El Gateway no depende de una base de datos, así que este test debería
        // pasar incluso si los 3 módulos todavía no están arriba.
    }
}
