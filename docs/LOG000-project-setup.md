# LOG000 — 프로젝트 초기 세팅

전체 진행도: [`README.md`](../README.md)

Phase 1 챕터 1 진입 전 인프라 세팅 기록. 세팅 과정에서 있었던 시행착오, Q&A, 최종 결정(ADR), 그리고 실제로 완성된 설정을 한 곳에 담는다. 서브디렉터리(`docs/adr/`, `docs/log/`, `docs/plans/`) 없이 `docs/` 바로 아래 `LOG###-부제.md` 형식으로 단계별 로그(+ADR 섹션)를 평평하게 쌓는다 — 계획서와 로그가 성격이 겹쳐 문서를 나눌 실익이 적다고 판단해 하나로 합침.

## 배경
Spring Initializr로 생성된 골격(`build.gradle`, `compose.yaml`, `application.yaml`, `io.hkarling.transaction` 패키지, `ApplicationTests`)에서 시작. 초기 상태 확인 결과 `compose.yaml`(랜덤 포트, DB=`mydatabase`)과 `application.yaml`(고정 포트 15432, DB=`postgres`)이 서로 안 맞았음 — `spring-boot-docker-compose`가 런타임에 datasource를 자동 덮어써서 지금까지 문제가 드러나지 않았던 것.

## 타임라인

### 1. DB/테스트 인프라 전략 결정
두 옵션을 저울질:
- A) 수동 `docker compose` (고정 포트) + Testcontainers
- B) Spring Boot Docker Compose 자동관리 유지

**락 경합/데드락/커넥션 풀 실험에서 `psql` 등으로 직접 DB에 붙어야 하고, 격리수준·동시성 테스트는 실제 PostgreSQL 동작이 필요하다**는 이유로 A 선택.

### 2. `build.gradle`에 Testcontainers 추가 — 첫 번째 빌드 실패
`spring-boot-docker-compose` 제거하고 `testImplementation 'org.testcontainers:junit-jupiter'`, `'org.testcontainers:postgresql'`을 버전 없이 추가(Spring Boot BOM이 버전을 관리해줄 거라 가정) → 빌드 실패:
```
Could not find org.testcontainers:junit-jupiter:.
Could not find org.testcontainers:postgresql:.
```

