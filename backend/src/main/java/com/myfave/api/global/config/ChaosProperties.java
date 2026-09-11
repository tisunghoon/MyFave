package com.myfave.api.global.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@Profile("chaos")
@ConfigurationProperties(prefix = "chaos.pg")
public class ChaosProperties {

    private boolean enabled = false;
    private long latencyMs = 8000;
    private Mode mode = Mode.SLOW200;

    public enum Mode {
        SLOW200,  // latencyMs 지연 후 정상 응답 — "느린 성공"으로 에러율 0%인 장애 재현
        ERROR     // 즉시 예외 발생
    }
}
