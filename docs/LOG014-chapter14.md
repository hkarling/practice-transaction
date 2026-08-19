# LOG014 — 챕터 14: Outbox 패턴

전체 진행도: [`README.md`](../README.md)

## 목표
챕터 10에서 `@TransactionalEventListener(AFTER_COMMIT)`로 "롤백돼도 알림이 안 나가게" 만들었지만, 커밋 이후 실제 발행 시점에 외부 시스템(메시지 브로커)이 다운되어 있으면 여전히 메시지가 소실될 수 있다. Outbox 패턴으로 이 문제를 해결한다.

## 시작 전 짚었던 것
- **검증**: `afterCommit()`(=`@TransactionalEventListener(AFTER_COMMIT)`) 안에서 예외가 나면 **로그만 찍히고 호출자에게 전파되지 않는다.** 즉 이체 API 호출자는 "성공"으로 알지만 실제로는 알림이 조용히 사라짐 — 챕터 10보다 더 위험한 시나리오.
- 근본 원인: DB(트랜잭션 있음)와 메시지 브로커(트랜잭션 없음)에 각각 따로 쓰기 때문 — 둘을 원자적으로 묶을 방법이 없음(챕터 11의 2PC/XA가 풀려던 문제).
- **해법**: 메시지 브로커에 직접 쓰지 않고 같은 로컬 DB의 `outbox` 테이블에 이벤트를 씀 — 비즈니스 데이터 변경과 평범한 ACID로 원자성 보장. 별도 **릴레이(relay)** 프로세스가 테이블을 폴링해서 실제 발행 후 `SENT`로 표시.

## 새 하위패키지 — `app.outbox`

## 핵심 코드

**`domain/OutboxStatus.java`** / **`domain/OutboxEvent.java`**
```java
public enum OutboxStatus {
  PENDING,
  SENT
}

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
public class OutboxEvent {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private Long fromId;
  private Long toId;
  private BigDecimal amount;

  @Enumerated(EnumType.STRING)
  private OutboxStatus status;

  public OutboxEvent(Long fromId, Long toId, BigDecimal amount) {
    this.fromId = fromId;
    this.toId = toId;
    this.amount = amount;
    this.status = OutboxStatus.PENDING;
  }

  public void markSent() {
    this.status = OutboxStatus.SENT;
  }
}
```

**`infra/OutboxEventRepository.java`**
```java
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {
  List<OutboxEvent> findByStatus(OutboxStatus status);
}
```

**`app/outbox/FakeMessageBroker.java`** (특정 이벤트 ID만 골라서 실패시킬 수 있는 가짜 브로커)
```java
@Component
public class FakeMessageBroker {

  private final List<Long> publishedEventIds = new CopyOnWriteArrayList<>();
  private final Set<Long> failingEventIds = ConcurrentHashMap.newKeySet();

  public void failFor(Long eventId) {
    failingEventIds.add(eventId);
  }

  public void publish(Long eventId, String message) {
    if (failingEventIds.contains(eventId)) {
      throw new IllegalStateException("메시지 브로커 연결 실패 (시뮬레이션): eventId=" + eventId);
    }
    publishedEventIds.add(eventId);
  }

  public List<Long> getPublishedEventIds() {
    return publishedEventIds;
  }
}
```

**`app/outbox/TransferWithOutboxService.java`** — 이체 + 이벤트 기록을 하나의 로컬 트랜잭션으로
```java
@RequiredArgsConstructor
@Service
public class TransferWithOutboxService {

  private final TransferService transferService;
  private final OutboxEventRepository outboxEventRepository;

  @Transactional
  public void transferAndRecordEvent(Long fromId, Long toId, BigDecimal amount) {
    transferService.transfer(fromId, toId, amount);
    outboxEventRepository.save(new OutboxEvent(fromId, toId, amount));
  }
}
```
특별한 전파 속성이나 이벤트 리스너 없이, 같은 로컬 DB에 쓰는 두 작업이라 평범한 ACID가 원자성을 보장 — 챕터 8~10에서 씨름했던 "여러 시스템에 걸친 원자성" 문제를 "하나의 DB 안 문제"로 바꿔버리는 게 핵심 아이디어.

