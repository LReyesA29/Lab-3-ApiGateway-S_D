package com.example.gateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Valida los JWT emitidos por el módulo de Autenticación (HS256 firmado con el
 * mismo secreto). El Gateway ya no emite tokens propios.
 */
@Component
public class JwtUtil {

    // HS256 exige una clave de al menos 256 bits
    private static final int LONGITUD_MINIMA_SECRETO = 32;

    private final JwtParser parser;

    public JwtUtil(@Value("${jwt.secreto}") String secreto) {
        if (secreto == null || secreto.getBytes(StandardCharsets.UTF_8).length < LONGITUD_MINIMA_SECRETO) {
            throw new IllegalStateException(
                    "La variable de entorno JWT_SECRET debe tener al menos " + LONGITUD_MINIMA_SECRETO + " caracteres");
        }
        this.parser = Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(secreto.getBytes(StandardCharsets.UTF_8)))
                .build();
    }

    /** Devuelve los claims si la firma y la expiración son válidas; lanza JwtException si no. */
    public Claims validar(String token) {
        return parser.parseSignedClaims(token).getPayload();
    }
}
