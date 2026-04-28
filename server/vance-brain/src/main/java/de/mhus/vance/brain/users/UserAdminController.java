package de.mhus.vance.brain.users;

import de.mhus.vance.api.users.UserCreateRequest;
import de.mhus.vance.api.users.UserDto;
import de.mhus.vance.api.users.UserPasswordRequest;
import de.mhus.vance.api.users.UserUpdateRequest;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.password.PasswordService;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import de.mhus.vance.shared.user.UserStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin CRUD for users.
 *
 * <p>v1 authorisation model: any authenticated user inside the tenant
 * can read and write user records. Cross-tenant probing is blocked by
 * {@link de.mhus.vance.brain.access.BrainAccessFilter}. The one
 * self-protect rule: a user cannot delete or disable their own account
 * — that prevents an admin from accidentally locking themselves out.
 *
 * <p>Passwords are sent as plaintext and hashed server-side via
 * {@link PasswordService#hash(String)}; the hash never leaves through
 * any read endpoint.
 */
@RestController
@RequestMapping("/brain/{tenant}/admin/users")
@RequiredArgsConstructor
@Slf4j
public class UserAdminController {

    private final UserService userService;
    private final PasswordService passwordService;

    @GetMapping
    public List<UserDto> list(@PathVariable("tenant") String tenant) {
        return userService.all(tenant).stream()
                .sorted(Comparator.comparing(UserDocument::getName))
                .map(UserAdminController::toDto)
                .toList();
    }

    @GetMapping("/{name}")
    public UserDto get(
            @PathVariable("tenant") String tenant,
            @PathVariable("name") String name) {
        return userService.findByTenantAndName(tenant, name)
                .map(UserAdminController::toDto)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "User '" + name + "' not found"));
    }

    @PostMapping
    public ResponseEntity<UserDto> create(
            @PathVariable("tenant") String tenant,
            @Valid @RequestBody UserCreateRequest request) {
        try {
            String passwordHash = (request.getPassword() == null || request.getPassword().isBlank())
                    ? null
                    : passwordService.hash(request.getPassword());
            UserDocument saved = userService.create(
                    tenant,
                    request.getName(),
                    passwordHash,
                    request.getTitle(),
                    request.getEmail());
            return ResponseEntity.status(HttpStatus.CREATED).body(toDto(saved));
        } catch (UserService.UserAlreadyExistsException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @PutMapping("/{name}")
    public UserDto update(
            @PathVariable("tenant") String tenant,
            @PathVariable("name") String name,
            @Valid @RequestBody UserUpdateRequest request,
            HttpServletRequest httpRequest) {
        UserStatus status = parseStatus(request.getStatus());
        // Self-protect: don't let the caller disable themselves.
        if (status == UserStatus.DISABLED && name.equals(currentUser(httpRequest))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot disable your own account");
        }
        try {
            UserDocument saved = userService.update(
                    tenant, name, request.getTitle(), request.getEmail(), status);
            return toDto(saved);
        } catch (UserService.UserNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @PutMapping("/{name}/password")
    public ResponseEntity<Void> setPassword(
            @PathVariable("tenant") String tenant,
            @PathVariable("name") String name,
            @Valid @RequestBody UserPasswordRequest request) {
        try {
            String hash = passwordService.hash(request.getPassword());
            userService.setPasswordHash(tenant, name, hash);
            return ResponseEntity.noContent().build();
        } catch (UserService.UserNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> delete(
            @PathVariable("tenant") String tenant,
            @PathVariable("name") String name,
            HttpServletRequest httpRequest) {
        if (name.equals(currentUser(httpRequest))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot delete your own account");
        }
        try {
            userService.delete(tenant, name);
            return ResponseEntity.noContent().build();
        } catch (UserService.UserNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────────────

    private static String currentUser(HttpServletRequest req) {
        Object u = req.getAttribute(AccessFilterBase.ATTR_USERNAME);
        if (!(u instanceof String s) || s.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "No authenticated user");
        }
        return s;
    }

    private static UserStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return UserStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown status '" + raw + "' — expected ACTIVE / DISABLED / PENDING");
        }
    }

    private static UserDto toDto(UserDocument u) {
        return UserDto.builder()
                .name(u.getName())
                .title(u.getTitle())
                .email(u.getEmail())
                .status(u.getStatus() == null ? "" : u.getStatus().name())
                .createdAt(u.getCreatedAt())
                .build();
    }
}
