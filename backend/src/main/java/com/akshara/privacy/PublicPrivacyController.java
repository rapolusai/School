package com.akshara.privacy;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.privacy.PrivacyViews.PublicNotice;

/** A school's privacy notice, current or an older version, for anyone. No sign-in; rate limited per client IP. */
@RestController
@RequestMapping("/api/public/schools/{code}")
public class PublicPrivacyController {

    private final PublicNoticeService notices;

    PublicPrivacyController(PublicNoticeService notices) {
        this.notices = notices;
    }

    @GetMapping("/privacy-notice")
    public PublicNotice notice(@PathVariable String code, @RequestParam(required = false) Integer version,
            HttpServletRequest http) {
        return notices.notice(code, version, http.getRemoteAddr());
    }
}
