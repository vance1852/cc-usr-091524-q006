package com.admin.equipment.web;

import com.admin.equipment.model.AppUser;
import com.admin.equipment.repo.AppUserRepository;
import com.admin.equipment.security.JwtUtil;
import com.admin.equipment.security.PasswordUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AppUserRepository userRepo;
    private final JwtUtil jwtUtil;

    public AuthController(AppUserRepository userRepo, JwtUtil jwtUtil) {
        this.userRepo = userRepo;
        this.jwtUtil = jwtUtil;
    }

    public record LoginRequest(String username, String password) {}

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {
        if (req.username() == null || req.password() == null) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", "请求参数不合法"));
        }
        AppUser user = userRepo.findByUsername(req.username()).orElse(null);
        if (user == null || !PasswordUtil.verify(req.password(), user.getPasswordHash())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("detail", "用户名或密码错误"));
        }
        String token = jwtUtil.createToken(user.getId(), user.getUsername());
        return ResponseEntity.ok(Map.of("access_token", token, "token_type", "bearer"));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(HttpServletRequest request) {
        AppUser user = (AppUser) request.getAttribute("currentUser");
        return ResponseEntity.ok(Map.of(
                "id", user.getId(),
                "username", user.getUsername(),
                "display_name", user.getDisplayName()
        ));
    }
}
