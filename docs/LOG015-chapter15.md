# LOG015 — 챕터 15: 멱등성 설계

전체 진행도: [`README.md`](../README.md)

## 목표
챕터 14 마지막에 예고한 문제 — Outbox 릴레이가 "메시지는 보냈는데 `markSent()` 커밋 직전에 죽는" 경우 재시도하면 같은 메시지가 두 번 발행될 수 있다. 이런 "적어도 한 번(at-least-once)" 전달에서는 같은 요청이 중복으로 들어와도 실제 효과는 한 번만 나야 한다 — 멱등성(Idempotency).

## 시작 전 짚었던 것
- 실무 시나리오: 클라이언트가 이체 요청 후 응답을 못 받음(타임아웃) → 성공/실패 여부를 모르니 같은 요청을 재시도 → 멱등성 보호가 없으면 이체가 두 번 실행됨.
- 해법: 요청마다 고유한 **멱등성 키**를 발급하고, 서버가 "이 키로 이미 처리한 적 있나"를 확인.
- 중요한 함정: 확인(조회)·실제 처리·키 기록이 **하나의 원자적 단위**여야 함 — 챕터 14의 Outbox와 같은 원리.

## 새 하위패키지 — `app.idempotency`

## 핵심 코드

**`domain/IdempotencyKey.java`**
```java
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyKey {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(unique = true)
  private String requestKey;

  public IdempotencyKey(String requestKey) {
    this.requestKey = requestKey;
  }
}
```

**`infra/IdempotencyKeyRepository.java`**
```java
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {
  boolean existsByRequestKey(String requestKey);
}
```

**`app/idempotency/IdempotentTransferService.java`** (최종 — 키 확인/기록 포함)
```java
@RequiredArgsConstructor
@Service
public class IdempotentTransferService {

  private final TransferService transferService;
  private final IdempotencyKeyRepository idempotencyKeyRepository;

  @Transactional
  public void transfer(String idempotencyKey, Long fromId, Long toId, BigDecimal amount) {
    if (idempotencyKeyRepository.existsByRequestKey(idempotencyKey)) {
      return;
    }
    transferService.transfer(fromId, toId, amount);
    idempotencyKeyRepository.save(new IdempotencyKey(idempotencyKey));
  }
}
```
(버그 버전엔 `idempotencyKey` 파라미터를 받기만 하고 `transferService.transfer(fromId, toId, amount)`만 그대로 호출 — 확인/기록 로직 자체가 없었음.)

**`test/app/idempotency/IdempotentTransferServiceTest.java`**
```java
@SpringBootTest
class IdempotentTransferServiceTest extends AbstractIntegrationTest {

  @Autowired
  private IdempotentTransferService idempotentTransferService;

  @Autowired
  private AccountRepository accountRepository;

  @Test
  @DisplayName("같은 멱등성 키로 재시도해도 이체는 한 번만 처리되어야 한다")
  void duplicateRequestWithSameKeyShouldBeProcessedOnce() {
    Account from = accountRepository.save(new Account("alice", new BigDecimal("10000")));
    Account to = accountRepository.save(new Account("bob", new BigDecimal("0")));
    String idempotencyKey = UUID.randomUUID().toString();

    idempotentTransferService.transfer(idempotencyKey, from.getId(), to.getId(), new BigDecimal("3000"));
    idempotentTransferService.transfer(idempotencyKey, from.getId(), to.getId(), new BigDecimal("3000")); // 재시도 시뮬레이션

    Account reloaded = accountRepository.findById(from.getId()).orElseThrow();
    assertThat(reloaded.getBalance()).isEqualByComparingTo("7000");
  }
}
```

## 재현 및 수정 결과
- **버그 버전**: `4000.00` (`10000 - 3000 - 3000`) — 같은 키로 재시도했는데도 이체가 두 번 실행됨. 커밋해서 시행착오를 기록으로 남긴 뒤 진행.
- **수정 버전**: `7000.00` — 두 번째 호출은 이미 존재하는 키를 발견하고 조용히 종료, 이체는 한 번만 실행.

## 참고 — 다단계 흐름에서 멱등성 키를 어떻게 나눌까
"조회 → 주문 → 결제 → 배송" 같은 다단계 흐름에서 단계마다 멱등성 키가 다 필요한지에 대한 질문에서 정리.

**원칙: "상태를 바꾸는 작업"마다 하나씩**
- **조회는 키가 필요 없다.** 읽기 전용이라 몇 번을 반복해도 부작용이 없음(자연히 멱등함) — 별도 보호 장치 자체가 불필요.
- **주문/결제/배송은 각각 별개의 상태 변경 작업이라, 각자 자기만의 멱등성 키가 필요하다.** 하나로 묶으면, 예를 들어 "결제"가 타임아웃나서 재시도해야 하는데 "이미 처리된 키"로 오인해서 결제 자체를 건너뛰는 사고가 날 수 있음. 특히 결제는 이중 처리가 가장 치명적인 영역이라 독립적으로 확실하게 보호해야 함.
- 핵심은 **"재시도할 때 같은 키를 재사용한다"**는 것 — 매번 새로 키를 발급하면 멱등성 자체가 무의미해짐. 클라이언트가 "결제하기" 클릭 시 키를 한 번 생성해 보관해뒀다가, 타임아웃으로 재시도할 때 **똑같은 키를 다시 보내야** 서버가 알아챌 수 있음.

