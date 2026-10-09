package com.akshara.fees;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Online payment settings. {@code gateway} picks the gateway for new orders. The sandbox secret signs sandbox
 * payments and webhooks; when it is empty a random one is made at startup (fine, because only this API signs and
 * checks sandbox signatures). Real gateway keys never go in configuration files: they come from Secrets Manager.
 */
@ConfigurationProperties("akshara.payments")
public record PaymentProperties(@DefaultValue("sandbox") String gateway, @DefaultValue Sandbox sandbox) {

    public record Sandbox(@DefaultValue("") String secret) {
    }
}
