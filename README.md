# PayFlow – Payment Processing System (plain Java)

A simple, dependency-free Java 17+ version of the PayFlow backend. It models the same design as the
Spring Boot / JPA / MySQL / Razorpay version, with in-memory repositories and a mock Razorpay gateway,
so it compiles and runs with just a JDK.

## Run

```bash
javac -d out $(find src -name '*.java')
java -cp out com.payflow.PayFlowApplication
```

The app runs 8 scenarios and prints `23/23 checks passed`.

## 1. Functional requirements

| # | Requirement | Where |
|---|---|---|
| F1 | Create an order for a customer and amount, and register it with the gateway | `OrderService.createOrder` |
| F2 | Capture a payment after checkout using the gateway payment id | `PaymentService.capture` |
| F3 | Full and partial refunds, never more than the captured amount | `RefundService.refund` |
| F4 | Transaction history per customer (payments + refunds) | `TransactionHistoryService` |
| F5 | Receive Razorpay webhooks (`payment.captured`, `payment.failed`) and verify their HMAC-SHA256 signature | `WebhookService`, `WebhookSignatureVerifier` |
| F6 | Idempotency-Key on every write API: retries return the original result | `IdempotencyService` |
| F7 | Record every money movement in a double-entry ledger | `LedgerService` |
| F8 | Retry failed captures on a schedule with exponential backoff, then mark FAILED | `ReconciliationService` |
| F9 | Reconcile PENDING payments with the gateway's real status, and check every capture has a ledger entry | `ReconciliationService` |

## 2. Non-functional requirements

- **Exactly-once processing**: duplicate API calls, duplicate webhooks, and API + webhook + scheduler racing on the same payment all produce a single capture and a single ledger posting.
- **Consistency**: optimistic locking (`@Version`-style) on Order and Payment; a unique key on payment-per-order; ledger debits always equal credits.
- **Security**: webhook signature check with constant-time comparison; webhook secret never logged.
- **Reliability**: gateway timeouts don't lose money; reconciliation repairs drift.
- **Money accuracy**: amounts stored as `long` in minor units (paise), never `double`.
- **Concurrency safety**: thread-safe repositories and services.
- **Auditability**: the ledger is append-only.
- **Extensibility**: `PaymentGateway` interface lets the mock be swapped for the real Razorpay SDK.

## 3. Core entities

| Entity | Key fields |
|---|---|
| `Order` | id, customerId, amount, currency, gatewayOrderId, status (CREATED → PAID → PARTIALLY_REFUNDED → REFUNDED / FAILED), version |
| `Payment` | id (one per order), orderId, gatewayPaymentId, amount, refundedAmount, status (PENDING → CAPTURED / FAILED), attempts, version |
| `Refund` | id, paymentId, gatewayRefundId, amount |
| `LedgerEntry` | id, transactionRef, account, DEBIT/CREDIT, amount |
| Idempotency record | scope + key, request fingerprint, stored result |
| `Transaction` | read model for history |

Ledger accounts: `ASSET:razorpay_settlement` and `INCOME:merchant_revenue`.
Capture = debit settlement / credit revenue. Refund = the reverse.

## 4. Flows

**Payment flow**
1. Client → `createOrder(key, customer, amount)` → gateway order created → Order `CREATED`.
2. Customer pays in Razorpay Checkout → client gets `razorpay_payment_id`.
3. Client → `capture(key, orderId, gatewayPaymentId)` → Payment row inserted (unique per order) → gateway capture.
   - success → Payment `CAPTURED`, Order `PAID`, ledger posting `capture:<paymentId>`
   - failure/timeout → Payment stays `PENDING`.
4. Razorpay → webhook `payment.captured` → verify signature → skip if event id already seen → same `markCaptured` (no-op if already captured).

**Refund flow**: check `CAPTURED` and refundable amount → reserve the amount on the Payment (optimistic lock) → gateway refund → on failure release the reservation → ledger reversal → Order `PARTIALLY_REFUNDED` / `REFUNDED`.

**Reconciliation job (every N seconds)**: for each `PENDING` payment, ask the gateway:
`captured` → mark captured · `failed` → mark failed · `pending` → retry with backoff (1×, 2×, 4×…) up to 3 attempts, then `FAILED`.
Then verify each `CAPTURED` payment has a ledger posting.

## 5. Package layout

```
src/com/payflow/
├── PayFlowApplication.java          wiring + demo scenarios
├── model/          Order, Payment, Refund, LedgerEntry, Transaction, enums
├── repository/     InMemoryRepository (optimistic locking, unique insert)
├── gateway/        PaymentGateway, MockRazorpayGateway, WebhookSignatureVerifier
├── service/        Order, Payment, Refund, Webhook, Ledger, Idempotency,
│                   Reconciliation, TransactionHistory services
└── exception/      PayFlowException, OptimisticLockException, InvalidSignatureException
```

## Mapping to the Spring Boot version

| Here | Spring Boot / MySQL |
|---|---|
| `InMemoryRepository` | `JpaRepository` + `@Version` column; `insert` = unique index |
| `IdempotencyService` map | `idempotency_keys` table with unique key |
| `ReconciliationService.start` | `@Scheduled(fixedDelay = …)` |
| `MockRazorpayGateway` | `razorpay-java` `RazorpayClient` |
| Service methods | `@RestController` endpoints (`POST /orders`, `POST /payments/capture`, `POST /payments/{id}/refunds`, `POST /webhooks/razorpay`, `GET /customers/{id}/transactions`) |
| key=value webhook body | JSON body, `X-Razorpay-Signature` header |
