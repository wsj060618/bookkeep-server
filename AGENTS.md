# AGENTS.md

Multi-module Spring Boot 3.3.5 / JDK 17 / Maven backend for a bookkeeping web app. Four modules: `bookkeep-common` (Result/exceptions/JWT), `bookkeep-model` (entities + DTOs), `bookkeep-service` (mappers/services), `bookkeep-web` (controllers/config). `PLAN.md` (in Chinese) is the authoritative design doc — read it before writing code. This file only captures what is not obvious from the repo.

## Current state (important)

- P1 in progress (days 0–5 done, day 6 = `SecurityUtil` + `JwtAuthenticationFilter` + tighten `SecurityConfig` + `/api/auth/me`). `bookkeep-common` has `Result`/`ResultCode`/`PageResult`/`BusinessException`/`JwtUtil`; `bookkeep-model` has `entity/{User,Book,Category}`, `dto/req/{RegisterReq,LoginReq}`, `dto/resp/{UserResp,BookResp,LoginResp}`; `bookkeep-service` has `mapper/{User,Book,Category}Mapper`, `constant/CategoryTemplate`, `AuthService` + `impl/AuthServiceImpl`; `bookkeep-web` has `BookkeepApplication`, `aspect/LogAspect`, `config/StartupInfoRunner`, `config/SecurityConfig`, `handler/GlobalExceptionHandler`, `controller/AuthController`, plus tests (`DataSourceConnectionTest`, `UserMapperTest`, `BookMapperTest`, `CategoryMapperTest`, `GlobalExceptionHandlerTest`, `AuthRegisterTest`, `AuthLoginTest`).
- `POST /api/auth/register` and `POST /api/auth/login` exist. Login returns `{token, user, books}` (**flat**, `books` is a sibling of `user`, not nested inside it — PLAN §5.1 records this). `SecurityUtil`, `/api/auth/me`, and every other controller/service are still to be written. Treat `PLAN.md` as the spec — do not assume code referenced there already exists.
- `SecurityConfig` is the **day-3 temporary** version (`anyRequest().permitAll()`, stateless, only exposes `BCryptPasswordEncoder`); it gets tightened on day 6 with `JwtAuthenticationFilter` + 401/403 JSON handlers. `JwtUtil.generateToken` is called by `AuthServiceImpl.login`; `parseUserId` has no caller yet (land in `SecurityUtil`, day 6).
- Exception responses are currently **HTTP 200 with the business code in `body.code`** (see `GlobalExceptionHandler` javadoc). This conflicts with PLAN §5 ("HTTP status = code"); PLAN day 7 now records the decision: business errors stay 200 + body.code, only 401/403 use real status codes.
- SQL lives in `sql/`: `schema.sql` (full schema, for fresh installs) and one-off `ALTER` migrations for already-built DBs (`migration_add_user_avatar.sql`, `migration_username_reusable.sql`).
- Commits exist now (latest: `P1：SecurityConfig + 全局异常处理（第 3 天）`). A root `.gitignore` covers `target/`, `logs/`, `.idea/`, `.env`, `application-local.yml` — still be deliberate about what you stage.

## Modules

