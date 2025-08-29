# Robot Framework – Java Remote Keywords (rf-java)

A plug‑and‑play, **Lego‑style** Robot Framework setup powered by a Java Remote Server.  
It exposes reusable keyword packs for **REST APIs**, **JSON**, **SQL**, **Excel**, and **FIX** so multiple teams can mix only the blocks they need.

> Built for reusability and demo‑friendly onboarding: drop in a keyword class and the server auto‑publishes it as a Remote library at `http://<host>:<port>/<pack>`.

# Framework Architecture — Robot Framework + Java Remote Keywords

This document explains the **architecture** of the rf‑java framework. It focuses on modules, interactions, lifecycle, and extensibility.

---

## 1) High‑level overview

```
+---------------------------+        +------------------------+
|         Robot CLI         |        |  Robot Remote Library  |
|  (robot / rf runner)      |        |  Proxy (per endpoint)  |
+------------+--------------+        +-----------+------------+
             | HTTP (XML-RPC/JSON-RPC)                     
             v                                              
+-----------------------------------------------------------+
|               Java Remote Keyword Server                  |
|                    (KeywordServer)                        |
|  Routes:  /rest   /json   /sql   /excel   /fix            |
|           |        |        |        |        |            |
|  AnnotationLibrary scans & loads keyword classes          |
|  (RestKeywords, JsonKeywords, SqlKeywords,                |
|   ExcelKeywords, FixKeywords, …)                          |
|                                                           |
|  Shared Core                                               |
|   - ApiSessionStore  - FileUtils  - TemplateUtils         |
|   - EnvUtils          - Config (application.conf)         |
|                                                           |
|  Logging: Log4j2 (log4j2.xml)                     |
+-----------------------------------------------------------+
             |                                            
             | Integrations                               
             v                                            
+-----------------+   +-----------------+   +--------------------+
|   HTTP(S) APIs  |   |   Databases     |   |  FIX Counterparty  |
| (RestAssured)   |   | (JDBC)          |   | (QuickFIX/J cfg)   |
+-----------------+   +-----------------+   +--------------------+
```

**Key idea:** The server exposes **modular keyword packs** as HTTP endpoints. Robot connects remotely, calls keywords, and receives results as RPC responses. Keyword packs encapsulate tooling for **REST**, **JSON**, **SQL**, **Excel**, and **FIX**.

---

## 2) Modules & responsibilities

### 2.1 Keyword Server
- **`KeywordServer.java`**  
  - Boots a **RemoteServer**.
  - Creates route → library mappings (e.g., `/rest` → `AnnotationLibrary` with package glob).
  - Centralizes **port/host** configuration via system props (`rf.host`, `rf.port`) or defaults.
  - Ensures each pack is isolated by path, simplifying import and dependency boundaries.

### 2.2 Keyword Packs (Libraries)
- **REST (`RestKeywords.java`)**
  - Uses **RestAssured** to manage sessions (`Create API Session`) and execute methods (GET/POST/PUT/DELETE).
  - Normalizes responses into an internal store by **alias** (e.g., `last`, `post1`) for later assertions/extractions.
  - Integrates with **ApiSessionStore** for base URLs, headers, auth, cookies.
- **JSON (`JsonKeywords.java`)**
  - Provides **JsonPath** extraction, transformations, and lightweight validations over values captured by REST keywords or read from files.
- **SQL (`SqlKeywords.java`)**
  - JDBC connect/disconnect, query/execute helpers.
  - Returns scalar, row, and list structures in Robot‑friendly shapes.
- **Excel (`ExcelKeywords.java`)**
  - Read/write XLSX; used for data‑driven tests and result logging.
  - Abstracted via a simple API to minimize direct Apache POI exposure to suites.
- **FIX (`FixKeywords.java`)**
  - Uses **QuickFIX/J** initiator configured through `fixInitiator.cfg`.
  - Start/stop session, logon assertions, message send/receive using templates.
  - Centralizes FIX plumbing so suites only express intent.

> Keyword packs are discovered by **AnnotationLibrary** scanning Java packages for `@RobotKeywords`/`@RobotKeyword` methods. This allows truly **plug‑and‑play** additions.

### 2.3 Core Utilities
- **`ApiSessionStore.java`**  
  - Thread‑safe store mapping **session names** → RestAssured `RequestSpecification` + metadata.
  - Encapsulates base URL, default headers, auth, cookies, and last `Response` handle per alias.
- **`FileUtils.java`**  
  - File read/write helpers (UTF‑8, JSON, binary) with predictable error handling.
- **`TemplateUtils.java`**  
  - Simple string/JSON templating, parameter replace, payload assembly from fragments.
- **`EnvUtils.java`**  
  - Consumes system properties and environment variables, with sensible defaults and fallbacks.