**`app/outbox/OutboxEventPublisher.java`** (개별 이벤트 발행, 별도 빈으로 분리 — self-invocation 문제 회피)
```java
@RequiredArgsConstructor
@Component
public class OutboxEventPublisher {

  private final OutboxEventRepository outboxEventRepository;
  private final FakeMessageBroker messageBroker;

  @Transactional
  public void publish(Long eventId) {
    OutboxEvent event = outboxEventRepository.findById(eventId)
        .orElseThrow(() -> new IllegalArgumentException("이벤트 없음: " + eventId));
    messageBroker.publish(event.getId(), "이체 완료: " + event.getFromId() + " -> " + event.getToId());
    event.markSent();
  }
}
```

**`app/outbox/OutboxRelay.java`** (최종 — 이벤트별 개별 트랜잭션)
```java
@RequiredArgsConstructor
@Component
public class OutboxRelay {

  private final OutboxEventRepository outboxEventRepository;
  private final OutboxEventPublisher outboxEventPublisher;

  public void relayPendingEvents() {
    List<OutboxEvent> pending = outboxEventRepository.findByStatus(OutboxStatus.PENDING);
    for (OutboxEvent event : pending) {
      try {
        outboxEventPublisher.publish(event.getId());
      } catch (Exception e) {
        // 이 이벤트는 PENDING으로 남음 — 다음 릴레이 실행 때 재시도됨
      }
    }
  }
}
```
(버그 버전엔 `relayPendingEvents()` 전체가 하나의 `@Transactional`이었고, `messageBroker.publish()` + `event.markSent()`가 반복문 안에 그대로 있었음 — 개별 빈 분리도, `try/catch`도 없었음.)

**`test/app/outbox/OutboxRelayTest.java`**
```java
@SpringBootTest
class OutboxRelayTest extends AbstractIntegrationTest {

  @Autowired
  private OutboxRelay outboxRelay;

  @Autowired
  private OutboxEventRepository outboxEventRepository;

  @Autowired
  private FakeMessageBroker messageBroker;

  @Test
  @DisplayName("한 이벤트 발행이 실패해도 다른 이벤트는 영향받지 않아야 한다")
  void oneFailureShouldNotBlockOtherEvents() {
    OutboxEvent event1 = outboxEventRepository.save(new OutboxEvent(1L, 2L, new BigDecimal("1000")));
    OutboxEvent event2 = outboxEventRepository.save(new OutboxEvent(3L, 4L, new BigDecimal("2000")));
    messageBroker.failFor(event1.getId());

    outboxRelay.relayPendingEvents();

    OutboxEvent reloadedEvent2 = outboxEventRepository.findById(event2.getId()).orElseThrow();
    assertThat(reloadedEvent2.getStatus()).isEqualTo(OutboxStatus.SENT);
  }
}
```

## 재현 및 수정 결과
- **버그 버전(배치 전체 트랜잭션)**: `event1` 발행 실패 → 예외가 반복문을 그대로 빠져나감 → `event2`는 시도조차 안 되고 `PENDING`으로 남음. 테스트 실패.
- **수정 버전(이벤트별 개별 트랜잭션 + `try/catch`)**: `event1`은 실패해서 `PENDING`으로 남지만(나중에 재시도 가능), `event2`는 정상적으로 `SENT`. 테스트 통과.

## 한계점 (실무 적용 시 점검 필요)

**1) 릴레이는 지금 자동으로 반복 실행되지 않는다**
`OutboxRelay.relayPendingEvents()`는 그냥 메서드일 뿐, `@Scheduled` 같은 걸 안 붙여서 테스트에서 수동 호출로만 실행됨. 실무에서 실제로 주기적으로 폴링하게 하려면 스케줄러(또는 CDC 트리거)를 별도로 붙여야 함 — 이 프로젝트에선 안 함.