- Dependency direction is one-way: `bookkeep-web → bookkeep-service → bookkeep-model`, and `bookkeep-service → bookkeep-common`. `bookkeep-model` and `bookkeep-common` are leaves that must **not** depend on each other, on service, or on web (model = data shapes only, common = envelope + utilities).
- Parent pom (packaging=pom) holds `dependencyManagement`; submodules declare dependencies **without versions**. Do **not** add a `<dependencies>` block to the parent (the reference project VirtueEdu does that and ends up polluting every leaf module with `spring-boot-starter-web`).
- All modules share package root `com.bookkeep`, but the app class lives in `com.bookkeep.web` — default component scan would only cover `com.bookkeep.web`, so `BookkeepApplication` **must** keep `scanBasePackages = "com.bookkeep"` or `@Component`/`@Service` beans in `common`/`service` will not be found. Keep `@MapperScan("com.bookkeep.service.mapper")` as well.
- `bookkeep-model` depends only on `mybatis-plus-annotation` + `jakarta.validation-api` + lombok: entities carry `@TableName/@TableId/@TableLogic`, DTOs carry `@NotBlank/@Size/@Email`. Changing a table's columns means editing an entity here, not in `service`.
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
- Login never distinguishes "user not found" from "wrong password" — both throw `BusinessException(BAD_REQUEST, "用户名或密码错误")`, otherwise the endpoint becomes a username oracle. `AuthLoginTest.unknownUserMessageIsIdentical` guards this. Soft-deleted users fall into the same branch for free (MP's logic-delete filter hides them from `selectOne`).
- Service layer is interface + `impl` (e.g. `AuthService`/`AuthServiceImpl`, interface in `com.bookkeep.service`, impl in `com.bookkeep.service.impl`). Entities/DTOs live in `bookkeep-model` (`com.bookkeep.model.entity`, `com.bookkeep.model.dto.req|resp`); mappers live in `bookkeep-service`.
- `bookkeep-service` has no `spring-security` dependency of its own — `PasswordEncoder` reaches it transitively via `bookkeep-common`'s `spring-security-core`, and the only bean is declared in `bookkeep-web`'s `SecurityConfig`. Inject the interface, not `BCryptPasswordEncoder`.
- Batch inserts use MyBatis-Plus `Db.saveBatch(list)` (JDBC batch, joins the ambient Spring transaction) rather than hand-written `<foreach>` SQL. It does **not** back-fill generated ids.
- `AuthServiceImpl.register` deliberately pre-checks the username **and** catches `DuplicateKeyException`. The pre-check only gives a friendly message; real uniqueness comes from the **generated-column** index `uk_user_username_active` (`IF(deleted=0, username, NULL)`), so a **soft-deleted user no longer holds the name** — re-registering a 注销过的 username is legitimate and guarded by `AuthRegisterTest.softDeletedUsernameCanBeReused`. `DuplicateKeyException` is now only for two *concurrent* live registrations of the same name.
- `user.username` is `utf8mb4_0900_as_cs`: usernames are **case-sensitive**, `Tom` and `tom` are two accounts. Do not "helpfully" lowercase usernames in code. Consequence: any query by username must add `deleted=0` (MP does it automatically; handwritten SQL must), otherwise a live row and its tombstone both match.
- Collation is deliberately two-layered: database/tables/every other column are `utf8mb4_0900_ai_ci` (MySQL 8 default), only `user.username` / `user.username_active` are `utf8mb4_0900_as_cs`. Comparing **two columns** with different collations raises `Illegal mix of collations`; comparing a column with a string parameter is fine. Never compare `username` to another table's text column.
- 封禁 must **not** reuse `deleted` — the generated column only looks at `deleted`, so marking a ban there frees the name for impersonation. Bans need a separate `status` column with `deleted=0`.
- Money is `BigDecimal` with `DECIMAL(12,2)` columns — never `double`.
- Logic delete is configured globally but **only applies to MyBatis-Plus generated SQL** — handwritten `@Select`/statistics SQL must add `deleted=0` itself.
- `budget` has **no `deleted` column** (see PLAN §3): do not put `@TableLogic` on the `Budget` entity; budgets are hard-deleted so the `(book_id, category_id, month)` unique key doesn't collide on re-create.
- `transaction` is a keyword-ish table name — backtick it in handwritten SQL.
- Dates pass as `yyyy-MM-dd`; statistics match via `DATE_FORMAT(trans_date,'%Y-%m')=#{month}`. Jackson is GMT+8.
- Business code logs via Lombok `@Slf4j`; controller calls are auto-logged and password/token-masked by `LogAspect` — don't duplicate logging in controllers. `LogAspect` JSON-serializes args, so `MultipartFile` upload args must be skipped/placeholder-ed.
- Log levels: `LogAspect` records **every** controller exception at **ERROR** — business ones (`BusinessException`, e.g. wrong password, duplicate username) without a stack trace, unexpected ones with the full stack. `GlobalExceptionHandler` additionally logs business errors at WARN. Consequence: `logs/*-error.log` is **not** "only real bugs" — two failed logins produce two ERROR lines by design. Don't add ERROR-level alerting on that file without filtering `业务异常`.
- jjwt is 0.12.x: `signWith(SecretKey)` requires `Keys.hmacShaKeyFor(...)`, not a raw string.
- Avatars: store only a **URL string** in `user.avatar`; images go to Aliyun OSS behind a `FileService` interface (`upload(InputStream, String filename)` — do **not** put `MultipartFile` in the service layer). OSS endpoint/bucket/AK/SK/urlPrefix come from env vars, never committed. On JDK 17 `aliyun-sdk-oss` additionally needs `jaxb-api` / `jaxb-runtime` / `activation`.
- Known gaps are tracked in **PLAN §9** (no rate limiting, email is display-only, no password reset, register is not idempotent). They are deliberate for P1 — do not silently "fix" them mid-phase, and do not open the API to the public before they are addressed.

## Testing

- Tests live in each module's own `src/test/java`; `@SpringBootTest` + MockMvc integration tests go in `bookkeep-web` (full context, needs MySQL + env vars). Test classes must end in `Test` (Surefire default). `spring-security-test` is already declared in `bookkeep-web`.
- `DataSourceConnectionTest` is the current smoke test (connection validity, `SELECT 1`, table count); it reads `application.yml`, so `DB_USER`/`DB_PWD` must be set or it fails with an unresolved-placeholder error.
- `GlobalExceptionHandlerTest` deliberately defines its own throw-away endpoints in a nested `@TestConfiguration` + `@RestController` (never in `src/main`, or they would ship as public endpoints). It asserts HTTP 200 + `body.code`/`message`. **When day 6 tightens `SecurityConfig`, all of its cases will return 401** — add `@WithMockUser` (keeps the filter chain) or `@AutoConfigureMockMvc(addFilters = false)` (cleanest when only testing exception mapping).
- Tests that write data (e.g. `UserMapperTest`) put `@Transactional` on the test class, so Spring rolls back after each method — the rows are **never persisted**, so do not go looking for test data in MySQL. `-DskipTests` also skips nothing but compilation, so "green build" alone means tests were not run; use `mvn -pl bookkeep-web -am test` with env vars set.
