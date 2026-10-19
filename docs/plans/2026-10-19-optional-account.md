# 游客优先的可选账户模块实施计划

**目标：** 保持 PDF 工作台默认游客直接使用，仅在原顶栏增加可选账户入口，提供注册、登录、昵称修改、密码修改和退出。

**架构：** 原工作台承载局部账户弹窗；账户状态与 PDF、智能阅读状态分离。账户接口使用独立的 Spring Security 安全链、服务端 Session 与 JDBC/H2 账户存储，不将登录引入原有业务接口。

**技术栈：** Vue 3、TypeScript、原生 HTML dialog、Vitest、Playwright；Java 21 编译目标、Spring Boot 3.5.14、Spring JDBC、H2、Spring Security、JUnit。

## 1. 业务边界

1. 首次访问仍进入 `/#/text`，不显示登录首页、不自动弹窗、不创建游客账户记录。
2. 文本、表格、图片、加密、签名、水印、文档信息与智能阅读均保持原有使用条件。账户与游客使用相同的文档功能。
3. 原工作台仅新增一个顶栏入口；不替换侧栏、文档预览、参数面板、结果区或智能阅读布局。
4. 登录、退出和会话到期不刷新页面、不跳转工具路由、不清空当前文件和已生成内容。
5. 仅保存账户资料，不新增云文档、个人文档列表、账户阅读历史、跨设备同步或现有历史迁移。
6. 现有智能阅读资料仍遵循本机存储方式，账户不提供这些资料的多用户隔离。
7. 不包含头像上传、邮箱修改、邮箱验证、密码找回、第三方登录、会员、团队或管理后台。
8. 同一页面内的身份操作保留工作台状态；手动刷新或关闭浏览器后的文件恢复不新增行为。

## 2. UI 与交互规格

### 2.1 唯一入口

- 位置：`WorkbenchView.vue` 的 `.topbar-right`，放在现有 API 文档链接之后。
- 游客文案：“登录”；已登录文案：昵称及账户图标，按钮可访问名称为“账户设置”。
- API 文档链接和工具说明保留原有行为。入口独立设置间距，不依赖小屏下已隐藏的 `.sidebar-footer`。
- 入口采用已有蓝白配色和字体，保持轻量按钮层级；长昵称截断显示，完整昵称保留在可访问名称中。
- 320、390、768、960、1440、1920 px 均检查标题、文档链接与账户入口的排布，不允许新增横向溢出。

### 2.2 弹窗结构

| 面板     | 字段与操作                                     | 成功结果                                                   | 退出方式                           |
| -------- | ---------------------------------------------- | ---------------------------------------------------------- | ---------------------------------- |
| 登录     | 邮箱、密码、密码显隐；登录、创建账户           | 更新入口昵称，关闭弹窗，停留原工具                         | 关闭按钮、Escape、“继续以游客使用” |
| 注册     | 昵称、邮箱、密码、确认密码；创建账户、返回登录 | 切回登录，填入注册邮箱，清空密码；显示“账户已创建，请登录” | 同登录                             |
| 个人资料 | 昵称、只读登录邮箱；保存                       | 面板与顶栏昵称同步更新                                     | 关闭按钮、Escape                   |
| 账户安全 | 当前密码、新密码、确认新密码；更新密码         | 使旧登录态失效，恢复游客入口；提示重新登录为可选操作       | 关闭按钮、Escape                   |

- 采用一个原生 `dialog`；登录、注册约 440 px 宽，资料与安全约 560 px 宽。
- 窄屏宽度为视口减 32 px，内容纵向滚动；输入文字至少 16 px，主要点击区域至少 44 px。
- 弹窗具备标题关联、初始焦点、焦点限制、Escape 关闭和关闭后焦点返回。遮罩点击不触发提交。
- 弹窗只覆盖工作台，不能通过路由切换、`key` 重置或条件挂载卸载 `WorkbenchView`、`PdfPreview`、`AiAssistant`。
- 身份输入框不复用 PDF 打开密码字段；关闭、切换身份面板、提交完成后清空密码。
- 加载状态不伪造成功结果，提交时禁止重复点击；错误放在相应面板，不覆盖工作台的 PDF 错误。
- 登录请求发送后仍允许关闭弹窗。关闭不等于撤销服务端操作；请求结束后同步真实会话，不能重新打开已关闭的弹窗。
- 界面配色以现有蓝白风格为集成基线；A/B/C 全页原型只保留为视觉参考。正式预览采用“原工作台 + 单入口 + 弹窗”结构。

