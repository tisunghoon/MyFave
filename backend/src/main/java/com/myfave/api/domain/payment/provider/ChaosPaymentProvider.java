package com.myfave.api.domain.payment.provider;

import com.myfave.api.global.config.ChaosProperties;
import com.myfave.api.global.error.CustomException;
import com.myfave.api.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;

// chaos 프로파일 전용 PaymentProvider 데코레이터.
// ChaosProperties.enabled=true 일 때 latencyMs 지연 후 정상 응답(slow200) 또는 예외(error)를 반환.
// @Primary로 실 PortOnePaymentProvider를 대체하며, disabled 상태엔 실 provider에 위임.
// 핵심: SLOW200 모드는 에러가 아닌 지연된 HTTP 200 재현 — 기본 서킷브레이커는 이를 실패로 안 잡음.
@Slf4j
@Component
@Profile("chaos")
@Primary
@RequiredArgsConstructor
public class ChaosPaymentProvider implements PaymentProvider {

    private final ChaosProperties chaosProperties;

    @Qualifier("portOnePaymentProvider")
    private final PaymentProvider delegate;

    @Override
    public PortOnePaymentInfo getPaymentInfo(String pgTransactionId) {
        if (!chaosProperties.isEnabled()) {
            return delegate.getPaymentInfo(pgTransactionId);
        }

        applyFault("getPaymentInfo", pgTransactionId);

        // SLOW200: 지연 후 정상 응답 반환 (실 PortOne 미호출 — 더미 크레덴셜 환경 대응)
        return new PortOnePaymentInfo(
                pgTransactionId,
                "PAID",
                13000,
                "https://chaos.receipt/" + pgTransactionId,
                ZonedDateTime.now()
        );
    }

    @Override
    public void cancelPayment(String pgTransactionId, int cancelAmount, String reason) {
        if (!chaosProperties.isEnabled()) {
            delegate.cancelPayment(pgTransactionId, cancelAmount, reason);
            return;
        }

        applyFault("cancelPayment", pgTransactionId);
        log.debug("[Chaos] cancelPayment 완료 (지연 후 정상 반환): pgTxId={}", pgTransactionId);
    }

    private void applyFault(String api, String pgTransactionId) {
        switch (chaosProperties.getMode()) {
            case SLOW200 -> {
                log.warn("[Chaos] {} {}ms 지연 주입 — 워커 스레드 점유 중: pgTxId={}",
                        api, chaosProperties.getLatencyMs(), pgTransactionId);
                try {
                    Thread.sleep(chaosProperties.getLatencyMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            case ERROR -> {
                log.warn("[Chaos] {} 예외 주입: pgTxId={}", api, pgTransactionId);
                throw new CustomException(ErrorCode.PAYMENT_FAILED);
            }
        }
    }
}
