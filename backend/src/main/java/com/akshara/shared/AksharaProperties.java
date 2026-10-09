package com.akshara.shared;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("akshara")
public record AksharaProperties(Auth auth, Security security, @DefaultValue Trial trial) {

    public record Auth(
            String jwtSecret,
            @DefaultValue("PT15M") Duration accessTokenTtl,
            @DefaultValue("P14D") Duration refreshTokenTtl,
            @DefaultValue("true") boolean cookieSecure,
            @DefaultValue("http://localhost:3000") List<String> allowedOrigins,
            @DefaultValue("10") int maxFailedLogins,
            @DefaultValue("PT5M") Duration failedLoginWindow) {
    }

    public record Security(@DefaultValue("false") boolean allowPrivilegedDbRole) {
    }

    public record Trial(@DefaultValue("P14D") Duration length) {
    }
}