### 3. 잘못된 방향의 삽질 — Spring Boot 버전 문제로 오판
GitHub에서 "Spring Boot 4.1.0 Gradle 플러그인 + BOM 조합에서 testcontainers 관련 회귀가 있다"는 이슈(#50762)를 찾고, 이게 원인이라고 판단해 `4.1.0` → `4.0.7`로 다운그레이드. (플러스로 Spring Boot 3.x 대안도 논의했으나, **3.x는 2026-06-30부로 OSS 지원 완전 종료**라는 걸 확인하고 기각.)

4.0.7로 내려도 **똑같은 에러가 토씨 하나 안 틀리고 반복**됨. `platform('org.testcontainers:testcontainers-bom:2.0.5')` 명시적 추가, `ext['testcontainers.version'] = '2.0.5'` 프로퍼티 오버라이드도 시도했지만 전부 실패 — 매번 같은 에러.

### 4. 진짜 원인 발견
`--stacktrace`로 받은 전체 스택트레이스를 보니 `Required by: root project` 한 줄뿐, BOM 관련 언급이 아예 없었음. 이때 "혹시 내가 알려준 버전 번호(`2.0.5`) 자체가 틀린 게 아닐까"를 의심하고 **Maven Central Solr API를 직접 조회**(`search.maven.org/solrsearch/select?...&wt=json`, 요약이 아닌 원본 JSON):
- `org.testcontainers:postgresql` 최신 버전 = `1.21.3`
- `org.testcontainers:junit-jupiter` 최신 버전 = `1.21.3`
- `2.0.5`, "testcontainers 2.x 메이저 버전에서 모듈명이 바뀌었다"는 이야기 전부 **웹 검색 요약이 지어낸 잘못된 정보**였음.

`testImplementation 'org.testcontainers:junit-jupiter:1.21.3'` / `'org.testcontainers:postgresql:1.21.3'`로 버전을 직접 명시하니 바로 해결. Spring Boot 버전은 `4.1.0`으로 되돌림 — 애초에 버전 문제와 Boot 버전은 무관했음.

**교훈**: 라이브러리 버전은 검색 요약을 믿지 말고 Maven Central 같은 원본 레지스트리로 직접 확인한다. 이 원칙을 안 지켜서 사용자 시간을 여러 라운드 허비시켰음 — 재발 방지용으로 memory에 남김.

### 5. 아키텍처 재검토 — 풀 헥사고날 → 라이트 구조로 축소
패키지 스켈레톤(`common`/`domain`/`app`/`infra`, 포트 인터페이스 포함)을 만들고 나서 "이렇게 나누는 게 실습에 의미가 있나?"는 질문이 나옴. 논의 끝에:
- 포트/어댑터 추상화(도메인에 리포지토리 인터페이스, infra가 구현)는 **트랜잭션/동시성 학습 목표와 무관한 보일러플레이트**로 판단해 제거.
- `@Transactional` 경계를 어디 두는지가 챕터 3(전파 속성)의 핵심이므로, `domain`(JPA 엔티티) / `app`(트랜잭션 경계) / `infra`(Spring Data JPA 리포지토리) 구분 자체는 유지 — 오히려 전파속성 실험 무대로 도움이 됨.
- 4개 `package-info.java` 내용을 라이트 구조에 맞게 수정.

### 6. `ApplicationTests` Testcontainers 전환 + 격리 검증
`@Testcontainers` + `@Container` + `@ServiceConnection`으로 전환 (`org.testcontainers.containers.PostgreSQLContainer` — 재배치 이야기는 4번 항목에서 확인했듯 잘못된 정보였으므로 기존 1.x 패키지 경로 그대로 사용). 수동 `docker compose stop`으로 로컬 DB를 내린 상태에서도 `./gradlew test`가 통과하는 걸 확인해, Testcontainers가 실제로 별도 격리된 컨테이너를 쓰고 있음을 검증함.

### 7. 문서 구조 정리
계획서(`docs/plans/0001-...`)와 로그(`docs/log/0000-...`)를 서브디렉터리 없이 `docs/` 평평한 구조로 옮기고 `LOG###` 네이밍으로 통일. 이후 다시, 계획서와 로그의 내용이 실질적으로 중복된다고 판단해 **이 문서 하나로 합침** — 계획서(`0001-project-setup-plan.md`)는 삭제.

## Q&A

**Q. Maven Central이 아닌 다른 저장소를 써야 하나? Testcontainers가 보편적인 라이브러리인가?**
A. `central.sonatype.com`은 Maven Central 자체의 공식 조회 사이트라 별도 저장소 추가가 필요한 게 아니었음 (`repositories { mavenCentral() }` 그대로 유지). Testcontainers는 Spring Boot가 `spring-boot-testcontainers` 모듈로 공식 통합해줄 만큼 Java/Spring 진영 표준 도구 — H2 같은 인메모리 DB로는 재현 안 되는 실제 PostgreSQL 락/격리수준 동작을 테스트하기 위해 업계에서 널리 쓰임.

**Q. Spring Boot 3.x였으면 이런 버전 문제가 없었을까?**
A. 있었을 가능성은 낮음 — 3.x의 testcontainers BOM 관리는 오랫동안 안정적이었음. 하지만 3.x는 2026-06-30부로 오픈소스 지원이 완전히 끊겨서(최종 릴리즈 3.5.16), 새 학습 프로젝트를 EOL 버전으로 시작하는 게 더 큰 트레이드오프라 4.1.0 유지로 최종 결정.

**Q. Spring Boot BOM에서 이 문제가 정식으로 언제 고쳐질까?**
A. 확인한 관련 이슈(#50762)는 open 상태, 마일스톤/담당자 없음 — 고정된 수정 시점을 알 수 없음. (참고로 이 이슈는 우리가 겪은 증상과는 다른 문제 — `bootJar`에 테스트 의존성이 새는 이슈였고, 우리 문제의 진짜 원인은 위 4번 항목의 잘못된 버전 번호였음.) 결국 공식 수정을 기다리지 않고 버전을 직접 명시하는 것으로 해결.

**Q. 헥사고날 4계층으로 나누는 게 실습 전반에 의미가 있나?**
A. 포트/어댑터까지 포함한 풀 헥사고날은 트랜잭션/동시성이라는 학습 목표에 비해 과함 — 격리수준·락 같은 버그는 JPA/DB 엔진 레벨 현상이라 계층을 몇 겹 두르든 동작 자체는 안 바뀌고, 솔로 학습 프로젝트라 계층 분리로 얻는 결합도 이점도 적음. 다만 `@Transactional` 경계를 어디 두는지는 학습 핵심이라, 포트 추상화는 빼고 `domain`/`app`/`infra`/`common` 라이트 구조는 유지하기로 함.

## 최종 구성

**`compose.yaml`**
```yaml
services:
  postgres:
    image: 'postgres:16'
    container_name: practice-transaction
    environment:
      - 'POSTGRES_DB=postgres'
      - 'POSTGRES_USER=postgres'
      - 'POSTGRES_PASSWORD=postgres'
    ports:
      - '15432:5432'
    volumes:
      - bank-transfer-pgdata:/var/lib/postgresql/data
volumes:
  bank-transfer-pgdata:
```

**`build.gradle` 핵심 변경**: `spring-boot-docker-compose` 제거, 아래 3줄 추가
```groovy
testImplementation 'org.springframework.boot:spring-boot-testcontainers'
testImplementation 'org.testcontainers:junit-jupiter:1.21.3'
testImplementation 'org.testcontainers:postgresql:1.21.3'
```

**`application.yaml`**
```yaml
spring:
  application:
    name: practice-transaction
  datasource:
    url: jdbc:postgresql://localhost:15432/postgres
    username: postgres
    password: postgres
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: true
    properties:
      hibernate:
        format_sql: true

logging:
  level:
    org.hibernate.SQL: debug
    org.hibernate.orm.jdbc.bind: trace
```

**패키지 구조** (`io.hkarling.transaction` 하위, 라이트 3+1계층, 포트 추상화 없음)
- `common/` — 계층 간 공유 유틸리티, 공통 예외 타입
- `domain/` — JPA 엔티티(도메인 규칙 포함), 도메인 서비스
- `app/` — 유스케이스/애플리케이션 서비스, 트랜잭션 경계(`@Transactional`)
- `infra/` — Spring Data JPA 리포지토리, 설정 클래스, 외부 연동

**`ApplicationTests.java`**
```java
package io.hkarling.transaction;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
class ApplicationTests {

  @Container
  @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  @Test
  void contextLoads() {
  }

}
```

## 완료 체크리스트
- [x] `docker compose up -d` 기동, 포트 매핑(`0.0.0.0:15432->5432/tcp`) 확인
- [x] `./gradlew bootRun` 정상 기동 (compose 떠 있는 상태)
- [x] `./gradlew test` — compose 내린 상태에서도 Testcontainers만으로 통과 (격리 검증 완료)
- [x] `./gradlew build` 성공
- [x] `common`/`domain`/`app`/`infra` 패키지 + `package-info.java` 존재
- [x] 이 로그 문서 작성

## ADR

**Decision**
- DB/테스트 인프라: 수동 `docker compose`(고정 포트 15432) + Testcontainers 이원화. `spring-boot-docker-compose` 자동관리는 제거.
- Spring Boot 버전: `4.1.0` 유지 (Hibernate는 Boot BOM이 관리하는 버전 그대로 사용).
- Testcontainers 버전: `1.21.3`로 명시적 고정 (BOM 자동관리에 의존하지 않음).
- 패키지 구조: `common`/`domain`/`app`/`infra` 라이트 3+1계층, 포트/어댑터 추상화 없음.
- 문서화: 계획서와 로그/ADR을 분리하지 않고 이 문서 하나로 통합, `docs/` 평평한 구조에 `LOG###` 네이밍.

**Drivers**
- 락 경합·데드락·커넥션 풀 실험에서 `psql` 등으로 실행 중인 DB에 직접 접속해야 함.
- 격리수준·동시성 재현 테스트는 매번 깨끗한 상태의 실제 PostgreSQL이 필요함.
- 실습용 솔로 프로젝트라 아키텍처 보일러플레이트보다 학습 속도·간결함을 우선.
- 라이브러리 버전은 검증 없이 제안하면 안 된다는 걸 이번에 직접 겪음.

**Alternatives considered**
- Spring Boot Docker Compose 자동관리 유지 — 기각: 랜덤 포트라 외부 툴로 직접 접속하기 번거로움.
- Spring Boot `3.x`(최신 3.5.16)로 다운그레이드 — 기각: 2026-06-30부로 OSS 지원 완전 종료, 새 학습 프로젝트를 EOL 버전으로 시작하는 트레이드오프가 더 큼.
- Spring Boot `4.0.7`로 다운그레이드 — 처음엔 이걸로 갔으나, 실제 원인이 Boot 버전과 무관한 잘못된 testcontainers 버전 번호였음이 밝혀져 `4.1.0`으로 되돌림.
- 풀 헥사고날(도메인에 리포지토리 포트 인터페이스, infra가 구현) — 기각: 트랜잭션/동시성이라는 학습 목표와 무관한 오버헤드. 다만 `@Transactional` 경계 구분(챕터 3 전파속성 실험에 필요)은 유지할 가치가 있어 domain/app/infra 구분 자체는 남김.
- 계획서와 로그를 별도 문서로 유지 — 기각: 내용이 실질적으로 중복되어 문서를 나눌 실익이 적음.

**Consequences**
- 챕터 진행 전 항상 `docker compose up -d`를 수동으로 띄워야 함 (자동 기동 없음).
- 라이브러리 버전을 바꿀 때마다 Maven Central 원본 API로 재확인하는 습관이 필요함 (memory에 기록).
- 도메인 모델과 JPA 엔티티가 분리되어 있지 않으므로, 나중에 정말 순수 도메인 로직만 테스트하고 싶어지면 재구조화가 필요할 수 있음 — 지금은 트레이드오프로 감수.

**Follow-ups**
- Phase 2 이후 스키마가 안정되면 `ddl-auto: update` 대신 마이그레이션 도구(Flyway 등) 도입 여부 재검토.
- Spring Boot `bootJar`에 테스트 의존성이 새는 이슈(#50762)는 실제 배포 패키징 챕터에서 재확인.
