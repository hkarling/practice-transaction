# LOG009 — 챕터 9: 감사 로그 — 트랜잭션 실패해도 로그는 남아야 함 (`REQUIRES_NEW` 실전)

전체 진행도: [`README.md`](../README.md)

## 목표
챕터 8과 정반대 케이스 — 이번엔 `REQUIRES_NEW`가 정당하게 필요한 상황. 이체가 성공하든 실패하든 "시도했다"는 감사 로그는 항상 남아야 한다.

## 시작 전 짚었던 것
감사 로그를 `REQUIRED`(기본값)로 만들면, 바깥 트랜잭션이 이미 예외로 롤백 대상이 표시된 상태에서 로그 저장이 합류하게 된다. `try/catch`로 예외를 잡아도, 이미 죽은 트랜잭션에 합류한 로그 저장은 같이 롤백된다 — "예외를 잡았으니 괜찮겠지"가 안 통하는 대표 사례.

## 새 하위패키지 — `app.audit`
감사 로그는 "이체"의 변형이 아니라 별개의 횡단 관심사라 `app.transfer`/`app.propagation`과 나란히 `app.audit`으로 분리 (챕터 7에서 정리한 "필요한 만큼만 그때그때 나눈다"는 원칙 재적용).

## 핵심 코드

**`domain/AuditLog.java`**
```java
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
public class AuditLog {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String action;
  private Long fromId;
  private Long toId;
  private BigDecimal amount;
  private boolean success;
  private String message;

  public AuditLog(String action, Long fromId, Long toId, BigDecimal amount, boolean success, String message) {
    this.action = action;
    this.fromId = fromId;
    this.toId = toId;
    this.amount = amount;
    this.success = success;
    this.message = message;
  }
}
```

**`infra/AuditLogRepository.java`**
```java
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
}
```

**`app/audit/AuditLogService.java`** (최종 — `REQUIRES_NEW`)
```java
@RequiredArgsConstructor
@Service
public class AuditLogService {

  private final AuditLogRepository auditLogRepository;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void log(String action, Long fromId, Long toId, BigDecimal amount, boolean success, String message) {
    AuditLog auditLog = new AuditLog(action, fromId, toId, amount, success, message);
    auditLogRepository.save(auditLog);
  }
}
```
(버그 버전엔 `@Transactional`만 있었음 — 기본값 `REQUIRED`.)

**`app/audit/AuditedTransferService.java`**
```java
@RequiredArgsConstructor
@Service
public class AuditedTransferService {

  private final TransferService transferService;
  private final AuditLogService auditLogService;

  @Transactional
  public void transfer(Long fromId, Long toId, BigDecimal amount) {
    try {
      transferService.transfer(fromId, toId, amount);
      auditLogService.log("TRANSFER", fromId, toId, amount, true, "성공");
    } catch (Exception e) {
      auditLogService.log("TRANSFER", fromId, toId, amount, false, e.getMessage());
      throw e;
    }
  }
}
```

**`test/app/audit/AuditedTransferServiceTest.java`**
```java
@SpringBootTest
class AuditedTransferServiceTest extends AbstractIntegrationTest {

  @Autowired
  private AuditedTransferService auditedTransferService;

  @Autowired
  private AccountRepository accountRepository;

  @Autowired
  private AuditLogRepository auditLogRepository;

  @Test
  @DisplayName("이체가 실패해도 감사 로그는 남아야 한다")
  void auditLogShouldSurviveWhenTransferFails() {
    Account from = accountRepository.save(new Account("alice", new BigDecimal("10000")));
    Long invalidToId = 999_999L;

    assertThatThrownBy(() -> auditedTransferService.transfer(from.getId(), invalidToId, new BigDecimal("3000")))
        .isInstanceOf(IllegalArgumentException.class);

    long logCount = auditLogRepository.count();
    assertThat(logCount).isEqualTo(1);
  }
}
```

## 재현 및 수정 결과
- **버그 버전(`REQUIRED`)**: `logCount == 0` — 이체 실패 시 `catch`에서 로그를 남기려 시도했지만, 이미 롤백 대상으로 표시된 같은 트랜잭션에 합류해 로그 저장도 같이 사라짐.
- **수정 버전(`REQUIRES_NEW`)**: `logCount == 1` — 바깥 트랜잭션이 롤백되든 말든, 로그는 완전히 독립된 트랜잭션에서 커밋되어 살아남음.

## 챕터 8과의 대비 — `REQUIRES_NEW` 판단 기준 완성
| | 챕터 8 (수수료) | 챕터 9 (감사 로그) |
|---|---|---|
| 부가 작업 | 수수료 징수 | 로그 기록 |
| 메인 작업 실패 시 정답 | **같이 롤백**돼야 함 (수수료는 이체 성공이 전제) | **독립적으로 남아야** 함 (실패 사실 자체가 기록 대상) |
| 올바른 전파 속성 | `REQUIRED` | `REQUIRES_NEW` |

**판단 기준**: 그 부가 작업이 "메인 작업의 결과에 의존하는 효과"인가(→ `REQUIRED`로 같이 묶기), 아니면 "메인 작업의 성패 자체를 기록/알리는 관찰자"인가(→ `REQUIRES_NEW`로 분리하기)로 나뉜다.

## 완료 체크리스트
- [x] `AuditLog` 엔티티 + 리포지토리 (`app.audit` 신설)
- [x] `AuditLogService`(`REQUIRED`, 버그 버전) + `AuditedTransferService` 작성
- [x] 테스트로 재현 (`logCount == 0`)
- [x] `REQUIRES_NEW`로 수정, 통과 확인 (`logCount == 1`)
- [x] 이 로그 문서 작성

## ADR

**Decision**
- 감사 로그(`AuditLogService.log`)는 `REQUIRES_NEW`로 메인 트랜잭션과 완전히 분리한다.
- 감사 로그는 별도 하위패키지 `app.audit`으로 분리.

**Drivers**
- 감사 로그의 존재 목적 자체가 "무슨 일이 있었는지(성공이든 실패든) 기록"이므로, 메인 트랜잭션의 운명과 무관해야 함.
- `catch`로 예외를 잡는 것과 트랜잭션이 살아있는 것은 별개라는 걸 직접 확인 — 실무에서 흔히 오해하는 지점.

**Alternatives considered**
- 로그를 트랜잭션 밖에서 별도 스레드/비동기로 처리 — 기각: 지금 필요한 건 "동기적으로, 하지만 독립적으로 커밋되는 것"이라 `REQUIRES_NEW`로 충분하고 비동기는 불필요한 복잡도 추가.

**Consequences**
- `AuditLogService.log()` 호출마다 새 물리 트랜잭션(새 DB 커넥션 획득)이 열린다 — 호출량이 많아지면 커넥션 풀 부담이 커질 수 있음 (Phase 4, 챕터 17 커넥션 풀 고갈 챕터와 연결될 주제).
- 이제 `REQUIRED` vs `REQUIRES_NEW` 판단 기준(위 표)이 이후 챕터에서 유사한 "부가 작업" 설계 시 재사용 가능.

**Follow-ups**
- 챕터 10(이벤트 발행 타이밍, `@TransactionalEventListener`)에서 "메인 트랜잭션 커밋 후에만 실행되어야 하는 부가 작업"이라는 또 다른 패턴을 다룬다 — `REQUIRES_NEW`와 어떻게 다른지 비교해볼 가치 있음.
- 감사 로그 호출이 늘어날 때의 커넥션 풀 영향은 챕터 17에서 재확인.
