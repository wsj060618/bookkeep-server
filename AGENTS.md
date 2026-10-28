# AGENTS.md

Multi-module Spring Boot 3.3.5 / JDK 17 / Maven backend for a bookkeeping web app. `PLAN.md` (in Chinese) is the authoritative design doc — read it before writing code. This file only captures what is not obvious from the repo.

## Current state (important)

- P1 in progress (days 0–3 done, day 4 = register API). `bookkeep-common` has `Result`/`ResultCode`/`PageResult`/`BusinessException`/`JwtUtil`; `bookkeep-service` has `entity/{User,Book,Category}`, `mapper/{User,Book,Category}Mapper`, `constant/CategoryTemplate`; `bookkeep-web` has `BookkeepApplication`, `aspect/LogAspect`, `config/StartupInfoRunner`, `config/SecurityConfig`, `handler/GlobalExceptionHandler`, plus tests (`DataSourceConnectionTest`, `UserMapperTest`, `BookMapperTest`, `CategoryMapperTest`, `GlobalExceptionHandlerTest`).
- No services/controllers/DTOs yet beyond the above. Treat `PLAN.md` as the spec — do not assume code referenced there already exists.
- `SecurityConfig` is the **day-3 temporary** version (`anyRequest().permitAll()`, stateless, only exposes `BCryptPasswordEncoder`); it gets tightened on day 6 with `JwtAuthenticationFilter` + 401/403 JSON handlers. `JwtUtil.generateToken` exists but **nothing calls it yet** — token issuance lands in `AuthService.login` (day 5).
- Exception responses are currently **HTTP 200 with the business code in `body.code`** (see `GlobalExceptionHandler` javadoc). This conflicts with PLAN §5 ("HTTP status = code"); PLAN day 7 now records the decision: business errors stay 200 + body.code, only 401/403 use real status codes.
- SQL lives in `sql/`: `schema.sql` (full schema, for fresh installs) and `migration_add_user_avatar.sql` (one-off `ALTER` for an already-built DB).
- Commits exist now (latest: `P1：SecurityConfig + 全局异常处理（第 3 天）`). A root `.gitignore` covers `target/`, `logs/`, `.idea/`, `.env`, `application-local.yml` — still be deliberate about what you stage.

## Modules

- Dependency direction is one-way: `bookkeep-web → bookkeep-service → bookkeep-common`. Never reverse it.
- Parent pom (packaging=pom) holds `dependencyManagement`; submodules declare dependencies **without versions**.
- All modules share package root `com.bookkeep`, but the app class lives in `com.bookkeep.web` — default component scan would only cover `com.bookkeep.web`, so `BookkeepApplication` **must** keep `scanBasePackages = "com.bookkeep"` or `@Component`/`@Service` beans in `common`/`service` will not be found. Keep `@MapperScan("com.bookkeep.service.mapper")` as well.
- `application.yml` and `logback-spring.xml` live **only** in `bookkeep-web`, which is the only runnable module (it owns `spring-boot-maven-plugin` + `build-info`).

## Build / run

- No Maven wrapper — use system `mvn`.
- Build: `mvn clean package` (root). Run: `mvn spring-boot:run` from `bookkeep-web`.
- Requires local MySQL (8.4 in this environment) database `bookkeep` (utf8mb4). Credentials are **not** hardcoded: `application.yml` uses `${DB_USER}` / `${DB_PWD}` and fails fast if unset.
  - PowerShell session: `$env:DB_USER="root"; $env:DB_PWD="..."` **in the same window** before `mvn spring-boot:run` / `mvn test`.
- JDBC URL must use `characterEncoding=UTF-8`, **not** `utf8mb4` (Connector/J takes a Java charset name; `utf8mb4` throws `UnsupportedEncodingException`).
- To (re)build the DB manually: `& "D:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe" -uroot -p -e "source D:/coding/bookkeep-server/sql/schema.sql"`. PowerShell does **not** support `<` redirection — use `source`, not `mysql < file`.
- Logs roll into `logs/` (daily, 30-day retention, ERROR split to `-error.log`); SQL logging on below `prod` profile.

## Conventions to follow when implementing (from PLAN.md)

- API prefix `/api`, envelope `{code, message, data}` (`Result`/`ResultCode`/`PageResult`/`BusinessException` belong in `common`), `code=200` success, 401 JSON on bad token.
- Data isolation: front-end never sends `book_id`; backend derives it from `user.current_book_id` and reads the user via `SecurityUtil.getUserId()` (not by re-parsing the token). Every query must enforce book ownership.
- Service layer is interface + `impl` (e.g. `AuthService`/`AuthServiceImpl`). Entities/mappers/dtos live in `bookkeep-service`.
- Money is `BigDecimal` with `DECIMAL(12,2)` columns — never `double`.
- Logic delete is configured globally but **only applies to MyBatis-Plus generated SQL** — handwritten `@Select`/statistics SQL must add `deleted=0` itself.
- `budget` has **no `deleted` column** (see PLAN §3): do not put `@TableLogic` on the `Budget` entity; budgets are hard-deleted so the `(book_id, category_id, month)` unique key doesn't collide on re-create.
- `transaction` is a keyword-ish table name — backtick it in handwritten SQL.
- Dates pass as `yyyy-MM-dd`; statistics match via `DATE_FORMAT(trans_date,'%Y-%m')=#{month}`. Jackson is GMT+8.
- Business code logs via Lombok `@Slf4j`; controller calls are auto-logged and password/token-masked by `LogAspect` — don't duplicate logging in controllers. `LogAspect` JSON-serializes args, so `MultipartFile` upload args must be skipped/placeholder-ed.
- jjwt is 0.12.x: `signWith(SecretKey)` requires `Keys.hmacShaKeyFor(...)`, not a raw string.
- Avatars: store only a **URL string** in `user.avatar`; images go to Aliyun OSS behind a `FileService` interface (`upload(InputStream, String filename)` — do **not** put `MultipartFile` in the service layer). OSS endpoint/bucket/AK/SK/urlPrefix come from env vars, never committed. On JDK 17 `aliyun-sdk-oss` additionally needs `jaxb-api` / `jaxb-runtime` / `activation`.

## Testing

- Tests live in each module's own `src/test/java`; `@SpringBootTest` + MockMvc integration tests go in `bookkeep-web` (full context, needs MySQL + env vars). Test classes must end in `Test` (Surefire default). `spring-security-test` is already declared in `bookkeep-web`.
- `DataSourceConnectionTest` is the current smoke test (connection validity, `SELECT 1`, table count); it reads `application.yml`, so `DB_USER`/`DB_PWD` must be set or it fails with an unresolved-placeholder error.
- `GlobalExceptionHandlerTest` deliberately defines its own throw-away endpoints in a nested `@TestConfiguration` + `@RestController` (never in `src/main`, or they would ship as public endpoints). It asserts HTTP 200 + `body.code`/`message`. **When day 6 tightens `SecurityConfig`, all of its cases will return 401** — add `@WithMockUser` (keeps the filter chain) or `@AutoConfigureMockMvc(addFilters = false)` (cleanest when only testing exception mapping).
- Tests that write data (e.g. `UserMapperTest`) put `@Transactional` on the test class, so Spring rolls back after each method — the rows are **never persisted**, so do not go looking for test data in MySQL. `-DskipTests` also skips nothing but compilation, so "green build" alone means tests were not run; use `mvn -pl bookkeep-web -am test` with env vars set.
