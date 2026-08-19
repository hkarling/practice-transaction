# LOG001 — 챕터 1: `@Transactional` 없는 기본 이체 구현

전체 진행도: [`README.md`](../README.md)

## 배경 & 목표
Phase 1 챕터 1. 계좌 간 이체(출금 + 입금)를 `@Transactional` 없이 구현해 뭐가 터지는지 직접 확인하고, `@Transactional`로 고친다.

## 예상 문제점 (시작 전 짚었던 것)
Spring Data JPA의 `SimpleJpaRepository`는 `save()`, `findById()` 같은 메서드 하나하나에 자체적으로 `@Transactional`이 걸려 있다. 서비스 메서드에 `@Transactional`이 없으면 `save()` 호출마다 독립적으로 커밋된다 — 즉 이체 중간에 실패하면 출금은 이미 반영된 채로 입금만 안 일어날 수 있다 (돈 증발).

## 구현

**`domain.Account`** — 잔액(`BigDecimal`)을 가진 JPA 엔티티. `withdraw`/`deposit` 도메인 규칙을 엔티티 안에 둠 (잔액 부족 시 `IllegalStateException`).

**`infra.AccountRepository`** — `JpaRepository<Account, Long>` 상속, 별도 구현 없음.

**`app.TransferService`** (버그 있는 첫 버전) — `@Transactional` 없이 `fromAccount` 조회→출금→저장, 그다음 `toAccount` 조회→입금→저장. `toId`가 존재하지 않으면 두 번째 조회에서 예외가 나는데, 이미 `fromAccount`의 출금은 저장된 뒤라 롤백되지 않음.

**`TransferServiceTest`** — 존재하지 않는 `toId`로 이체를 시도하고, 예외 발생 후 `fromAccount`의 잔액이 원래대로(`10000`)인지 검증. `Account.balance`가 `BigDecimal`이라 `assertEquals` 대신 `compareTo()`로 비교 (scale 차이로 `.equals()`가 틀리게 나올 수 있음).

## 핵심 코드 (챕터 1 완료 시점)

**`domain/Account.java`**
```java
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
public class Account {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String ownerName;

  private BigDecimal balance;

  public Account(String ownerName, BigDecimal balance) {
    this.ownerName = ownerName;
    this.balance = balance;
  }

  public void withdraw(BigDecimal amount) {
    if (balance.compareTo(amount) < 0) {
      throw new IllegalStateException("잔액 부족: " + ownerName);
    }
    balance = balance.subtract(amount);
  }

  public void deposit(BigDecimal amount) {
    balance = balance.add(amount);
  }
}
```

**`infra/AccountRepository.java`**
```java
public interface AccountRepository extends JpaRepository<Account, Long> {
}
```

**`app/TransferService.java`**
```java
@RequiredArgsConstructor
@Service
public class TransferService {

  private final AccountRepository accountRepository;

  @Transactional
  public void transfer(Long fromId, Long toId, BigDecimal amount) {
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

**`test/app/TransferServiceTest.java`**
```java
@Testcontainers
@SpringBootTest
class TransferServiceTest {

  @Container
  @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  @Autowired
  private TransferService transferService;

  @Autowired
  private AccountRepository accountRepository;

  @Test
  @DisplayName("이체 중간에 실패하면 출금액도 롤백되어야 한다")
  void withdrawalShouldRollbackWhenTransferFailsMidway() {
    Account from = accountRepository.save(new Account("alice", new BigDecimal("10000")));
    Long invalidToId = 999_999L;

    assertThrows(IllegalArgumentException.class,
        () -> transferService.transfer(from.getId(), invalidToId, new BigDecimal("3000")));

    Account reloaded = accountRepository.findById(from.getId()).orElseThrow();
    assertEquals(0, reloaded.getBalance().compareTo(new BigDecimal("10000")));
  }
}
```

## 재현 결과
버그 있는 버전으로 테스트를 돌리자 실패:
```
Expected :0
Actual   : -1
```
`compareTo` 리턴값 `-1`은 `reloaded.getBalance() < 10000`이라는 뜻 — 재조회한 잔액이 이미 줄어든 채(`7000`)로 DB에 남아있었다는 것. 예상한 버그가 그대로 재현됨.

## 수정
`TransferService.transfer()`에 `@Transactional` 한 줄 추가. 메서드 진입 시점에 트랜잭션이 시작되고, 그 안에서 호출하는 `accountRepository.save()`들은 (기본 전파 속성 `REQUIRED`) 새 트랜잭션을 만들지 않고 이 트랜잭션에 합류한다. 그래서 `to` 조회 실패로 런타임 예외가 메서드 밖으로 전파되면, Spring이 트랜잭션 전체를 롤백 — 출금도 없었던 일이 된다. ("왜 REQUIRED로 합류하는가"의 전파속성 디테일은 챕터 3에서 더 다룬다.)

수정 후 테스트 통과 확인.

## 코드 주석 관련 결정
커밋 전, 사용자가 `@Transactional` 위에 위 "수정" 내용을 그대로 옮긴 5줄짜리 블록 주석을 달았다가 "이렇게 남겨도 되냐"고 질문. **주석을 지우고 이 LOG 문서로 옮기기로 결정** — 이유: 그 주석은 이 코드만의 이유가 아니라 Spring `@Transactional`의 일반 동작 원리 설명이라, 코드/채팅/LOG 세 군데에 같은 내용이 중복되고 나중에 코드가 바뀌면 stale해질 위험이 있음. "왜"는 LOG 문서에 모아두고 코드는 가볍게 유지하기로 함.

## 완료 체크리스트
- [x] `Account` 엔티티, `AccountRepository`, `TransferService`(버그 버전) 작성
- [x] `TransferServiceTest`로 버그 재현 (테스트 실패 확인)
- [x] `@Transactional` 추가로 수정, 테스트 통과 확인
- [x] 이 로그 문서 작성

## ADR

**Decision**
- `TransferService.transfer()`에 메서드 레벨 `@Transactional` 적용 (클래스 레벨 아님 — 나중에 트랜잭션이 필요 없는 조회 메서드가 추가될 걸 고려).
- 도메인 규칙(출금 시 잔액 검증)은 `Account` 엔티티 안에 캡슐화 (서비스가 아니라 엔티티가 스스로 지킴).
- 코드에는 "왜 이렇게 동작하는가" 같은 장문 설명 주석을 남기지 않고, 챕터별 `docs/LOG###`에 모은다.

**Drivers**
- Spring Data JPA `SimpleJpaRepository`의 메서드별 개별 트랜잭션 동작을 직접 겪어보는 게 이번 챕터의 핵심 학습 목표.
- 코드 주석과 문서 사이 중복/stale 위험을 피하고 싶음.

**Alternatives considered**
- 클래스 레벨 `@Transactional` — 기각(당장은 아니지만, 조회 전용 메서드가 추가되면 불필요하게 트랜잭션이 걸릴 수 있어 메서드 레벨을 기본으로 함).
- 코드에 상세 동작 주석 유지 — 기각: LOG 문서와 내용이 중복되고 유지보수 부담.

**Consequences**
- 이후 챕터에서 조회 전용 메서드가 생기면 메서드 레벨 `@Transactional` 패턴을 그대로 따라간다.
- "왜 이렇게 동작하는가"를 다시 찾아볼 땐 코드가 아니라 `docs/LOG###`를 봐야 함.

**Follow-ups**
- 챕터 3(전파 속성)에서 `REQUIRED`가 왜 기존 트랜잭션에 합류하는지 더 깊게 다룰 예정 — 이번 챕터의 "미리 맛보기" 설명과 연결.
