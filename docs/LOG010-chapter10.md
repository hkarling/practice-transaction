# LOG010 — 챕터 10: 이벤트 발행 타이밍 (`@TransactionalEventListener`)

전체 진행도: [`README.md`](../README.md)

## 목표
이체 완료 시 알림(SMS/푸시 등)을 보내는 상황에서, **이벤트를 언제 처리하느냐**에 따라 생기는 버그를 재현하고 고친다.

## 시작 전 짚었던 것
- 일반 `@EventListener`는 이벤트 발행 즉시, 같은 스레드에서 동기 실행됨 — 트랜잭션이 아직 커밋되기 전.
- 알림 발송 같은 건 보통 외부 부수효과라 DB 트랜잭션에 안 묶이고, 한 번 나가면 롤백으로도 취소가 안 됨.
- 이벤트 발행 뒤 트랜잭션이 롤백되면: 실제 이체는 취소됐는데 "이체 완료" 알림은 이미 나가버리는 심각한 신뢰 문제 발생.
- 해결: `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` — 트랜잭션이 실제로 커밋된 뒤에만 리스너 실행, 롤백되면 아예 실행 안 됨.
- 알림 발송은 실제 외부 시스템 대신 **인메모리 가짜 게이트웨이**로 시뮬레이션 — 자바 리스트 추가는 DB 롤백으로 취소가 안 된다는 점이 실제 외부 API 호출과 같은 성격이라 정확한 비유가 됨.

## 새 하위패키지 — `app.event`
이벤트 발행/구독이라는 별개의 관심사라 `app.transfer`/`app.propagation`/`app.audit`과 나란히 `app.event`로 분리.

## 핵심 코드

**`app/event/TransferCompletedEvent.java`**
```java
public record TransferCompletedEvent(Long fromId, Long toId, BigDecimal amount) {
}
```

**`app/event/FakeNotificationGateway.java`**
```java
@Component
public class FakeNotificationGateway {

  private final List<TransferCompletedEvent> sentNotifications = new CopyOnWriteArrayList<>();

  public void send(TransferCompletedEvent event) {
    sentNotifications.add(event);
  }

  public List<TransferCompletedEvent> getSentNotifications() {
    return sentNotifications;
  }
}
```

**`app/event/TransferNotificationListener.java`** (최종 — `AFTER_COMMIT`)
```java
@RequiredArgsConstructor
@Component
public class TransferNotificationListener {

  private final FakeNotificationGateway notificationGateway;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void handle(TransferCompletedEvent event) {
    notificationGateway.send(event);
  }
}
```
(버그 버전엔 `@EventListener`였음.)

**`app/event/TransferEventService.java`**
```java
@RequiredArgsConstructor
@Service
public class TransferEventService {

  private final TransferService transferService;
  private final ApplicationEventPublisher eventPublisher;

  @Transactional
  public void transferAndNotify(Long fromId, Long toId, BigDecimal amount, boolean simulateFailureAfterEvent) {
    transferService.transfer(fromId, toId, amount);
    eventPublisher.publishEvent(new TransferCompletedEvent(fromId, toId, amount));

    if (simulateFailureAfterEvent) {
      throw new IllegalStateException("이후 처리 중 실패 (시뮬레이션)");
    }
  }
}
```
`simulateFailureAfterEvent` 플래그로 "이체 성공 + 이벤트 발행 뒤, 그다음 단계에서 실패"하는 상황을 의도적으로 만든다 — 지금까지는 존재하지 않는 계좌 ID 같은 자연스러운 실패로 재현했지만, 이번엔 이벤트 발행 이후 시점의 실패가 필요해 플래그로 주입.

**`test/app/event/TransferEventServiceTest.java`**
```java
@SpringBootTest
class TransferEventServiceTest extends AbstractIntegrationTest {

  @Autowired
  private TransferEventService transferEventService;

  @Autowired
  private AccountRepository accountRepository;

  @Autowired
  private FakeNotificationGateway notificationGateway;

  @Test
  @DisplayName("트랜잭션이 롤백되면 AFTER_COMMIT 리스너는 실행되지 않는다")
  void notificationIsSentEvenWhenTransactionRollsBack() {
    Account from = accountRepository.save(new Account("alice", new BigDecimal("10000")));
    Account to = accountRepository.save(new Account("bob", new BigDecimal("0")));

    assertThatThrownBy(
        () -> transferEventService.transferAndNotify(from.getId(), to.getId(), new BigDecimal("3000"), true))
        .isInstanceOf(IllegalStateException.class);

    assertThat(notificationGateway.getSentNotifications()).isEmpty();
  }

  @Test
  @DisplayName("트랜잭션이 커밋되면 알림이 정상적으로 나간다")
  void notificationIsSentWhenTransactionCommits() {
    Account from = accountRepository.save(new Account("carol", new BigDecimal("10000")));
    Account to = accountRepository.save(new Account("dave", new BigDecimal("0")));
    int before = notificationGateway.getSentNotifications().size();

    transferEventService.transferAndNotify(from.getId(), to.getId(), new BigDecimal("1000"), false);

    assertThat(notificationGateway.getSentNotifications()).hasSize(before + 1);
  }
}
```

