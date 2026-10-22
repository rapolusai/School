package com.akshara.certificates;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.certificates.CertificateViews.Verification;

/** The public verification page behind a certificate's QR code. No sign-in; rate-limited per IP. */
@RestController
public class PublicVerifyController {

    private final PublicVerifyService verify;

    PublicVerifyController(PublicVerifyService verify) {
        this.verify = verify;
    }

    @GetMapping("/api/public/verify/{token}")
    public Verification verify(@PathVariable String token, HttpServletRequest http) {
        return verify.verify(token, http.getRemoteAddr());
    }
}