### 2.3 状态规则

| 事件                     | 账户处理                                                             | 工作台处理                        |
| ------------------------ | -------------------------------------------------------------------- | --------------------------------- |
| 初次访问                 | 异步查询一次会话；查询前仍显示登录入口                               | 立即可用，不等待账户请求          |
| 返回未登录状态           | 使用游客入口，不显示错误                                             | 保持原状                          |
| 会话查询网络错误或 503   | 入口仍可操作；打开面板后提示账户暂不可用并可重试，不宣称服务端已退出 | 保持原状                          |
| 登录失败                 | 保留邮箱、清空密码、显示统一错误                                     | 保持原状                          |
| 账户操作返回 401         | 清除本地账户资料，切换可选登录面板                                   | 不清空文件，不停止 PDF 或阅读任务 |
| 账户写请求返回 CSRF 错误 | 更新 CSRF 信息，提示重新提交；不自动重放写请求                       | 保持原状                          |
| 退出成功                 | 恢复游客入口并关闭面板                                               | 保留当前内容                      |
| 退出请求失败             | 不显示“已退出”；保留重试入口                                         | 保持原状                          |
| 旧账户请求晚于新操作返回 | 通过请求代次丢弃旧响应，不覆盖新状态                                 | 保持原状                          |

会话查询只在初次加载、打开账户面板和身份操作结束时按需执行，不增加轮询。其他标签页在再次打开账户面板或发起账户请求时重新校验身份。

## 3. 数据与接口规格

以下类型、字段、路径与错误码为新增模块的设计约定。

### 3.1 账户数据

独立账户文件默认位于 `${user.home}/.pdf-workbench/accounts/account` 对应的 H2 数据库，通过 `ACCOUNT_DATA_DIRECTORY` 覆盖目录。原 `history` 数据库不修改、不迁移。账户存储按需初始化，初始化失败返回账户错误，不能阻断应用启动或游客接口。

| 字段                 | 约束                                  | 用途                   |
| -------------------- | ------------------------------------- | ---------------------- |
| `id`                 | UUID，主键                            | 服务端账户标识         |
| `email`              | 规范化后最长 254 字符，非空，唯一索引 | 登录标识               |
| `display_name`       | 去除首尾空白后 1–24 个 Unicode 码点   | 界面昵称               |
| `password_hash`      | 非空，保存散列格式及参数              | 密码校验，不返回客户端 |
| `credential_version` | 正整数，初始为 1                      | 修改密码后使旧会话失效 |

- 邮箱去除首尾空白并使用 `Locale.ROOT` 统一小写，不移除内部字符、加号或句点。
- 密码不裁剪首尾空白；新密码长度为 12–128 个 Unicode 码点，前后端使用一致的计数规则。
- 密码采用 Spring Security 的 PBKDF2-HMAC-SHA256 编码器，计划参数为 600,000 次迭代、16 字节随机盐、256 位结果；格式包含算法与参数标识。
- 不增加明文密码字段、固定默认密码或预置管理员。无邮箱验证流程，界面不展示“邮箱已验证”。
- 所有 SQL 使用参数绑定。邮箱唯一性由数据库保证，不能只依赖注册前查询。
- 修改密码通过带 `credential_version` 条件的更新处理并发；更新密码与版本递增属于同一事务。

### 3.2 返回对象

- `AccountUser`：`id`、`email`、`displayName`；不包含散列、凭据版本或内部配置。
- `AccountSession`：`authenticated`、`user`；游客的 `user` 为 `null`。
- `CsrfInfo`：`headerName`、`token`；只在内存使用，不写 URL 或本地存储。
- 错误统一为现有 `ErrorBody`：`status`、`code`、`message`。框架过滤器错误也必须使用同一形状。

### 3.3 接口合同

所有写请求使用 JSON 正文并携带有效账户 CSRF 头；`logout` 正文可为空。注册只创建账户，不自动登录。

