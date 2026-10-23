package com.akshara.preferences;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.preferences.PreferencesService.PreferencesView;

/** The signed-in person's own preferences. Any school user may read and change their own; nobody else's. */
@RestController
@RequestMapping("/api/me/preferences")
public class PreferencesController {

    private final PreferencesService preferences;

    public PreferencesController(PreferencesService preferences) {
        this.preferences = preferences;
    }

    public record PreferencesRequest(
            @NotNull(message = "Choose English, Hindi or Telugu.")
            @Pattern(regexp = "^(en|hi|te)$", message = "Choose English, Hindi or Telugu.") String language) {
    }

    @GetMapping
    public PreferencesView get() {
        return preferences.mine();
    }

    @PutMapping
    public PreferencesView put(@Valid @RequestBody PreferencesRequest request) {
        return preferences.setMine(request.language());
    }
}