## 재현 및 수정 결과
- **버그 버전(`@EventListener`)**: `notificationIsSentEvenWhenTransactionRollsBack` 실패 — `Expecting empty but was: [TransferCompletedEvent[fromId=1, toId=2, amount=3000]]`. 트랜잭션이 롤백됐는데도 알림이 이미 나가있었음.
- **수정 버전(`@TransactionalEventListener(AFTER_COMMIT)`)**: 두 테스트 모두 통과 — 롤백 시 알림 0건, 커밋 시 알림 정상 발송.

## 시행착오 — 공유 싱글턴 빈의 테스트 간 상태 누적
`FakeNotificationGateway`는 스프링 빈(싱글턴)이라 내부 리스트가 테스트 메서드 사이에 공유된다 — 챕터 5에서 `AbstractIntegrationTest`로 Spring 컨텍스트를 캐싱해 재사용하게 만든 부작용. 두 번째 테스트(`notificationIsSentWhenTransactionCommits`)는 이 점을 감안해 "이전 대비 몇 개 늘었는지"(`before + 1`)로 검증 — 절대 개수(`hasSize(1)`)로 검증했다면 실행 순서에 따라 깨지는 테스트가 됐을 것.

## 완료 체크리스트
- [x] `TransferCompletedEvent`/`FakeNotificationGateway`/`TransferNotificationListener`(버그 버전)/`TransferEventService` 작성 (`app.event` 신설)
- [x] 테스트로 재현 (롤백돼도 알림이 나가는 것 확인)
- [x] `AFTER_COMMIT`으로 수정, 롤백/커밋 양쪽 케이스 확인
- [x] 이 로그 문서 작성

## ADR

**Decision**
- 이체 완료 알림은 `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`로 처리한다.
- 알림 발송(외부 부수효과)은 인메모리 가짜 게이트웨이로 시뮬레이션 — DB에 안 묶이는 부수효과의 성격을 정확히 반영.
- 이벤트 관련 코드는 `app.event` 하위패키지로 분리.

**Drivers**
- 외부 부수효과(알림, 결제 게이트웨이 호출 등)는 트랜잭션 롤백으로 취소가 안 되므로, 반드시 커밋 확정 후에만 트리거돼야 함.
- `@EventListener`의 기본 동작(즉시·동기 실행)이 이 요구사항과 안 맞는다는 걸 실제로 겪어야 다음에 같은 실수를 안 함.

**Alternatives considered**
- 알림 발송을 별도 스레드/메시지 큐로 비동기 처리 — 기각: 지금 필요한 건 "커밋 후에만 실행"이라는 타이밍 보장이지 비동기 자체가 아님. `@TransactionalEventListener`로 충분.
- `TransferService.transfer()` 안에서 직접 알림 호출 — 기각: 관심사 분리 위반, 이벤트 기반으로 느슨하게 결합하는 게 낫다고 판단.

**Consequences**
- `AFTER_COMMIT` 리스너는 트랜잭션이 없을 때 발행된 이벤트에 대해서는 기본적으로 실행되지 않는다(`fallbackExecution` 기본값 `false`) — 항상 `@Transactional` 컨텍스트 안에서 이벤트를 발행해야 한다는 전제가 생김.
- 테스트에서 공유 싱글턴 빈(`FakeNotificationGateway`)의 상태 누적에 항상 주의해야 함 — 절대 개수보다 "증가량"으로 검증하는 습관 필요.

**Follow-ups**
- Phase 2가 이걸로 완료됨 (챕터 7~10). Phase 3(분산 트랜잭션 — 2PC/SAGA/Outbox/멱등성)로 진행.
- Outbox 패턴(챕터 14)에서 "이벤트를 트랜잭션과 원자적으로 남기는" 더 견고한 방식을 다룰 예정 — `AFTER_COMMIT`과의 차이 비교해볼 가치 있음.
