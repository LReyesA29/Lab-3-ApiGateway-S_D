package com.example.gateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtUtil {

    @Value("${jwt.secreto}")
    private String secreto;

    @Value("${jwt.expiracion-minutos}")
    private long expiracionMinutos;

    private SecretKey clave() {
        return Keys.hmacShaKeyFor(secreto.getBytes(StandardCharsets.UTF_8));
    }

    public String generarToken(String usuario) {
        Date ahora = new Date();
        Date expira = new Date(ahora.getTime() + expiracionMinutos * 60 * 1000);
        return Jwts.builder()
                .subject(usuario)
                .issuedAt(ahora)
                .expiration(expira)
                .signWith(clave())
                .compact();
    }

    /** Devuelve el usuario (subject) si el token es válido; lanza excepción si no lo es o expiró. */
    public String validarYObtenerUsuario(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(clave())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return claims.getSubject();
    }
}
