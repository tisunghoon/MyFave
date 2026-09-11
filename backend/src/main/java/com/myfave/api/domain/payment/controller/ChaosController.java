package com.myfave.api.domain.payment.controller;

import com.myfave.api.global.config.ChaosProperties;
import com.myfave.api.global.config.ChaosProperties.Mode;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// chaos 프로파일 전용 런타임 토글 API.
// k6 부하 중 라이브로 PG 지연을 ON/OFF해 cascade 장애를 재현·복구하는 실습 지원.
@RestController
@Profile("chaos")
@RequestMapping("/internal/chaos")
@RequiredArgsConstructor
public class ChaosController {

    private final ChaosProperties chaosProperties;

    @PostMapping("/pg")
    public ResponseEntity<Map<String, Object>> togglePgChaos(
            @RequestParam boolean enabled,
            @RequestParam(defaultValue = "8000") long latencyMs,
            @RequestParam(defaultValue = "SLOW200") Mode mode
    ) {
        chaosProperties.setEnabled(enabled);
        chaosProperties.setLatencyMs(latencyMs);
        chaosProperties.setMode(mode);

        return ResponseEntity.ok(currentState());
    }

    @GetMapping("/pg")
    public ResponseEntity<Map<String, Object>> getPgChaosState() {
        return ResponseEntity.ok(currentState());
    }

    private Map<String, Object> currentState() {
        return Map.of(
                "enabled", chaosProperties.isEnabled(),
                "latencyMs", chaosProperties.getLatencyMs(),
                "mode", chaosProperties.getMode()
        );
    }
}