| 方法与路径                      | 输入                               | 成功响应                            | 主要失败条件                                                |
| ------------------------------- | ---------------------------------- | ----------------------------------- | ----------------------------------------------------------- |
| `GET /api/v1/account/session`   | 无                                 | 200，`AccountSession`               | 账户数据校验不可用：503                                     |
| `GET /api/v1/account/csrf`      | 无                                 | 200，`CsrfInfo`                     | 无法建立账户会话：503                                       |
| `POST /api/v1/account/register` | `email`、`displayName`、`password` | 201，`AccountUser`                  | 字段错误：400；邮箱不可用：409；请求过频：429               |
| `POST /api/v1/account/login`    | `email`、`password`                | 200，`AccountSession` 与会话 Cookie | 错误凭据：401；请求过频：429                                |
| `PATCH /api/v1/account/profile` | `displayName`                      | 200，更新后的 `AccountUser`         | 未登录：401；非法昵称：400                                  |
| `POST /api/v1/account/password` | `currentPassword`、`newPassword`   | 204，并结束当前登录态               | 未登录：401；当前密码错误或新旧相同：400；并发凭据变更：409 |
| `POST /api/v1/account/logout`   | 无                                 | 204，结束当前会话                   | CSRF 无效：403                                              |

- 确认密码为前端交互字段，不重复传给服务端。服务端仍独立校验所有接收字段。
- `profile`、`password` 的账户 ID 只来自服务端当前身份，不接受请求指定的目标 ID。
- `session` 在无 Cookie 时返回游客状态，不创建会话、不连接账户数据库；旧会话在校验失败后也返回游客状态。
- 无 Cookie 的 PDF 请求不得仅因账户模块而收到新会话 Cookie。
- 登录成功后更换 Session ID；注册不改变已有文档状态。带有效 CSRF 的重复退出保持无业务副作用；旧 CSRF 失效后须重新获取。
- 账户接口返回 `Cache-Control: no-store`；异常响应不含堆栈、SQL、密码或会话标识。

### 3.4 错误码

| 状态 | 代码                          | 语义                                       |
| ---- | ----------------------------- | ------------------------------------------ |
| 400  | `ACCOUNT_INVALID_INPUT`       | 账户字段不符合约束                         |
| 400  | `ACCOUNT_PASSWORD_REJECTED`   | 当前密码或新密码不满足修改条件             |
| 401  | `ACCOUNT_BAD_CREDENTIALS`     | 邮箱或密码不正确；不区分未知邮箱与密码错误 |
| 401  | `ACCOUNT_LOGIN_REQUIRED`      | 账户写操作缺少有效身份                     |
| 403  | `ACCOUNT_CSRF_INVALID`        | 账户 CSRF 校验失败                         |
| 409  | `ACCOUNT_EMAIL_UNAVAILABLE`   | 邮箱不能用于本次注册                       |
| 409  | `ACCOUNT_CREDENTIALS_CHANGED` | 密码已被其他请求修改                       |
| 429  | `ACCOUNT_TOO_MANY_ATTEMPTS`   | 超出账户请求频率限制，附重试时间           |
| 503  | `ACCOUNT_STORAGE_UNAVAILABLE` | 账户数据不可读写                           |

### 3.5 会话、安全链与限流

1. Spring Security 链只匹配 `/api/v1/account/**`。提供显式账户认证组件，不生成默认用户；关闭框架默认表单登录与 HTTP Basic，认证失败返回 JSON，而非跳转页面。
2. 会话查询、CSRF 获取、注册、登录与退出允许匿名；资料与密码操作要求账户身份。匿名写操作仍校验 CSRF。
3. 保留 `RequestPolicyFilter` 对原 PDF 与 AI 的来源、Host、查询参数限制，不放宽原规则。
4. Cookie 名称为 PDF_WORKBENCH_ACCOUNT，使用 HttpOnly、SameSite=Lax、Path=/api/v1/account，闲置会话默认 30 分钟到期；HTTPS 配置启用 Secure。本机默认 HTTP 开发地址保持可用。
5. 账户写接口保留 CSRF 校验。登录和退出后清理旧 CSRF，下一次写操作前获取新值。
6. 自定义 JSON 登录必须调用会话认证策略并显式保存 SecurityContext，不能只向请求上下文设置身份。
7. Session 保存账户 ID 与登录时凭据版本。会话查询与受保护账户写接口校验数据库中的最新版本；改密后版本递增，所有旧版本会话不能再访问账户资料或执行写操作。CSRF 获取与退出不依赖账户数据库，以便账户数据故障时仍可结束会话。
8. 版本校验过滤器只注册在账户安全链内，不作为全局 Servlet 过滤器重复注册；数据不可用时拒绝账户操作，不影响 PDF 接口。
9. 登录失败按“来源地址 + 规范化邮箱”计数，15 分钟内达到 5 次失败后返回 429；注册每来源地址 15 分钟最多 10 次。已有会话的改密失败也纳入账户尝试限制。
10. 单机限流使用有容量和过期边界的内存结构，不引入 Redis。使用可替换时钟进行测试；容量满时拒绝新的限流键，不通过无限增长或驱逐未过期键绕过限流。
11. 不将密码或会话凭据写入 `localStorage`、`sessionStorage`、URL、日志或截图；昵称使用 Vue 文本插值，不使用 HTML 渲染。

