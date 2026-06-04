package com.DinoCorp.Lost_In_Woods.controller;

import com.DinoCorp.Lost_In_Woods.dto.AuthResponse;
import com.DinoCorp.Lost_In_Woods.dto.RegisterRequest;
import com.DinoCorp.Lost_In_Woods.model.GameSession;
import com.DinoCorp.Lost_In_Woods.service.GameService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
public class AuthController {

    private final GameService gameService;

    public AuthController(GameService gameService) {
        this.gameService = gameService;
    }

    // Guest: create a session named "Guest". Returns the ID used to start the story.
    @PostMapping("/guest")
    public ResponseEntity<AuthResponse> playAsGuest() {
        GameSession session = gameService.createSession("Guest");
        return ResponseEntity.ok(new AuthResponse(session.getId(), session.getPlayerName()));
    }

    // Login: create a session under the chosen name.
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody RegisterRequest request) {
        String name = request.getUsername();
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        GameSession session = gameService.createSession(name.trim());
        return ResponseEntity.ok(new AuthResponse(session.getId(), session.getPlayerName()));
    }
}
