# LOG003 — 챕터 3: 전파 속성 실험 (REQUIRED / REQUIRES_NEW / NESTED)

전체 진행도: [`README.md`](../README.md)

## 목표
바깥 트랜잭션이 실패했을 때, 전파 속성에 따라 안쪽에서 한 작업이 살아남는지 확인한다.

## 시작 전 짚었던 것
1. **Self-invocation 문제**: 같은 클래스 안에서 `this.innerMethod()`를 호출하면 Spring AOP 프록시를 안 거쳐 `@Transactional`이 무시된다. 그래서 안쪽(`PropagationDemoService`)과 바깥쪽(`PropagationOuterService`)을 서로 다른 빈으로 나눠서 실험.
2. **`NESTED`는 기본 설정으로 안 될 가능성이 높다**는 걸 미리 예고 — `JpaTransactionManager`의 `nestedTransactionAllowed` 기본값이 `false`.

## 구현
- `PropagationDemoService`(안쪽) — `REQUIRED`/`REQUIRES_NEW`/`NESTED` 각각으로 `JdbcTemplate`을 통해 `propagation_log` 테이블에 로그 한 줄 삽입.
- `PropagationOuterService`(바깥) — 안쪽 메서드를 호출한 뒤 의도적으로 `RuntimeException`을 던짐.
- `PropagationTest` — `@BeforeEach`에서 `CREATE TABLE IF NOT EXISTS`로 테이블 준비(JPA 엔티티가 아니라 ddl-auto가 안 만들어줌), 각 전파 속성별로 바깥 실패 후 로그가 몇 개 남았는지 확인. `@Slf4j`로 삽입 시점과 최종 개수를 로그로 관찰 (테스트 파일에 `showStandardStreams` 설정은 챕터 2에서 이미 적용됨).

## 핵심 코드 (챕터 3 완료 시점)

**`app/propagation/PropagationDemoService.java`** (챕터 7 이후 `app.propagation` 하위로 이동 — 자세한 경위는 `LOG007` 참고)
```java
@Slf4j
@RequiredArgsConstructor
@Service
public class PropagationDemoService {

  private final JdbcTemplate jdbcTemplate;

  @Transactional(propagation = Propagation.REQUIRED)
  public void logRequired(String message) {
    jdbcTemplate.update("INSERT INTO propagation_log(message) VALUES (?)", "REQUIRED: " + message);
    log.info("[REQUIRED] 삽입 완료: {}", message);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void logRequiresNew(String message) {
    jdbcTemplate.update("INSERT INTO propagation_log(message) VALUES (?)", "REQUIRED_NEW: " + message);
    log.info("[REQUIRES_NEW] 삽입 완료: {}", message);
  }

  @Transactional(propagation = Propagation.NESTED)
  public void logNested(String message) {
    jdbcTemplate.update("INSERT INTO propagation_log(message) VALUES (?)", "NESTED: " + message);
    log.info("[NESTED] 삽입 완료: {}", message);
  }
}
```
(참고: `logRequiresNew`의 INSERT 문자열이 `"REQUIRED_NEW: "`로 오타 — 로그 저장용 문자열이라 테스트의 개수 검증에는 영향 없어 그대로 남아있음.)

**`app/propagation/PropagationOuterService.java`**
```java
@RequiredArgsConstructor
@Service
public class PropagationOuterService {

  private final PropagationDemoService propagationDemoService;

  @Transactional
  public void requiredThenFail(String message) {
    propagationDemoService.logRequired(message);
    throw new RuntimeException("의도적 실패");
  }

  @Transactional
  public void requiresNewThenFail(String message) {
    propagationDemoService.logRequiresNew(message);
    throw new RuntimeException("의도적 실패");
  }

  @Transactional
  public void nestedThenFail(String message) {
    propagationDemoService.logNested(message);
    throw new RuntimeException("의도적 실패");
  }
}
```

