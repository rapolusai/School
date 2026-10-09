package com.akshara.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Creates the first Super Admin from configuration (a Secrets Manager value in AWS) if it does not exist yet. */
@Component
public class PlatformAdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PlatformAdminBootstrap.class);

    private final PlatformAdminRepository admins;
    private final PasswordEncoder passwordEncoder;
    private final String email;
    private final String password;
    private final String name;

    public PlatformAdminBootstrap(PlatformAdminRepository admins, PasswordEncoder passwordEncoder,
            @Value("${akshara.platform.bootstrap-admin.email:}") String email,
            @Value("${akshara.platform.bootstrap-admin.password:}") String password,
            @Value("${akshara.platform.bootstrap-admin.name:Platform Admin}") String name) {
        this.admins = admins;
        this.passwordEncoder = passwordEncoder;
        this.email = email;
        this.password = password;
        this.name = name;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (email.isBlank() || password.isBlank() || admins.findByEmail(email).isPresent()) {
            return;
        }
        admins.save(new PlatformAdmin(email.trim(), name, passwordEncoder.encode(password)));
        log.info("Created bootstrap platform admin {}", email);
    }
}