## 4. 文件变更清单

### 4.1 前端

| 操作 | 文件                                        | 职责                                                    |
| ---- | ------------------------------------------- | ------------------------------------------------------- |
| 新增 | `frontend/src/components/AccountDialog.vue` | 四种面板、表单验证、焦点与关闭行为                      |
| 新增 | `frontend/src/composables/useAccount.ts`    | 非阻塞会话恢复、操作状态、旧响应隔离                    |
| 新增 | `frontend/src/api/account.ts`               | 账户请求、CSRF 与结构化错误处理                         |
| 新增 | `frontend/src/types/account.ts`             | 接口 DTO 与面板状态类型                                 |
| 新增 | `frontend/src/assets/account.css`           | 账户限定样式与移动端规则                                |
| 修改 | `frontend/src/views/WorkbenchView.vue`      | 唯一入口与持久挂载的账户弹窗                            |
| 修改 | `frontend/src/components/AppIcon.vue`       | 补充账户图标，复用关闭与其他现有图标                    |
| 修改 | `frontend/vite.config.ts`                   | 代理目标支持 `PDF_WORKBENCH_BACKEND_URL`，默认仍为 8080 |
| 修改 | `frontend/package.json`                     | 新增隔离浏览器验收脚本 `test:account`                   |
| 新增 | `frontend/playwright.account.config.ts`     | 5182/8082 独立服务、独立数据目录、禁止复用外部分支服务  |
| 新增 | `frontend/tests/account-api.test.ts`        | 请求格式、CSRF、错误与超时                              |
| 新增 | `frontend/tests/account.test.ts`            | 会话、操作与异步竞态                                    |
| 新增 | `frontend/tests/account-dialog.test.ts`     | 表单与弹窗生命周期                                      |
| 新增 | `frontend/tests/e2e/account.spec.ts`        | 账户闭环与文档状态保留                                  |
| 修改 | `frontend/tests/e2e/workbench.spec.ts`      | 匿名访问、入口与账户故障兼容回归                        |

保留 `router/index.ts` 的默认地址与工具路由；不增加全局守卫。不修改 `api/pdf.ts`、`api/ai.ts` 的身份依赖或原 `useDocument`、`useAiAssistant` 的生命周期。

### 4.2 后端

下列类统一位于 `backend/src/main/java/cn/edu/pdfworkbench/` 下。

| 操作 | 相对文件                                     | 职责                                                     |
| ---- | -------------------------------------------- | -------------------------------------------------------- |
| 新增 | `auth/AccountTypes.java`                     | 请求记录、对外用户与会话 DTO                             |
| 新增 | `auth/AccountSettings.java`                  | 数据目录与限流参数                                       |
| 新增 | `auth/AccountRepository.java`                | 账户表、参数化查询、唯一约束、条件改密                   |
| 新增 | `auth/AccountService.java`                   | 字段规范化、密码校验和业务规则                           |
| 新增 | `auth/AccountAttemptLimiter.java`            | 登录、注册与改密失败的单机限流                           |
| 新增 | `auth/AccountSessionFilter.java`             | 账户安全链中的会话版本校验                               |
| 新增 | `auth/AccountController.java`                | JSON 接口、会话与业务服务衔接                            |
| 新增 | `config/AccountSecurityConfig.java`          | 账户链、编码器、会话认证策略与 JSON 失败响应             |
| 修改 | `exception/ApiExceptionHandler.java`         | 增加账户 DTO 校验错误映射，保留原 PDF 错误行为           |
| 修改 | `backend/pom.xml`                            | 添加 Boot 管理版本的 Security 与测试依赖，不新增 JPA/JWT |
| 修改 | `backend/src/main/resources/application.yml` | 账户目录、会话 Cookie 与到期配置                         |

