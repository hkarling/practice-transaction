# LOG007 — 챕터 7: 이체 한도 / 일별 한도 동시성 제어

전체 진행도: [`README.md`](../README.md)

## 목표
1회 이체 한도는 단순 비교라 동시성 문제가 없지만, **일별 누적 한도**는 "오늘 이미 쓴 금액"이라는 공유 카운터를 여러 요청이 동시에 읽고 갱신하게 되어 챕터 4~6의 잔액 문제와 구조적으로 같은 버그를 만든다. 배운 락 기법(챕터 5/6)을 새로운 문제에 적용해본다.

## 시작 전 짚었던 것
- "오늘 쓴 금액"을 관리할 새 엔티티(`AccountDailyUsage`)가 필요.
- 락 없이 구현하면 두 요청이 동시에 "오늘 쓴 금액"을 읽고 둘 다 "한도 안 넘었다"고 판단할 수 있음 — 잔액 Lost Update와 같은 패턴.
- 테스트 단순화를 위해 "오늘 날짜의 사용량 레코드"는 setup에서 미리 만들어둠 (최초 INSERT 경쟁은 범위 밖).

## 핵심 코드

**`domain/AccountDailyUsage.java`**
```java
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccountDailyUsage {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private Long accountId;

  private LocalDate usageDate;

  private BigDecimal usedAmount;

  public AccountDailyUsage(Long accountId, LocalDate usageDate) {
    this.accountId = accountId;
    this.usageDate = usageDate;
    this.usedAmount = BigDecimal.ZERO;
  }

  public void addUsage(BigDecimal amount, BigDecimal dailyLimit) {
    BigDecimal newTotal = usedAmount.add(amount);
    if (newTotal.compareTo(dailyLimit) > 0) {
      throw new IllegalStateException("일일 이체 한도 초과");
    }
    usedAmount = newTotal;
  }
}
```

**`infra/AccountDailyUsageRepository.java`** (최종 — 비관적 락 메서드 포함)
```java
public interface AccountDailyUsageRepository extends JpaRepository<AccountDailyUsage, Long> {

  Optional<AccountDailyUsage> findByAccountIdAndUsageDate(Long accountId, LocalDate usageDate);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select u from AccountDailyUsage u where u.accountId = :accountId and u.usageDate = :usageDate")
  Optional<AccountDailyUsage> findByAccountIdAndUsageDateForUpdate(@Param("accountId") Long accountId, @Param("usageDate") LocalDate usageDate);

}
```

**`app/LimitedTransferService.java`** (최종 — 사용량/계좌 둘 다 비관적 락)
```java
@RequiredArgsConstructor
@Service
public class LimitedTransferService {

  public static final BigDecimal DAILY_LIMIT = new BigDecimal("5000");

  private final AccountRepository accountRepository;
  private final AccountDailyUsageRepository dailyUsageRepository;

  @Transactional
  public void transfer(Long fromId, Long toId, BigDecimal amount) {
    AccountDailyUsage usage = dailyUsageRepository.findByAccountIdAndUsageDateForUpdate(fromId, LocalDate.now())
        .orElseThrow(() -> new IllegalStateException("오늘 사용량 레코드 없음: " + fromId));
    usage.addUsage(amount, DAILY_LIMIT);

    Account from = accountRepository.findByIdForUpdate(fromId)
        .orElseThrow(() -> new IllegalArgumentException("계좌 없음: " + fromId));
    from.withdraw(amount);
    accountRepository.save(from);

    Account to = accountRepository.findByIdForUpdate(toId)
        .orElseThrow(() -> new IllegalArgumentException("계좌 없음: " + toId));
    to.deposit(amount);
    accountRepository.save(to);
  }

}
```

## 시행착오 — 버그가 다른 버그에 가려짐
1차 시도: `Account`는 `findById()`(락 없음)로 그대로 두고 `AccountDailyUsage`만 락 없이 구현 → 테스트 결과 **성공 1건, 실패 9건**. 그런데 실패 원인을 로그로 찍어보니 전부 `IllegalStateException`(한도 초과)이 아니라 **`ObjectOptimisticLockingFailureException`** — `Account`에 챕터 5에서 이미 붙여둔 `@Version` 충돌이었음. 재현하려던 "일일 한도" 버그는 시도조차 못 해보고, 계좌 자체의 낙관적 락 충돌에 먼저 가려진 것.

2차 시도: `Account` 조회를 `findByIdForUpdate()`(비관적 락, 챕터 6)로 바꿔서 계좌 쪽 노이즈 제거. 그런데 이때 원래 assertion(`usedAmount <= DAILY_LIMIT`)을 그대로 뒀더니, 여전히 통과해버림 — 사용자가 "비관적 락이면 결국 성공할 텐데?"라고 정확히 짚음. 재추론:
- 사용량 체크(`usage.addUsage()`)는 계좌 락보다 **먼저** 일어나고 락이 없어서, 10개 스레드가 계좌 락을 잡기도 전에 **전부 독립적으로 `usedAmount=0`을 보고 통과 판정**을 받아버림.
- `AccountDailyUsage`엔 `@Version`이 없어 커밋 시 각자 계산한 값(전부 `1000`)으로 덮어씀 — 진짜 문제는 기록값이 아니라 **"몇 건이 실제로 성공했는가"**.

