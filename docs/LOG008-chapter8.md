# LOG008 — 챕터 8: 수수료 트랜잭션 — 부분 실패 시 롤백 범위

전체 진행도: [`README.md`](../README.md)

## 목표
이체에 수수료가 붙는 상황을 구현한다. 이번엔 동시성이 아니라 **트랜잭션 경계/롤백 범위**가 주제 — 챕터 3에서 배운 `REQUIRES_NEW`를 잘못된 곳에 쓰면 어떻게 되는지 직접 겪어본다.

## 시작 전 짚었던 것
- 감사 로그처럼 "메인 작업이 실패해도 남아야 하는 것"엔 `REQUIRES_NEW`가 맞다(챕터 3). 하지만 **수수료 징수는 이체의 일부**라 이체가 실패하면 수수료도 같이 취소돼야 정상.
- "수수료는 무조건 받아야 하니까"라는 그럴듯한 이유로 `REQUIRES_NEW`를 잘못 쓰면: 수수료는 독립적으로 즉시 커밋되고, 그 뒤 실제 이체가 실패해도 이미 걷은 수수료는 롤백 안 됨 — 고객 돈만 떼고 서비스는 안 해준 셈.

## 핵심 코드

**`app/transfer/FeeService.java`** (최종 — `REQUIRED`)
```java
@RequiredArgsConstructor
@Service
public class FeeService {

  private final AccountRepository accountRepository;

  @Transactional
  public void chargeFee(Long fromId, Long feeAccountId, BigDecimal fee) {
    Account from = accountRepository.findById(fromId)
        .orElseThrow(() -> new IllegalArgumentException("계좌 없음: " + fromId));
    from.withdraw(fee);
    accountRepository.save(from);

    Account feeAccount = accountRepository.findById(feeAccountId)
        .orElseThrow(() -> new IllegalArgumentException("계좌 없음: " + feeAccountId));
    feeAccount.deposit(fee);
    accountRepository.save(feeAccount);
  }
}
```
(버그 버전엔 `@Transactional(propagation = Propagation.REQUIRES_NEW)`였음.)

**`app/transfer/FeeTransferService.java`**
```java
@RequiredArgsConstructor
@Service
public class FeeTransferService {

  public static final BigDecimal FEE = BigDecimal.valueOf(100);

  private final AccountRepository accountRepository;
  private final FeeService feeService;

  @Transactional
  public void transfer(Long fromId, Long toId, Long feeAccountId, BigDecimal amount) {
    feeService.chargeFee(fromId, feeAccountId, FEE);

    Account from = accountRepository.findById(fromId)
        .orElseThrow(() -> new IllegalArgumentException("계좌 없음: " + fromId));
    from.withdraw(amount);
    accountRepository.save(from);

    Account to = accountRepository.findById(toId)
        .orElseThrow(() -> new IllegalArgumentException("계좌 없음: " + toId));
    to.deposit(amount);
    accountRepository.save(to);
  }
}
```

**`test/app/transfer/FeeTransferServiceTest.java`**
```java
@Slf4j
@SpringBootTest
class FeeTransferServiceTest extends AbstractIntegrationTest {

  @Autowired
  private FeeTransferService feeTransferService;

  @Autowired
  private AccountRepository accountRepository;

  @Test
  @DisplayName("이체가 실패하면 수수료도 함께 롤백되어야 한다")
  void feeShouldRollBackWhenTransferFails() {
    Account from = accountRepository.save(new Account("alice", new BigDecimal("10000")));
    Account feeAccount = accountRepository.save(new Account("bank-fee", new BigDecimal("0")));
    Long invalidToId = 999_999L;

    assertThatThrownBy(() -> feeTransferService.transfer(from.getId(), invalidToId, feeAccount.getId(), new BigDecimal("3000")))
        .isInstanceOf(IllegalArgumentException.class);

    Account reloadedFrom = accountRepository.findById(from.getId()).orElseThrow();
    Account reloadedFeeAccount = accountRepository.findById(feeAccount.getId()).orElseThrow();

    log.info("이체 실패 후 from 잔액: {} (기대값: 10000)", reloadedFrom.getBalance());
    log.info("이체 실패 후 수수료 계좌 잔액: {} (기대값: 0)", reloadedFeeAccount.getBalance());

    assertThat(reloadedFrom.getBalance()).isEqualByComparingTo("10000");
    assertThat(reloadedFeeAccount.getBalance()).isEqualByComparingTo("0");
  }
}
```