**`test/app/propagation/PropagationTest.java`**
```java
@Slf4j
@Testcontainers
@SpringBootTest
class PropagationTest {

  @Container
  @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer("postgres:16");

  @Autowired
  private PropagationOuterService outerService;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void setUp() {
    jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS propagation_log (id BIGSERIAL PRIMARY KEY, message VARCHAR(255))");
    jdbcTemplate.update("DELETE FROM propagation_log");
  }

  @Test
  @DisplayName("REQUIRED는 바깥 트랜잭션에 합류해서, 바깥이 롤백되면 같이 롤백된다")
  void requiredJoinsOuterTransactionAndRollsBackTogether() {
    assertThatThrownBy(() -> outerService.requiredThenFail("A"))
        .isInstanceOf(RuntimeException.class);

    Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM propagation_log", Integer.class);
    log.info("[REQUIRED] 바깥 롤백 후 남은 로그 수: {}", count);
    assertThat(count).isZero();
  }

  @Test
  @DisplayName("REQUIRES_NEW는 독립된 트랜잭션이라 바깥이 롤백돼도 살아남는다")
  void requiresNewSurvivesOuterRollback() {
    assertThatThrownBy(() -> outerService.requiresNewThenFail("B"))
        .isInstanceOf(RuntimeException.class);

    Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM propagation_log", Integer.class);
    log.info("[REQUIRES_NEW] 바깥 롤백 후 남은 로그 수: {}", count);
    assertThat(count).isEqualTo(1);
  }

  @Test
  @DisplayName("JPA 환경에서는 NESTED가 근본적으로 지원되지 않는다")
  void nestedIsNotSupportedInJpaEnvironment() {
    assertThatThrownBy(() -> outerService.nestedThenFail("C"))
        .isInstanceOf(NestedTransactionNotSupportedException.class);
  }

}
```
(`TransactionManagerConfig.java`는 3차 시도 때 만들었다가 효과가 없어 삭제됐으므로 최종 코드에는 없다.)

## 결과 — REQUIRED / REQUIRES_NEW
```
[REQUIRED]      삽입 완료 → 바깥 롤백 후 남은 로그 수: 0
[REQUIRES_NEW]  삽입 완료 → 바깥 롤백 후 남은 로그 수: 1
```
`REQUIRED`는 바깥 트랜잭션에 합류해서 같이 롤백됨. `REQUIRES_NEW`는 바깥을 잠깐 멈추고 독립된 트랜잭션으로 즉시 커밋하기 때문에 바깥이 실패해도 살아남음 — "삽입 완료" 로그(SQL 실행 시점)와 "최종 개수"(진짜 커밋 여부)가 다를 수 있다는 걸 직접 확인.

## 결과 — NESTED: 근본적 한계 발견
1차 시도: `NestedTransactionNotSupportedException` — 메시지가 `nestedTransactionAllowed` 프로퍼티를 켜라고 안내.

2차 시도: `TransactionManagerConfig`를 만들어 `JpaTransactionManager.setNestedTransactionAllowed(true)` 적용 → 예외 타입은 그대로, 메시지만 "JpaDialect does not support savepoints"로 바뀜.

3차 시도: `setJpaDialect(new HibernateJpaDialect())` 추가 → **동일한 에러, 전혀 안 바뀜.**

여기서 추측을 멈추고 Spring 공식 문서를 조사:
- `HibernateJpaDialect.getJdbcConnection()`은 **설계상 항상 `null`을 반환** — savepoint 매니저를 절대 못 얻으므로 `setJpaDialect`를 아무리 붙여도 소용없었던 이유가 이거였음.
- Spring 공식 Javadoc(`JpaTransactionManager`): *"JPA itself does not support nested transactions, so do not expect JPA access code to semantically participate in a nested transaction."*
- 결론: **JPA/Hibernate 기반(`JpaTransactionManager`) 프로젝트에서 `NESTED`는 설정으로 고칠 수 있는 문제가 아니라 근본적으로 지원되지 않는다.** `NESTED`는 원래 JDBC 세이브포인트(`DataSourceTransactionManager`)를 위해 설계된 기능.