后端测试统一位于 `backend/src/test/java/cn/edu/pdfworkbench/`：新增 `AccountRepositoryTest`、`AccountServiceTest`、`AccountAttemptLimiterTest`、`AccountApiTest`；扩展现有 `PdfApiTest` 与 `AiApiTest` 的匿名兼容断言。

## 5. 逐项实施任务

任务按顺序执行。UI 集成预览先于正式账户实现；每个代码任务采用“新增测试 → 执行并确认对应失败 → 最小实现 → 重跑通过”的顺序。

### P01：游客基线与隔离验证环境

**涉及文件：** `frontend/vite.config.ts`、`frontend/package.json`、`frontend/playwright.account.config.ts`、`frontend/tests/e2e/workbench.spec.ts`。

1. 记录新分支已有前端测试、构建、静态检查与后端测试结果，区分新增失败和已有失败。
2. 增加游客测试：无 Cookie 访问首页与七个工具地址，无强制登录页面或弹窗。
3. 让 Vite 的三个代理共享可覆盖的后端目标，未设置环境变量时行为仍为 8080。
4. 新建账户验收配置：前端 5182、后端 8082，`reuseExistingServer=false`；后端账号与 AI 数据限定在本工作目录的 `backend/target/account-e2e/` 下。
5. 测试配置通过 `webServer.env` 设置端口、代理目标与数据目录，不使用 Bash 环境变量语法。
6. 执行原单元测试与游客布局测试，确认新配置不会连接当前 5173/8080 服务。

**完成标准：** 游客基线可重复运行；占用测试端口时明确失败，不复用其他工作目录服务；测试不读写个人账户或 AI 历史目录。

**提交单元：** `test: establish isolated guest-first account baseline`。

### P02：原工作台入口与弹窗预览

**涉及文件：** `docs/ui/account/index.html`、`prototype.css`、`prototype.js`、`verify.cjs` 与 `previews/`。

1. 将静态预览入口改为游客工作台，在顶栏仅增加“登录”。
2. 将现有全页登录、注册、资料、安全参考改为覆盖原界面的弹窗；原文档区保留在背景。
3. 提供登录/注册切换、关闭、继续游客、提交中、错误和会话到期的局部交互预览。
4. 更新验证脚本，检查默认无弹窗、只存在一个入口、开关面板不替换文档区域，以及六种宽度。
5. 保存默认工作台、登录、注册、资料、安全和移动端截图。

**完成标准：** 可直观看到“原界面只多一个入口”，账户页面不作为默认首页；预览表单不调用真实账户接口。

**提交单元：** `design: preview optional account dialog in existing workbench`。

### P03：账户 DTO、字段规则与存储

**涉及文件：** `AccountTypes.java`、`AccountSettings.java`、`AccountRepository.java`、`AccountRepositoryTest.java`、`application.yml`。

1. 先测试账户记录的主键、邮箱唯一约束、独立文件位置及读写行为，服务层字段规范化与密码边界在 P04 验证。
2. 先测试同邮箱并发插入只创建一条记录，异常转换为 409。
3. 实现独立账户表和惰性数据源，不接触 `AiRepository` 的连接或表。
4. 测试重新打开同一数据库仍可查询账户，切换临时目录后数据互不混用。
5. 测试目录不可写与连接错误得到 503，应用可启动，游客 PDF 接口仍按原合同响应。

**执行入口：** `mvn -f backend/pom.xml "-Dtest=AccountRepositoryTest" test`。

**完成标准：** 账户读写、唯一性与存储异常均有测试；失败写入没有半写入账户。

**提交单元：** `feat: add isolated account persistence and validation`。

### P04：账户密码服务与限流

**涉及文件：** `AccountService.java`、`AccountAttemptLimiter.java`、对应单元测试、`pom.xml`。

1. 加入 Boot 管理的 Spring Security 依赖，核验解析版本与框架 API；应用安全配置与依赖在同一提交中交付，避免留下全站默认拦截的中间版本。
2. 配置 PBKDF2 编码器；测试邮箱规范化、昵称与密码码点边界、密码不裁剪、同密码不同盐、正确/错误匹配、散列格式与响应脱敏。
3. 实现注册、登录验证、昵称更新与条件改密；未知邮箱登录执行等价密码校验路径，返回统一错误。
4. 使用可替换时钟测试第 5 次失败、后续 429、窗口过期、不同键隔离与容量边界；注册与改密失败采用对应计数。
5. 测试并发改密只有一个版本更新成功，错误当前密码不改变散列或版本。

