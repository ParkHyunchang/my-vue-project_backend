package com.hyunchang.webapp.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

@Component
public class JwtUtil {

    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";
    public static final String CLAIM_TYPE = "typ";
    public static final String CLAIM_JTI = "jti";

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.access-expiration}")
    private Long accessExpiration;

    @Value("${jwt.refresh-expiration}")
    private Long refreshExpiration;

    private SecretKey getSigningKey() {
        if (secret == null || secret.getBytes().length < 32) {
            throw new IllegalStateException("jwt.secret must be at least 32 bytes (256 bits) for HS256");
        }
        return Keys.hmacShaKeyFor(secret.getBytes());
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    public String extractJti(String token) {
        return extractClaim(token, Claims::getId);
    }

    public String extractType(String token) {
        Claims claims = extractAllClaims(token);
        Object typ = claims.get(CLAIM_TYPE);
        return typ == null ? null : typ.toString();
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private Boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    public long getAccessExpirationMillis() {
        return accessExpiration;
    }

    public long getRefreshExpirationMillis() {
        return refreshExpiration;
    }

    public String generateAccessToken(UserDetails userDetails) {
        return generateAccessToken(userDetails.getUsername());
    }

    public String generateAccessToken(String username) {
        return createToken(username, TYPE_ACCESS, UUID.randomUUID().toString(), accessExpiration);
    }

    public String generateRefreshToken(String username) {
        return createToken(username, TYPE_REFRESH, UUID.randomUUID().toString(), refreshExpiration);
    }

    public String generateRefreshToken(String username, String jti) {
        return createToken(username, TYPE_REFRESH, jti, refreshExpiration);
    }

    private String createToken(String subject, String type, String jti, long expirationMillis) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(CLAIM_TYPE, type);
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .claims(claims)
                .subject(subject)
                .id(jti)
                .issuedAt(new Date(now))
                .expiration(new Date(now + expirationMillis))
                .signWith(getSigningKey(), Jwts.SIG.HS256)
                .compact();
    }

    public Boolean validateAccessToken(String token, UserDetails userDetails) {
        try {
            final String username = extractUsername(token);
            return TYPE_ACCESS.equals(extractType(token))
                    && username.equals(userDetails.getUsername())
                    && !isTokenExpired(token);
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public Boolean validateRefreshToken(String token) {
        try {
            return TYPE_REFRESH.equals(extractType(token)) && !isTokenExpired(token);
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }
}