assertion을 `successCount <= 5`로 바꾸자 정확히 재현: **성공 10건, 실패 0건, 그런데 사용량 기록은 겨우 `1000`.**

## 재현 결과
```
성공한 이체 수: 10, 실패한 이체 수: 0
일일 한도: 5000, 실제 사용량: 1000.00
```
- 계좌 쪽은 비관적 락 덕분에 정확히 처리됨 — 즉 10건 전부 **진짜로 출금됨** (한도 5000의 두 배인 10000이 실제로 빠져나감).
- 그런데 추적 기록(`usedAmount`)은 `1000`(Lost Update로 1건분만 남음) — 시스템 스스로도 한도가 뚫린 걸 모르는 상태. **이중으로 심각한 버그**: 한도 우회 + 감사 기록 손실이 동시에 일어남.

## 수정 결과
`AccountDailyUsage` 조회에도 비관적 락 적용 후 결정론적으로 통과:
```java
assertThat(successCount.get()).isEqualTo(5);
assertThat(failures).hasSize(5);
assertThat(reloadedUsage.getUsedAmount()).isEqualByComparingTo(LimitedTransferService.DAILY_LIMIT);
```
정확히 5건 성공, 5건은 `IllegalStateException`("일일 이체 한도 초과")으로 실패, 최종 사용량 정확히 `5000`.

## 완료 체크리스트
- [x] `AccountDailyUsage` 엔티티 + 리포지토리 작성
- [x] `LimitedTransferService`(버그 버전) 작성
- [x] 테스트로 재현 — Account 낙관적 락에 버그가 가려지는 문제 발견 및 우회
- [x] 재현 성공 확인 (10건 전부 성공, 한도 2배 우회)
- [x] `AccountDailyUsage`에도 비관적 락 적용, 결정론적 결과 확인
- [x] 이 로그 문서 작성

## ADR

**Decision**
- 일일 한도 추적은 별도 엔티티(`AccountDailyUsage`, 계좌ID+날짜 기준)로 관리.
- 한도 체크·갱신과 계좌 잔액 변경 둘 다 비관적 락(`PESSIMISTIC_WRITE`)으로 통일 — 챕터 6에서 만든 `Account.findByIdForUpdate` 패턴을 그대로 재사용.

**Drivers**
- 이미 챕터 5에서 `Account`에 `@Version`을 붙여뒀기 때문에, 이번 챕터 버그를 낙관적 락과 섞어 재현하려 하면 계좌 자체의 재시도 없는 낙관적 락 충돌이 먼저 나서 원하는 버그를 가려버림 — 두 메커니즘(낙관적/비관적)을 한 트랜잭션에서 섞어 쓸 때의 함정을 직접 겪음.
- "기록값이 맞는가"가 아니라 "실제로 몇 건이 처리됐는가"가 진짜 불변조건이라는 걸 재확인.

**Alternatives considered**
- `Account`도 낙관적 락 + 재시도(`TransferRetryService` 패턴)로 통일 — 기각(이번엔): 비관적 락을 이미 배운 김에 같은 트랜잭션 안에서 일관되게 쓰는 게 더 단순함. 낙관적+비관적 혼합은 별도로 다뤄볼 가치 있는 주제로 남김.
- `usedAmount <= DAILY_LIMIT` assertion 유지 — 기각: `AccountDailyUsage` 자체의 Lost Update 때문에 기록값만으로는 진짜 문제(실제 출금 건수 초과)를 못 잡아냄.

**Consequences**
- `LimitedTransferService.transfer()`는 이제 사용량 락 → 계좌 락(A) → 계좌 락(B) 순으로 총 3개의 비관적 락을 잡는다 — 락 경합/데드락 가능성이 늘어난 구조. (계좌 락 순서는 챕터 6에서 이미 고정했지만, 사용량 락까지 포함한 전체 순서의 데드락 안전성은 별도로 검토가 필요할 수 있음.)
- 한 트랜잭션 안에서 낙관적 락과 비관적 락을 섞어 쓸 때, 낙관적 락 쪽이 재시도 없이 먼저 실패하면 다른 버그를 가릴 수 있다는 걸 기억해야 함.

**Follow-ups**
- `LimitedTransferService`의 3중 락 조합에 대한 데드락 가능성 검토 (챕터 6 기법 재적용 여부).
- `AccountDailyUsage` 최초 레코드 생성(그날 첫 이체) 시의 INSERT 경쟁은 아직 안 다룸 — 필요시 후속 챕터에서.
- `app` 패키지가 커져서 하위 패키지 분리 예정(챕터 7 마무리 후, 다음 세션에서 진행) — 문서(LOG001~007)의 코드 스니펫 경로도 함께 갱신 필요.