**계층 구조 — 상위 식별자 + 단계별 키**
전체 흐름을 하나로 묶는 상위 식별자(우리 SAGA 챕터의 `sagaId`가 정확히 이 역할)와, 각 단계 자신의 멱등성 키를 따로 둔다. 처음엔 `sagaId + 단계이름`을 문자열로 이어붙이는 방식(`"orderId:payment"`)을 예시로 들었으나, 논의 끝에 **별도 컬럼으로 분리하는 게 더 나은 설계**로 결론:

```java
@Entity
public class IdempotencyKey {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String sagaId;   // 전체 흐름 식별자 (상관관계 ID)
  private String step;     // 이 흐름 안에서의 세부 단계

  // (sagaId, step) 복합 유니크 제약
}
```

**문자열 조합 대신 컬럼 분리가 나은 이유**:
- 조회가 쉬워짐 — `WHERE saga_id = ?`로 "이 흐름의 모든 단계가 지금 어떤 상태인지" 한 번에 볼 수 있음 (Phase 4의 운영 모니터링 관점에서 중요). 문자열 조합이면 `LIKE 'orderId:%'` 같은 지저분한 쿼리가 필요.
- 구분자 선택, ID 안에 구분자가 우연히 포함되는 경우의 이스케이핑 같은 문자열 조립의 함정을 아예 피함.
- `(sagaId, step)` 복합 유니크 제약이 문자열 하나에 유니크를 거는 것보다 명시적이고 실수할 여지가 적음.

**이 프로젝트와의 연결점**: 챕터 12~13(SAGA)에서 쓴 `sagaId`가 정확히 "상위 식별자" 역할이었는데, 각 단계(출금 커맨드, 보상 커맨드)에 개별 멱등성 키를 안 씌워뒀다는 게 이미 지적돼 있음(아래 Follow-ups) — 실제 분산 환경이었다면 이 챕터에서 정리한 `(sagaId, step)` 구조를 SAGA의 각 단계에 그대로 적용했어야 함.

## 완료 체크리스트
- [x] `IdempotencyKey`/`IdempotencyKeyRepository` + `IdempotentTransferService`(버그 버전) 작성 (`app.idempotency` 신설)
- [x] 테스트로 재현 (`4000.00`, 중복 처리 확인)
- [x] 버그 상태 커밋
- [x] 키 확인/기록 로직 추가, 수정 확인 (`7000.00`)
- [x] 이 로그 문서 작성

## ADR

**Decision**
- 요청 단위로 클라이언트가 발급한 멱등성 키를 받아, 서버가 "이미 처리된 키인지" 확인 후 처리한다.
- 키 확인·비즈니스 처리·키 기록을 하나의 로컬 트랜잭션으로 원자적으로 묶는다.

**Drivers**
- 챕터 14의 Outbox 릴레이가 만드는 "적어도 한 번" 전달 특성상, 중복 요청은 피할 수 없는 전제로 받아들이고 수신 측에서 걸러야 함.
- 확인과 기록이 원자적이지 않으면(따로 트랜잭션) 그 사이의 경쟁 상태로 멱등성 보장이 깨질 수 있음 — 챕터 4~7에서 배운 "확인 후 처리" 패턴의 경쟁 상태 문제가 여기서도 그대로 적용됨.

**Alternatives considered**
- 클라이언트 쪽에서만 중복 요청을 막기(예: 버튼 비활성화) — 기각: 네트워크 재시도, 클라이언트 재실행 등 클라이언트가 통제 못 하는 중복 상황이 많아 서버 측 보장이 필수.
- 멱등성 키에 처리 결과까지 저장해서 재시도 시 캐시된 응답을 그대로 반환 — 이번엔 미구현(단순 "이미 처리됨, 무시"만 함), 실무에서는 응답 캐싱까지 하는 경우가 많음 — follow-up으로 남김.

**Consequences**
- `IdempotencyKey` 테이블이 계속 쌓임 — 실무에서는 일정 기간(예: 24시간) 지난 키는 정리하는 배치가 필요.
- 지금 구현은 **동시에** 같은 키로 두 요청이 거의 동시에 들어오는 경우(순차 재시도가 아니라 진짜 병렬 요청)까지는 검증 안 함 — `requestKey`에 unique 제약이 있어 DB 레벨에서 최소한 이중 처리는 막히겠지만(둘 중 하나는 유니크 제약 위반으로 실패), 그 실패를 우아하게 처리하는 로직은 없음.

