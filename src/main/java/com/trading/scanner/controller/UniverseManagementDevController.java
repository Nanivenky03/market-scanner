package com.trading.scanner.controller;

import com.trading.scanner.service.instrument.UniverseManagementService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/dev/universe")
@Profile({"default", "simulation"})
@RequiredArgsConstructor
public class UniverseManagementDevController {

    private final UniverseManagementService universeManagementService;

    public record AddInstrumentRequest(Integer instrumentId) {
    }

    @GetMapping("/search")
    public ResponseEntity<List<UniverseManagementService.InstrumentSearchResult>> search(
            @RequestParam String query,
            @RequestParam(defaultValue = "NSE") String exchange
    ) {
        return ResponseEntity.ok(
                universeManagementService.searchActiveInstruments(query, exchange)
        );
    }

    @PostMapping("/add")
    public ResponseEntity<UniverseManagementService.AddToUniverseResult> addToUniverse(
            @RequestBody AddInstrumentRequest request
    ) {
        return ResponseEntity.ok(
                universeManagementService.addInstrumentToUniverse(request.instrumentId())
        );
    }
}