- **Configuration files**
  - `application.conf` — logical config hub (base URLs, DB creds placeholders, folders).
  - `fixInitiator.cfg` — QuickFIX/J sessions.
  - `log4j2.xml` — logging backend and appenders.
  - `login.json` — sample inputs (non‑secrets).

### 2.4 Build & Packaging
- **`pom.xml`**
  - Declares dependencies (RestAssured, QuickFIX/J, JDBC drivers, JsonPath, Log4j2, Robot Java Lib).
  - **Shade plugin** builds a single runnable JAR embedding dependencies for easy distribution.
  - Profiles or properties can tune versions and optional packs.

---

## 3) Runtime lifecycle

1. **Startup**
   - `KeywordServer` resolves `rf.host`/`rf.port` and initializes `RemoteServer`.
   - For each pack, it registers `AnnotationLibrary("<package‑glob>")` under a **route** (`/rest`, `/json`, `/sql`, `/excel`, `/fix`).  
   - Log4j2 initializes sinks and levels from `log4j2.xml`.
2. **Discovery**
   - `AnnotationLibrary` scans classpath for `@RobotKeywords` classes and methods.
   - Any class not found or missing annotations → not exposed (prevents accidental leakage).
3. **Execution**
   - Robot calls `Remote` at `http://host:port/<route>` with keyword name + args.
   - The server invokes the Java method, returns serialized result or error.
   - **Sessions** (API/FIX/DB/Excel) are pooled via the respective stores; aliases keep operations stateless for the test runner.
4. **Shutdown**
   - Server stops; long‑lived connectors (FIX initiator, DB connections) are closed gracefully.
   - Log buffers flush.

---

## 4) Data & control flows

### 4.1 REST + JSON
```
Robot → Remote(/rest) → RestKeywords:Create API Session → ApiSessionStore[echo]
Robot → Remote(/rest) → RestKeywords:Get/Post → Response(alias="last")
Robot → Remote(/json) → JsonKeywords:Extract JsonPath($.foo) from Response("last")
Robot ← value
```

### 4.2 SQL
```
Robot → Remote(/sql) → SqlKeywords:Connect(jdbcUrl, user, pass)
Robot → Remote(/sql) → SqlKeywords:Query / Execute
Robot ← rows / scalar / update count
Robot → Remote(/sql) → SqlKeywords:Disconnect
```

### 4.3 Excel
```
Robot → Remote(/excel) → ExcelKeywords:Open → Read/Write → Close
```

### 4.4 FIX
```
Robot → Remote(/fix) → FixKeywords:Start Initiator (cfg)
QuickFIX/J session ↔ Counterparty
Robot → Remote(/fix) → FixKeywords:Send/Expect
Robot → Remote(/fix) → FixKeywords:Stop
```

---

## 5) Cross‑cutting concerns

- **Logging**: Log4j2. Use `INFO` for lifecycle, `DEBUG/TRACE` for payloads (guard secrets). Configure appenders in `log4j2.xml`.
- **Error handling**: Keywords should:
  - Throw informative runtime exceptions mapped to Robot FAIL messages.
  - Include context (endpoint SQL/FIX tag) without leaking secrets.
- **Thread safety**: `ApiSessionStore` and FIX/JDBC/POI usage must be guarded:
  - Prefer per‑test alias isolation.
  - Avoid static/global mutable state beyond the scoped stores.
- **Configuration**: Read from `application.conf`/env; never hardcode credentials. Allow override via `-D` system properties.
- **Security**: Keep secrets out of VCS. Accept tokens via env/CI secret stores. Disable payload logging in prod runs.
- **Observability**: Add correlation IDs in headers; include request/response timing in logs.

---

## 6) Extensibility model (“Lego blocks”)

1. **Create a keyword class** in `com.example.rf.keywords.<pack>` and annotate:
   ```java
   @RobotKeywords
   public class MyServiceKeywords {
     @RobotKeyword
     @ArgumentNames({ "param1", "param2" })
     public String doThing(String p1, String p2) { ... }
   }
   ```
2. **Expose it via a route** in `KeywordServer`:
   ```java
   server.addLibrary("/myservice",
     "org.robotframework.javalib.library.AnnotationLibrary",
     "com.example.rf.keywords.myservice.*");
   ```
3. **Rebuild the shaded JAR** and restart the server.  
   Robot can now import `Remote  http://host:port/myservice`.

> Packs can be independently versioned by package path; you can maintain **optional** packs and include them by Maven profile.

---

## 7) Deployment & CI/CD

- **Build**: `mvn -B -DskipTests package` → shaded JAR in `target/`.
- **Runtime**: `java -Drf.port=8270 -Drf.host=0.0.0.0 -jar target/*.jar`
- **Pipelines**:
  - Stage 1: compile/package
  - Stage 2: spin up server; health‑check routes (`/rest`, `/sql`, ...)
  - Stage 3: run Robot suites against Remote endpoints
  - Stage 4: publish reports (robot framework default HTML), archive logs