**2) PENDING 재시도는 의도된 동작이지만 "적어도 한 번(at-least-once)" 전달만 보장한다**
`messageBroker.publish()`는 성공했는데 그 직후 `event.markSent()`가 커밋되기 전에 프로세스가 죽으면, DB엔 여전히 `PENDING`으로 남아 다음 릴레이 실행 때 **이미 성공적으로 보낸 메시지를 또 보내게 된다.** Outbox는 "메시지가 소실되지 않는다"는 보장이지 "딱 한 번만 보내진다"는 보장이 아님 — 수신 쪽(또는 챕터 15처럼 요청 자체)이 중복을 스스로 걸러낼 수 있어야 함(멱등성).

**3) 무한 재시도 / 죽은 편지함(dead letter) 처리 없음**
어떤 이벤트가 영구적으로 실패하면(브로커가 계속 다운되거나 메시지 자체가 잘못됐거나) 지금 설계는 릴레이가 돌 때마다 무한정 재시도한다. 실무에서는 보통 "재시도 횟수" 필드를 두고 N번 넘게 실패하면 dead letter 상태로 옮겨 더 이상 자동 재시도 안 하고 사람이 확인하게 만든다 — 이 프로젝트에선 구현하지 않음.

## 완료 체크리스트
- [x] `OutboxEvent`/`OutboxStatus`/`OutboxEventRepository` + `FakeMessageBroker` 작성 (`app.outbox` 신설)
- [x] `TransferWithOutboxService` — 이체+이벤트 기록 원자적 처리
- [x] `OutboxRelay`(배치 전체 트랜잭션, 버그 버전) 작성, 테스트로 재현
- [x] `OutboxEventPublisher` 분리 + 이벤트별 개별 트랜잭션으로 수정, 통과 확인
- [x] `afterCommit()` 예외가 호출자에게 전파 안 됨을 공식 자료로 확인
- [x] 이 로그 문서 작성

## ADR

**Decision**
- 이벤트 발행은 메시지 브로커에 직접 쓰지 않고, 로컬 DB의 `outbox` 테이블에 비즈니스 트랜잭션과 원자적으로 기록한다.
- 실제 발행은 별도 릴레이(`OutboxRelay`)가 담당하며, **이벤트 하나당 독립된 트랜잭션**으로 처리한다.

**Drivers**
- `@TransactionalEventListener(AFTER_COMMIT)`(챕터 10)만으로는 "발행 자체의 실패"까지는 못 막는다는 걸 확인 — Outbox는 이 남은 구멍을 메움.
- 배치 전체를 한 트랜잭션으로 처리하면 하나의 실패가 다른 정상 이벤트까지 막아버리는 실무적으로 흔한 실수를 직접 겪음.

**Alternatives considered**
- 메시지 브로커에 직접 발행 + 재시도 로직을 애플리케이션에서 직접 구현 — 기각: 재시도 상태를 어딘가에 저장해야 하는데, 그게 결국 Outbox 테이블을 재발명하는 셈.
- CDC(Change Data Capture, 예: Debezium)로 트랜잭션 로그를 직접 읽어 발행 — 기각: 이 프로젝트 규모에는 과한 인프라, 폴링 방식 릴레이로 핵심 개념은 충분히 익힘.

**Consequences**
- `OutboxEvent`가 `SENT`로 표시되기 전까지는 큐에 계속 남아 폴링 대상이 됨 — 실무에서는 오래된 `SENT` 레코드를 주기적으로 정리(archive/delete)하는 배치가 추가로 필요.
- 릴레이가 "적어도 한 번"(at-least-once) 발행을 보장하므로, 수신 측(메시지 브로커 구독자)은 중복 수신에 대비해야 함 — 챕터 15(멱등성 설계)로 직결.

**Follow-ups**
- 챕터 15(멱등성 설계)에서 "적어도 한 번" 전달로 인한 중복 처리를 다룬다 — 이 챕터의 릴레이가 재시도할 때 이미 성공한 발행을 중복 전송할 가능성(예: 발행은 성공했는데 `markSent()` 커밋 직전에 죽는 경우)까지 포함.