**执行入口：** `mvn -f backend/pom.xml "-Dtest=AccountServiceTest,AccountAttemptLimiterTest" test`。

**完成标准：** 不存明文、不记录输入密码；限流不无界增长；并发更新不会覆盖新凭据。

**提交单元：** 与 P05 的账户安全链一起构成 `feat: add scoped account authentication and password security`。

### P05：账户安全链、会话与 HTTP 合同

**涉及文件：** `AccountSecurityConfig.java`、`AccountSessionFilter.java`、`AccountController.java`、`ApiExceptionHandler.java`、`AccountApiTest.java`、`PdfApiTest.java`、`AiApiTest.java`。

1. 写 HTTP 测试：无 Cookie 的 PDF POST 与 AI 状态请求不需要账户或账户 CSRF；无 Cookie 的账户资料请求返回 JSON 401。
2. 实现严格账户路径匹配，不新增全站认证链、不开放原先被拒绝的 AI 来源。
3. 实现匿名 session、CSRF、注册、JSON 登录与退出；检查登录前后 Session ID 变化及下次请求身份保持。
4. 校验 Cookie 属性、过期、CSRF 缺失/错误和登录/退出后的旧 CSRF 失效；错误无 HTML 重定向。
5. 用两个独立 Cookie 客户端登录同一账户：一个改密后，两个旧会话均不能读写账户；新密码可登录，旧密码失败。
6. 用两个不同账户验证资料更新只能修改当前账户；请求附加外部账户 ID 不能改变目标。
7. 账户存储故障时验证账户 503、PDF 接口保持原功能响应；检查匿名 PDF 请求没有新增会话 Cookie。
8. 检查过滤器只有账户链内的一次注册，避免进入 PDF 或 AI 流式链路。

**执行入口：** `mvn -f backend/pom.xml "-Dtest=AccountApiTest,PdfApiTest,AiApiTest" test`。

**完成标准：** 七个账户接口符合合同；游客 POST、下载及 AI 流式路径无新增账户拦截；旧会话失效可重复验证。

### P06：前端请求封装与账户状态

**涉及文件：** `types/account.ts`、`api/account.ts`、`composables/useAccount.ts`、`account-api.test.ts`、`account.test.ts`。

1. 先测试账户请求使用独立 URL 前缀、正确 JSON 与 CSRF 头；缺少响应体、非 JSON 错误、超时均转换为可显示错误。
2. 实现按需获取 CSRF、同源 Cookie、登录/退出后令牌失效；403 不自动重复写操作。
3. 实现 `unknown`、`guest`、`authenticated`、`unavailable` 状态，以及各操作独立的 pending 与错误信息。
4. 首次恢复不阻塞渲染；同一恢复任务去重，初始请求晚于登录完成时不能覆盖已登录状态。
5. 关闭面板不把已发出的账户操作当作撤销；操作最终结果更新身份但不强制重开面板。
6. 测试 401 降为游客、503 不假报退出、失败资料更新不覆盖昵称、重复点击只发一个请求、密码不持久化。
7. 账户逻辑不引用 PDF/AI 状态重置函数，不修改原请求封装。

**执行入口：** `npm --prefix frontend test -- tests/account-api.test.ts tests/account.test.ts`。

**完成标准：** 账户请求失败仅影响账户模块；并发恢复、关闭和提交具有确定结果。

**提交单元：** `feat: add optional account client and state management`。

### P07：账户弹窗组件

**涉及文件：** `AccountDialog.vue`、`assets/account.css`、`account-dialog.test.ts`。

1. 先测初始关闭、打开登录、切换注册、关闭时清空密码、确认密码不一致和重复提交。
2. 实现四种面板及原生 dialog 生命周期，资料表单只编辑昵称。
3. 接入真实账户状态和错误，注册后返回登录，不自动创建登录态。
4. 实现成功反馈、服务不可用重试、会话失效提示与继续游客操作。
5. 测试修改昵称同步、当前密码错误、改密成功与退出成功的面板行为。
6. 单元测试为 jsdom 提供最小 dialog 方法桩；焦点限制、背景 inert 与 Escape 在真实浏览器验证。

