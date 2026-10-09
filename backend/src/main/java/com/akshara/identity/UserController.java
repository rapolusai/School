package com.akshara.identity;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.akshara.shared.ApiException;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository users;
    private final UserService userService;
    private final PasswordEncoder passwordEncoder;

    public UserController(UserRepository users, UserService userService, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
    }

    public record CreateUserRequest(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 10, max = 200, message = "Use at least 10 characters.") String password,
            @NotEmpty(message = "Pick at least one role.") List<@NotBlank String> roles) {
    }

    @GetMapping
    @PreAuthorize("hasAuthority('users.read')")
    @Transactional(readOnly = true)
    public List<UserSummary> list() {
        return users.findAllWithRoles().stream().map(UserSummary::of).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('users.read')")
    @Transactional(readOnly = true)
    public UserSummary get(@PathVariable UUID id) {
        return users.findByIdWithRoles(id).map(UserSummary::of).orElseThrow(() -> ApiException.notFound("User"));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('users.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public UserSummary create(@Valid @RequestBody CreateUserRequest request) {
        String hash = passwordEncoder.encode(request.password());
        return UserSummary.of(userService.createUser(request.name(), request.email(), hash, request.roles(), null));
    }
}
