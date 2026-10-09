package com.akshara.shared;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/** Issues and verifies short-lived access tokens (HS256). The key comes from Secrets Manager in AWS. */
@Component
public class JwtService {

    public static final String ISSUER = "akshara";
    public static final String CLAIM_TENANT = "tid";
    public static final String CLAIM_PERMISSIONS = "perms";
    public static final String CLAIM_ROLES = "roles";
    public static final String CLAIM_NAME = "name";
    public static final String CLAIM_TYPE = "typ";

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final AksharaProperties properties;

    public JwtService(AksharaProperties properties) {
        this.properties = properties;
        String secret = properties.auth().jwtSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("akshara.auth.jwt-secret must be set to at least 32 bytes");
        }
        SecretKey key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.encoder = NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS256).build();
        NimbusJwtDecoder nimbusDecoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        nimbusDecoder.setJwtValidator(org.springframework.security.oauth2.jwt.JwtValidators.createDefaultWithIssuer(ISSUER));
        this.decoder = nimbusDecoder;
    }

    public JwtDecoder decoder() {
        return decoder;
    }

    public long ttlSeconds() {
        return properties.auth().accessTokenTtl().toSeconds();
    }

    public String issueSchoolToken(UUID userId, UUID tenantId, String name, List<String> roles,
            List<String> permissions) {
        return issue(userId, name, "school", tenantId, roles, permissions);
    }

    public String issuePlatformToken(UUID adminId, String name) {
        return issue(adminId, name, "platform", null, List.of("SUPER_ADMIN"), List.of(Permissions.PLATFORM_ADMIN));
    }

    private String issue(UUID subject, String name, String type, UUID tenantId, List<String> roles,
            List<String> permissions) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(subject.toString())
                .issuedAt(now)
                .expiresAt(now.plus(properties.auth().accessTokenTtl()))
                .claim(CLAIM_TYPE, type)
                .claim(CLAIM_NAME, name)
                .claim(CLAIM_ROLES, roles)
                .claim(CLAIM_PERMISSIONS, permissions);
        if (tenantId != null) {
            claims.claim(CLAIM_TENANT, tenantId.toString());
        }
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }
}
