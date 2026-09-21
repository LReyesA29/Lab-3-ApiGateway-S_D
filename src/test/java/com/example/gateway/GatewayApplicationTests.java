package com.example.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class GatewayApplicationTests {

    @Test
    void contextLoads() {
        // El Gateway no depende de una base de datos, así que este test debería
        // pasar incluso si los 3 módulos todavía no están arriba.
    }
}
