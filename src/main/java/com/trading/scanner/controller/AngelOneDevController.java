package com.trading.scanner.controller;

import com.trading.scanner.service.provider.angelone.AngelOneSessionService;
import com.trading.scanner.service.provider.angelone.dto.AngelOneSessionInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/dev/angelone")
@Profile({"default", "simulation"})
@RequiredArgsConstructor
public class AngelOneDevController {

    private final AngelOneSessionService angelOneSessionService;

    public record TotpRequest(String totp) {
    }

    @PostMapping("/session/test-auto")
    public ResponseEntity<AngelOneSessionInfo> testAutoSession() {
        AngelOneSessionInfo sessionInfo = angelOneSessionService.createSession();
        return ResponseEntity.ok(sessionInfo);
    }

    @PostMapping("/session/test-manual")
    public ResponseEntity<AngelOneSessionInfo> testManualSession(@RequestBody TotpRequest request) {
        AngelOneSessionInfo sessionInfo = angelOneSessionService.createSession(request.totp());
        return ResponseEntity.ok(sessionInfo);
    }
}