## 재현 및 수정 — 3단계로 진행됨

**1단계**: `FeeTransferService.transfer()`에 실수로 `@Transactional`이 빠진 채로 첫 테스트 실행 → `from=6900`, 수수료 계좌 `100`. `10000 - 100(수수료) - 3000(이체액)`이 그대로 남아있었음 — `to` 조회 실패 전에 이미 두 번의 `save()`가 각각 독립 커밋된 것. **이건 챕터 1의 버그가 우연히 다시 섞여 들어간 것**(`transfer()` 자체에 트랜잭션이 없으면 `REQUIRED`로 합류할 대상이 없어 무의미해짐).

**2단계**: `transfer()`에 `@Transactional` 추가(챕터 1 버그 해결), `chargeFee()`는 아직 `REQUIRES_NEW`로 둔 채 재실행 → `from=9900`, 수수료 계좌 `100`. 이번엔 실제 이체(`amount` 출금)는 정확히 롤백됨(`6900`이 아니라 `9900`) — `transfer()` 자체는 이제 제대로 트랜잭션 경계를 가짐. **하지만 수수료(`100`)만 독립적으로 살아남음** — 이게 이번 챕터의 진짜 버그.

**3단계**: `chargeFee()`의 propagation을 `REQUIRES_NEW` → `REQUIRED`로 변경 → `from=10000`, 수수료 계좌 `0`. 완전히 롤백됨, 테스트 통과.

2단계에서 1단계의 "우연한" 버그를 먼저 걷어내고 나니, 이번 챕터가 보여주려던 버그(수수료만 새는 것)가 훨씬 깔끔하게 분리되어 보였음.

## 완료 체크리스트
- [x] `FeeService`(`REQUIRES_NEW`, 버그 버전) + `FeeTransferService` 작성
- [x] 테스트로 재현 (챕터 1 버그가 우연히 섞여 들어간 것 발견 및 정리)
- [x] `REQUIRES_NEW`가 진짜 원인임을 순수하게 재현 (`9900`/`100`)
- [x] `REQUIRED`로 수정, 전체 롤백 확인 (`10000`/`0`)
- [x] 이 로그 문서 작성

## ADR

**Decision**
- 수수료 징수(`FeeService.chargeFee`)는 이체의 일부로 취급 — `REQUIRED`(기본 전파)로 메인 트랜잭션에 합류시킨다.
- `REQUIRES_NEW`는 "메인 작업 실패와 무관하게 반드시 남아야 하는 것"(감사 로그 등)에만 쓴다는 챕터 3의 원칙을 재확인.

**Drivers**
- 수수료는 이체가 성공했을 때만 정당화되는 부수 효과라, 이체와 원자적으로 묶여야 함.
- `REQUIRES_NEW`를 "확실히 실행되게 하고 싶어서" 오남용하는 게 실무에서 흔한 실수라, 직접 겪어보는 게 중요.

**Alternatives considered**
- 수수료 징수 실패를 별도로 감지해서 보상 트랜잭션(수수료 환불)으로 처리 — 기각: SAGA 패턴(Phase 3)에서 다룰 주제이고, 지금은 단일 DB 트랜잭션 범위 안에서 원자성으로 해결 가능하므로 과함.

**Consequences**
- `FeeTransferService.transfer()`가 실패하면 수수료를 포함한 모든 변경이 항상 함께 롤백된다.
- 앞으로 "부가 작업"(수수료, 포인트 적립 등)을 추가할 때는 기본적으로 `REQUIRED`로 시작하고, `REQUIRES_NEW`는 명확한 이유(실패와 무관하게 반드시 남아야 함)가 있을 때만 예외적으로 쓴다.

**Follow-ups**
- 챕터 9(감사 로그)에서 `REQUIRES_NEW`가 실제로 정당한 케이스를 다룬다 — 이번 챕터(부당한 케이스)와 대비해서 "언제 쓰고 언제 쓰면 안 되는지" 판단 기준이 완성됨.
