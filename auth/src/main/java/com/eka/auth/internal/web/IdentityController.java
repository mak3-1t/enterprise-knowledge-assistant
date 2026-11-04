package com.eka.auth.internal.web;

import com.eka.auth.internal.service.IdentityService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@RequestMapping("/api")
public class IdentityController {
    private final IdentityService identities;

    public IdentityController(IdentityService identities) {
        this.identities = identities;
    }

    @GetMapping("/me")
    public ResponseEntity<IdentityService.MeView> me() {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(identities.me());
    }

    @GetMapping("/tenant/members")
    public ResponseEntity<IdentityService.MemberPage> members(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(identities.members(page, size));
    }

    @GetMapping("/tenant/members/{id}")
    public ResponseEntity<IdentityService.MemberView> member(@PathVariable UUID id) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(identities.member(id));
    }

    @ExceptionHandler(IdentityService.MemberNotFound.class)
    ResponseEntity<ErrorResponse> notFound() {
        return ResponseEntity.status(404)
                .header("Cache-Control", "no-store")
                .body(new ErrorResponse("not_found"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> invalidPagination() {
        return invalidRequest();
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorResponse> invalidType() {
        return invalidRequest();
    }

    @ExceptionHandler(BadCredentialsException.class)
    ResponseEntity<ErrorResponse> inactive() {
        return ResponseEntity.status(401)
                .header("WWW-Authenticate", "Bearer")
                .header("Cache-Control", "no-store")
                .body(new ErrorResponse("unauthorized"));
    }

    private ResponseEntity<ErrorResponse> invalidRequest() {
        return ResponseEntity.badRequest()
                .header("Cache-Control", "no-store")
                .body(new ErrorResponse("invalid_request"));
    }

    public record ErrorResponse(String error) {}
}