**Follow-ups**
- 동시 중복 요청(순차 재시도가 아닌 진짜 동시 요청) 시나리오는 별도로 테스트/보완해볼 가치 있음 — `existsByRequestKey` 체크 자체도 챕터 4~7에서 배운 것과 같은 "확인 후 처리" 경쟁 상태에 노출될 수 있음.
- 멱등성 키에 처리 결과를 캐싱해서 재시도 시 그 결과를 그대로 반환하는 방식으로 확장 가능.
- Phase 3(분산 트랜잭션, 챕터 11~15)이 이걸로 완료됨. Phase 4(운영 관점 — 락 모니터링/커넥션 풀/동시성 테스트 자동화)로 진행.

## Phase 3 종합 정리

Phase 4로 넘어가기 전, 챕터 11~15를 관통하는 큰 그림을 정리.

### 핵심은 "롤백"이 아니라 "원자성을 흉내내는 것"
챕터 11(2PC)에서 확인한 것: **진짜 원자성(전부 성공 아니면 전부 무효)은 분산 환경에서 사실상 못 쓴다.** 너무 비싸고(블로킹 문제, SPOF), 이기종 시스템 간엔 애초에 안 된다(XA 미지원 시스템이 대부분).

그래서 SAGA(챕터 12~13)는 **원자성 자체를 포기한다.** 각 단계는 진짜로, 영구적으로 커밋된다. 실패하면 DB가 자동으로 되돌려주는 게 아니라, 애플리케이션이 명시적으로 반대 방향 작업(보상 트랜잭션)을 **사후에 새로 실행**해서 되돌린다.

**"진짜 롤백(ACID)"과 "보상(SAGA)"은 최종 상태는 비슷해 보여도 메커니즘이 다르다**:
| | 진짜 롤백 (ACID) | 보상 (SAGA) |
|---|---|---|
| 중간 상태 노출 | 절대 안 됨 (커밋 전까진 아무도 못 봄) | 될 수 있음 (각 단계가 진짜로 커밋되므로) |
| 되돌리는 방법 | DB 엔진이 자동으로 없었던 일로 만듦 | 애플리케이션이 반대 작업을 새로 실행 |
| 비용 | 여러 시스템에 걸치면 사실상 불가능(2PC의 한계) | 각 단계가 독립적이라 확장 가능 |

나머지 두 챕터는 이 타협이 만드는 부작용을 막는 장치:
- **챕터 14(Outbox)**: "그나마 원자성이 가능한 부분(로컬 DB 안)"으로 문제 범위를 좁혀서, 거기서만큼은 진짜 ACID 원자성을 되찾는 전략.
- **챕터 15(멱등성)**: 원자성을 포기한 대가로 생기는 "적어도 한 번 재시도"가 중복 실행을 일으키지 않게 막는 방어막.

**결론**: "한 단계 실패 시 전부 롤백"이 아니라, *"진짜 원자성은 포기하고, (1) 실패하면 보상으로 되돌리기 + (2) 되돌릴 수 있는 부분은 최대한 원자적으로 좁혀두기 + (3) 재시도로 인한 중복은 따로 막기"*라는 세 가지 전략의 조합이 Phase 3의 핵심.

### SAGA의 숨은 대가 — 원자성뿐 아니라 격리성(Isolation)도 포기한다
위 표에서 짚었듯, SAGA는 각 단계가 진짜로 커밋되기 때문에 **일시적으로 불일치한 중간 상태가 실제로 존재하고, 다른 관찰자가 그걸 볼 수 있다.** 진짜 트랜잭션이었다면 격리성(ACID의 "I") 덕분에 이런 일이 없었을 것 — SAGA는 원자성과 함께 격리성도 같이 내준 셈.

**구체적 위험**: 챕터 12의 출금→입금 SAGA에서, 출금이 커밋된 직후부터 입금이 완료(혹은 보상)되기 전까지 `from` 계좌는 이미 줄어든 잔액으로 DB에 존재한다. 만약 이 순간에 챕터 7의 "일일 한도" 체크가 이 중간 상태의 잔액을 근거로 판단해버리면, 나중에 이 SAGA가 보상(환불)되고 나서 보면 그 판단 자체가 틀린 전제 위에서 이뤄진 셈이 된다.

**완화책** (완벽한 해법은 없음, 트레이드오프):
1. **시맨틱 락(semantic locking)** — 계좌에 "지금 SAGA 진행 중" 상태 플래그를 둬서, 다른 작업이 이를 보고 대기하거나 거부하게 함.
2. **같은 리소스를 건드리는 SAGA를 직렬화** — 같은 계좌 대상 SAGA들을 큐 등으로 순서 강제. (챕터 6의 비관적 락과 발상은 비슷하지만, SAGA 단계 사이에 락을 오래 들고 있으면 SAGA의 장점인 "느슨한 결합·짧은 락 보유"가 사라지는 트레이드오프가 있음.)
3. **민감한 판단(한도 체크 등)은 SAGA 중간 상태를 신뢰하지 않고, 확실히 끝난 뒤의 값만 사용하도록 설계.**
4. **아예 감수하기** — 많은 실무 시스템에서 이 정도의 짧은 불일치는 최종 일관성 관점에서 허용 가능한 트레이드오프로 받아들이고, 정말 민감한 부분만 별도로 보호함.