**执行入口：** `npm --prefix frontend test -- tests/account-dialog.test.ts`。

**完成标准：** 表单完整、错误明确；面板关闭后没有密码残留、意外提交或悬挂的焦点状态。

**提交单元：** `feat: add accessible account dialog and forms`。

### P08：原工作台单入口集成

**涉及文件：** `WorkbenchView.vue`、`AppIcon.vue`、账户局部样式与浏览器测试。

1. 增加唯一入口和始终存在的弹窗组件挂载点，不改变工作台顶层条件或 RouterView。
2. 将入口状态连接到 `useAccount`，异步会话恢复失败不显示全页错误。
3. 用已上传样本记录文件名、预览页、选中工具、表单参数、提取结果、下载链接和阅读消息。
4. 执行弹窗开关、注册切换、登录、改名、退出和会话到期，逐项断言上述内容不被重置。
5. 验证账户操作与 PDF 处理中、AI 流式输出同时发生时互不取消，停止与重试仍按原功能执行。
6. 检查新增 CSS 使用账户类名限定，不覆盖现有全局 nav、input、header 规则。

**完成标准：** 业务布局只新增入口；原组件不发生由身份变化引起的卸载。

**提交单元：** `feat: integrate optional login entry without resetting workbench`。

### P09：浏览器闭环与故障回归

**涉及文件：** `tests/e2e/account.spec.ts`、`tests/e2e/workbench.spec.ts`、隔离验收配置。

1. 先运行 `PdfApiTest` 生成本工作目录的 `backend/target/verification/api-sample.pdf`，不复制其他分支的临时结果作为测试输入。
2. 测试随机邮箱注册 → 返回登录 → 登录 → 改名 → 刷新后恢复身份 → 改密 → 旧身份失效 → 新密码登录 → 退出 → 继续游客使用。
3. 独立验证错误邮箱、错误密码、昵称边界、重复邮箱、确认密码错误、限流和 CSRF 失败。
4. 使用请求延迟模拟会话恢复与登录竞态、提交后关闭和错误响应；不使用固定等待掩盖竞态。
5. 模拟账户接口不可用，验证游客上传、提取、下载与匿名 AI 合同仍可用。
6. 检查键盘 Tab/Shift+Tab、Escape、关闭后焦点、密码显隐及移动端点击区域。
7. 保存六种宽度下的默认工作台、登录和账户设置截图；失败截图和报告保留在 `frontend/test-results/`、`frontend/playwright-report/`。
8. 模型无关的身份兼容验证使用后端受控响应；真实模型闭环使用独立执行入口，不把模拟响应记为真实模型结果。

**执行入口：** `npm --prefix frontend run test:account`。该脚本计划使用 `playwright.account.config.ts`，不复用原目录服务。

**完成标准：** 账户闭环、游客回归、状态保留、真实浏览器可访问性和响应式检查通过。

**提交单元：** `test: cover account lifecycle and anonymous workbench compatibility`。

### P10：完整验收与文档同步

1. 执行本计划第 7 节的完整检查命令。
2. 检查依赖差异、安全链范围、敏感信息和新增界面文案，确认未增加登录后才能使用的业务功能。
3. 在 `DEV_STATE.md` 汇总实际执行结果、剩余问题和下一步；不在需求或设计正文中堆积开发过程。
4. 按实际接口与行为同步工程文档。若修改三份 Word 文档，单独执行相应文档渲染与版面检查。
5. 提交前核对全部 diff；提交范围仅限本分支的模块改动、必要测试与对应文档。

**完成标准：** 正式验收矩阵全部满足；任何未执行环境项保留客观状态，不合并为“全部通过”。

**提交单元：** `docs: document optional accounts and verified guest compatibility`。

## 6. 验收矩阵