- **Scaling**:
  - Horizontal: run multiple servers on different ports/nodes.
  - Isolation: run per‑team servers with only needed packs enabled.

---

## 8) Dependency stack (typical)

- **Robot Java Library** (`org.robotframework:javalib-core`) — keyword annotations & remote server.
- **HTTP**: `io.rest-assured:rest-assured`
- **JSON**: `com.jayway.jsonpath:json-path`
- **DB**: JDBC drivers (PostgreSQL/MySQL/SQLServer) as needed
- **Excel**: Apache POI (via ExcelKeywords)
- **FIX**: QuickFIX/J
- **Logging**: `org.apache.logging.log4j:log4j-core`, `log4j-slf4j2-impl`

> Exact versions are managed in `pom.xml`. Use BOMs or properties for consistency.

---

## 9) Operational runbook (issues seen in logs)

- **“No SLF4J providers were found”** → add `log4j-slf4j2-impl` and `log4j-core`; include `log4j2.xml` on classpath.
- **“Mapped path … to java.lang.Class” / “0 keywords”** → ensure `AnnotationLibrary` points to the correct package glob; classes compiled into shaded JAR; `@RobotKeywords` present.
- **JsonPath casting** → return types depend on payload. Maintain strict paths (e.g., `$.args.foo` when object). Avoid unnecessary `[...]` indexing unless arrays.
- **Remote not reachable** → check port/IP, local firewalls, VPNs; use `127.0.0.1` for local first.

---
## ✅ Prerequisites

- **Java 17+**
- **Maven 3.8+**
- **Python 3.10+** with **Robot Framework** installed
  ```bash
  pip install robotframework
  # optional: libdoc for remote libraries
  pip install robotframework-libdoc
  ```

---

## 🔧 Build

```bash
# from the project root
mvn -B -DskipTests package
```
This produces a shaded (fat) JAR in `target/`.

---

## 🚀 Run the keyword server

You can pass host/port via JVM system properties:

```bash
java -Drf.port=8270 -Drf.host=0.0.0.0 \
     -jar target/rf-remote-keywords-1.0.0-shaded.jar
```

Or run from your IDE using `KeywordServer` as the **Main class** with VM args:
```
-Drf.port=8270 -Drf.host=0.0.0.0
```

You should see lines like:
```
Mapped path /rest  → REST keyword pack
Mapped path /json  → JSON keyword pack
Mapped path /sql   → SQL keyword pack
Mapped path /excel → Excel keyword pack
Mapped path /fix   → FIX keyword pack
Robot Framework remote server starting
Keyword server started at http://0.0.0.0:8270
```

> **Note:** If you see `0 keywords` or routes mapped to `java.lang.Class`, it means the annotation scan didn’t find your keyword classes. See *Troubleshooting* below.

---

## ⚙️ Configuration

- **`application.conf`**  
  Central app/test config. Use it for base URLs, DB creds, file paths, etc.
- **`login.json`**  
  Example payload or credentials store for API tests.
- **`fixInitiator.cfg`**  
  QuickFIX/J initiator configuration (sessions, host/port, sender/target compIDs).
- **`log4j2.xml`**  
  Logging config; ensures SLF4J has a concrete backend.

> You can also pass values with env vars: `-Denv.NAME=value` or `ENV=VALUE` and read them from `EnvUtils` in keywords.

---

## 🧪 Using from Robot Framework

### Import Remote libraries
```robot
*** Settings ***
Library   Remote   http://127.0.0.1:8270/rest    WITH NAME    REST
Library   Remote   http://127.0.0.1:8270/json    WITH NAME    JSON
Library   Remote   http://127.0.0.1:8270/sql     WITH NAME    SQL
Library   Remote   http://127.0.0.1:8270/excel   WITH NAME    EXCEL
Library   Remote   http://127.0.0.1:8270/fix     WITH NAME    FIX
Resource  resources/variables.resource
Resource  resources/keywords.resource
```

### Example: API smoke (`api_smoke.robot`)
```robot
*** Variables ***
${BASE}      https://postman-echo.com
&{EMPTY}     # an empty dict

---

## 🏗️ CI tips

- `mvn -B -DskipTests package` for faster packaging
- Start the server as a background step, then run Robot suites:
  ```bash
  java -Drf.port=8270 -jar target/*.jar &
  robot -d results tests/
  ```
- Archive `results/` or publish Allure/HTML reports as needed.

---

## 🔐 Security & secrets

Do **not** commit real credentials. Use env vars, CI secrets, or an encrypted secrets store. `login.json` should be sample/dev‑only.

---

## 📄 License

Internal/POC use. Add your preferred OSS license if publishing.

---

## 🙋‍♀️ Support

If you get stuck, run with a higher log level (log4j2) and share:
- your `pom.xml` (build plugins, dependencies)
- the server startup log
- example Robot test and the failing keyword call

Happy testing! 🚀

