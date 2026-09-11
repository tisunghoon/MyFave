// 시나리오 F — 외부 PG 지연 cascade 재현 (장애 실습 키트)
// 결제 confirm과 읽기 경로(상품목록·주문조회)를 동시에 부하해,
// 결제가 공유 워커 스레드를 다 먹어 읽기 경로가 먼저 죽는 cascade를 관측한다.
//
// 사전 준비:
//   1. SPRING_PROFILES_ACTIVE=chaos ./gradlew bootRun  (Hikari pool 10 / threads 20)
//   2. node backend/load-test/seed/token-prepare.mjs   (tokens.json 생성)
//   3. psql -f backend/load-test/seed/reset.sql        (재고 리셋)
//   4. 이 스크립트 실행 후 별도 터미널에서 장애 주입:
//      curl -X POST 'localhost:8080/internal/chaos/pg?enabled=true&latencyMs=8000'

import { check, sleep } from 'k6';
import { Trend, Counter, Rate } from 'k6/metrics';
import http from 'k6/http';

import { get, post, BASE_URL } from '../lib/http.js';
import { tokens, pickByVu } from '../lib/pool.js';

// chaos toggle — 장애 주입/해제에 환경변수로도 제어 가능
const CHAOS_URL = __ENV.CHAOS_URL || 'http://localhost:8080/internal/chaos/pg';

export const options = {
  scenarios: {
    // 읽기 경로: 상품목록·주문조회 — 결제 경로에 스레드를 뺏기면 여기가 먼저 죽음
    readers: {
      executor: 'constant-vus',
      vus: 30,
      duration: '3m',
    },
    // 결제 경로: PG 지연 시 워커 스레드 점유원
    payers: {
      executor: 'constant-vus',
      vus: 15,
      duration: '3m',
    },
  },
  thresholds: {
    // 읽기 경로 p99 — 장애 전 200ms 이내, 장애 중 폭증 관측
    'http_req_duration{scenario:readers}': ['p(99)<500'],
    // 결제 경로는 PG 지연으로 임계값 분리 (실패율만 관측)
    'http_req_failed{scenario:payers}': ['rate<0.5'],
  },
};

// 읽기 경로 지연 분포 — 장애 전후 비교용
const readLatency = new Trend('f_read_latency', true);
// 결제 경로 지연 분포 — PG 지연이 스레드 점유를 통해 여기 반영됨
const payLatency = new Trend('f_pay_latency', true);
// 읽기 경로 에러 카운트 — "결제 경로가 죽는 동안 읽기가 살아있는가" 검증
const readErrors = new Counter('f_read_errors');
const payErrors = new Counter('f_pay_errors');
const cascadeRate = new Rate('f_cascade_detected');

// ── 읽기 경로 VU ──────────────────────────────────────────────
export function readers() {
  const token = pickByVu(__VU);

  // 상품 목록 (캐시 조회 — 장애와 무관해야 정상)
  const productsRes = get('/products?page=0&size=10', token);
  const productsOk = check(productsRes, { '[읽기] 상품목록 200': r => r.status === 200 });
  readLatency.add(productsRes.timings.duration, { endpoint: 'products' });
  if (!productsOk) readErrors.add(1, { endpoint: 'products' });

  sleep(0.5);

  // 주문 조회 (DB 조회 — 스레드풀 고갈 시 같이 죽음)
  const ordersRes = get('/orders?page=0&size=5', token);
  const ordersOk = check(ordersRes, { '[읽기] 주문조회 200': r => r.status === 200 });
  readLatency.add(ordersRes.timings.duration, { endpoint: 'orders' });
  if (!ordersOk) readErrors.add(1, { endpoint: 'orders' });

  // cascade 감지: 읽기가 3초 이상 걸리면 결제 스레드가 풀을 잠식 중
  const isCascade = productsRes.timings.duration > 3000 || ordersRes.timings.duration > 3000;
  cascadeRate.add(isCascade);

  sleep(0.5);
}

// ── 결제 경로 VU ──────────────────────────────────────────────
export function payers() {
  const token = pickByVu(__VU);

  // 1단계: 결제 준비 (주문 생성)
  const prepareRes = post('/payments/prepare', token, {
    productId: Math.floor(Math.random() * 10) + 1,
    quantity: 1,
  });
  const prepareOk = check(prepareRes, { '[결제] prepare 2xx': r => r.status >= 200 && r.status < 300 });
  if (!prepareOk) {
    payErrors.add(1, { step: 'prepare' });
    sleep(1);
    return;
  }

  let orderId, paymentId;
  try {
    const body = JSON.parse(prepareRes.body);
    orderId = body.data?.orderId;
    paymentId = body.data?.paymentId;
  } catch (_) {
    payErrors.add(1, { step: 'parse' });
    sleep(1);
    return;
  }

  sleep(0.2);

  // 2단계: 결제 confirm — chaos ON 시 이 호출이 latencyMs 동안 워커 스레드를 점유
  const confirmRes = post('/payments/confirm', token, {
    orderId,
    paymentId,
    pgTransactionId: `CHAOS-PAY-${paymentId}`,
  });
  const confirmOk = check(confirmRes, { '[결제] confirm 2xx': r => r.status >= 200 && r.status < 300 });
  payLatency.add(confirmRes.timings.duration, { step: 'confirm' });
  if (!confirmOk) payErrors.add(1, { step: 'confirm' });

  sleep(1);
}