| 编号 | 场景                                 | 必须满足的结果                                  | 主要验证层    |
| ---- | ------------------------------------ | ----------------------------------------------- | ------------- |
| G01  | 无 Cookie 打开首页及七个工具地址     | 原工作台直接可用，无登录跳转与自动弹窗          | 浏览器        |
| G02  | 匿名执行原 PDF POST、下载和 AI 请求  | 业务合同不新增账户或 CSRF 前提                  | 后端 + 浏览器 |
| G03  | 账户数据目录不可写、会话查询失败     | 账户明确失败，游客功能继续可用                  | 后端 + 浏览器 |
| U01  | 原工作台与六种宽度                   | 只增加一个账户入口，无新增横向溢出              | 截图 + 浏览器 |
| U02  | 关闭、Escape、继续游客               | 弹窗可退出，密码清空，焦点返回                  | 组件 + 浏览器 |
| A01  | 注册字段边界与同邮箱并发注册         | 无效请求拒绝，只产生一条有效账户                | 单元 + 后端   |
| A02  | 正确与错误密码登录                   | 身份保持、Session ID 更新、错误不泄露账户存在性 | 后端          |
| A03  | 修改昵称                             | 只修改当前账户，顶栏与资料同步，文本安全渲染    | 单元 + 浏览器 |
| A04  | 修改密码与并发修改                   | 验证旧密码，只接受有效版本，所有旧会话失效      | 后端          |
| A05  | 退出、到期、旧 Cookie                | 不能继续访问账户操作，不影响游客功能            | 后端 + 浏览器 |
| S01  | 缺少/错误 CSRF、跨账户 ID、尝试过频  | 分别拒绝，使用稳定 JSON 错误                    | 后端          |
| S02  | 密码、散列、会话与 Cookie            | 无响应或日志泄露，Cookie 属性符合配置           | 后端 + 浏览器 |
| R01  | 身份变化与账户弹窗生命周期           | 当前 PDF、参数、结果、下载及阅读状态保持        | 浏览器        |
| R02  | PDF 处理中与 AI 流式输出期间操作账户 | 原任务不被账户模块取消，原停止重试有效          | 后端 + 浏览器 |
| R03  | 旧会话请求晚到、提交后关闭           | 不覆盖新状态、不重新打开弹窗、不假报撤销        | 单元 + 浏览器 |

## 7. 执行命令与环境边界

在新分支根目录使用 PowerShell，优先执行仓库脚本。下列新增测试文件与脚本在对应任务完成后使用。

| 范围               | 命令                                     | 结果要求                                            |
| ------------------ | ---------------------------------------- | --------------------------------------------------- |
| 前端单元测试       | `npm --prefix frontend test`             | 现有与新增用例全部通过                              |
| 类型检查与生产构建 | `npm --prefix frontend run build`        | 类型检查与构建成功                                  |
| 静态检查           | `npm --prefix frontend run lint`         | 无新增错误                                          |
| 后端全部测试       | `mvn -f backend/pom.xml test`            | 区分并处理既有失败与新增失败                        |
| 后端打包           | `mvn -f backend/pom.xml package`         | 测试与打包成功，不跳过测试                          |
| 隔离浏览器验收     | `npm --prefix frontend run test:account` | 新配置启动本分支 5182/8082 服务，闭环与游客回归通过 |
| 既有真实模型回归   | `npm --prefix frontend run test:ai`      | 仅在原真实模型环境可用、且确认服务来自本分支时执行  |
| Git 格式检查       | `git diff --check`                       | 无空白错误                                          |

- 后端测试使用临时账户目录，不能读取或清理个人实际账户目录。
- 8082 后端的账户与 AI 存储都指向本分支测试目录；5182 前端的 `/api`、`/v3`、`/swagger-ui` 全部代理到 8082。
- 既有 `playwright.config.ts` 使用固定 5173/8080 且允许复用服务；账户正式验收使用新增隔离配置，避免命中其他分支进程。
- 限流与会话到期测试使用可控时钟或测试会话配置，不让浏览器实际等待 15/30 分钟。
- 本机数据保留规则与游客功能不随账户模块扩展；账户数据库失败时保留原 PDF 与 AI 数据。

## 8. 参考与版本核验

安全链限定范围、JSON 登录后的会话保存、CSRF 生命周期与密码编码器的实现按项目实际解析版本核验，不套用其他 Spring Security 主版本示例。

- [Spring Security 6.5 请求匹配与局部安全链](https://docs.spring.io/spring-security/reference/6.5/servlet/configuration/java.html)
- [Spring Security 6.5 会话持久化与认证策略](https://docs.spring.io/spring-security/reference/6.5/servlet/authentication/session-management.html)
- [Spring Security 6.5 CSRF 与前端集成](https://docs.spring.io/spring-security/reference/6.5/servlet/exploits/csrf.html)
- [Spring Security 6.5 密码编码器](https://docs.spring.io/spring-security/reference/6.5/features/authentication/password-storage.html)