**최종 결정**: `TransactionManagerConfig`(효과 없었음) 삭제, `nestedThenFailBehavior` 테스트를 "이 예외가 나는 게 정상"이라고 명시적으로 기대하는 특성 테스트(characterization test)로 고정:
```java
@Test
@DisplayName("JPA 환경에서는 NESTED가 근본적으로 지원되지 않는다")
void nestedIsNotSupportedInJpaEnvironment() {
    assertThatThrownBy(() -> outerService.nestedThenFail("C"))
            .isInstanceOf(NestedTransactionNotSupportedException.class);
}
```

## 시행착오
`nestedTransactionAllowed` → `setJpaDialect` 두 번의 설정 시도가 모두 실패한 뒤, 세 번째 추측을 던지는 대신 Spring 공식 Javadoc/이슈를 검색해서 근본 원인을 확인함. "설정으로 고칠 수 있는 버그"가 아니라 "애초에 지원 안 되는 기능"이라는 걸 확인하고 나서야 왜 계속 같은 에러가 났는지 납득됨.

## 완료 체크리스트
- [x] `PropagationDemoService`/`PropagationOuterService` 작성 (서로 다른 빈으로 self-invocation 문제 회피)
- [x] `REQUIRED` — 바깥 롤백 시 같이 롤백됨을 확인
- [x] `REQUIRES_NEW` — 바깥 롤백에도 살아남음을 확인
- [x] `NESTED` — JPA 환경에서 근본적으로 지원 안 됨을 확인, 이를 명시하는 테스트로 고정
- [x] 이 로그 문서 작성

## ADR

**Decision**
- `REQUIRED`/`REQUIRES_NEW`는 예상대로 동작 확인, 별도 조치 없음.
- `NESTED`는 이 프로젝트(JPA/Hibernate 기반)에서 사용하지 않는다 — 근본적으로 지원되지 않으므로 시도 자체를 하지 않고, 대신 그 사실을 테스트로 문서화해둔다.
- `TransactionManagerConfig`(수동 `PlatformTransactionManager` 빈)는 만들었다가 효과가 없어 제거 — Boot의 자동 설정된 트랜잭션 매니저를 그대로 사용.

**Drivers**
- Spring 공식 문서가 JPA 환경에서 `NESTED`가 의미상 지원되지 않는다고 명시.
- `HibernateJpaDialect.getJdbcConnection()`이 항상 `null`을 반환해 세이브포인트 매니저를 구조적으로 얻을 수 없음.

**Alternatives considered**
- JDBC 세이브포인트 지원을 위해 `DataSourceTransactionManager`로 전환하거나 별도 트랜잭션 매니저를 이원화 — 기각: 이 프로젝트는 JPA 중심이고, `NESTED`만을 위해 트랜잭션 매니저 체계를 이원화하는 건 학습 목표 대비 과도한 복잡도.
- 계속 다른 설정을 추측해서 시도 — 기각: 두 번의 실패한 추측 후 공식 문서로 근본 원인을 먼저 확인하는 게 맞다고 판단 (`docs/LOG000`에서 이미 겪은 교훈 재적용).

**Consequences**
- 이후 챕터에서 "실패해도 로그는 남겨야 한다"(챕터 9) 같은 요구사항엔 `NESTED`가 아니라 `REQUIRES_NEW`를 쓴다.
- `NESTED`가 필요한 시나리오(부분 롤백)가 생기면, JPA가 아니라 순수 JDBC 트랜잭션 관리로 그 부분만 분리해야 한다는 걸 기억해야 함.

**Follow-ups**
- 챕터 9(감사 로그, REQUIRES_NEW 실전)에서 이번 챕터의 `REQUIRES_NEW` 확인 결과를 실제 비즈니스 로직에 적용.
