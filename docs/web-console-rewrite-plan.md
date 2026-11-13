# StarRocks Web Console 重写计划（branch-4.1.4）

> 用 shadcn-admin（https://github.com/satnaing/shadcn-admin.git）作为 UI 基底，将现有 FE/BE HTML 控制台全部迁移为 SPA。本文是执行计划，不是代码。

## 0. 基线

- 工作分支：`feat/web-console`（基于 `branch-4.1.4`，commit 0e030a1130）。
- 旧 HTML 页面（`/system`、`/backend`、`/log`、`/query`、`/session`、`/variable`、`/ha`、`/proc_profile`、`/query_profile`、`/`）本期**全部重写**为 SPA，不使用 iframe 过渡；旧路由保留一个版本作回退。
- SPA 挂 `/ui/`；浏览器访问 `/` 302 到 `/ui/`，带 `Authorization` 的请求仍返回旧首页一个版本。
- 全面登录：新 UI 必须登录才能访问（用户要求）。

## 1. 范围

**做：**

- FE 10 个页面：overview、system、backends、logs、queries、sessions、variables、ha、proc profiles、query profile。
- BE 6 个页面：`/`、`/varz`、`/memz`、`/mem_tracker`、`/logs`、`/proc_profile`，统一从 FE 代理。
- 登录、登出、会话守卫、401/403、权限校验。
- 所有现有行为：leader 转发、query profile 访问检查（4.1.4 为 OPERATE）、log verbose 增删、CSV 下载、profile 链接、硬件信息开关。

**不做：**

- 不加 query kill、SQL 控制台、告警、审计等新功能。
- 不改现有 `/api/*` 的语义，只新增 `/api/web/v1/*`。
- 不引入 i18n（现有 UI 只有英文）、不做 RTL、不保留 Google Fonts。

## 2. 架构决策

| 决策 | 方案 | 理由 |
|---|---|---|
| SPA 位置 | `/ui/`，Vite `base: '/ui/'`，TanStack Router `basepath: '/ui'` | 避开旧 HTML 路由冲突；浏览器访问 `/` 302 到 `/ui/`，下个版本移除旧页面 |
| 静态服务 | 新增 `UiResourceAction`，在 `HttpServerHandler.getAction` 对 `/ui` 前缀直接分派 | PathTrie 的 `*` 只按段匹配，多级资源路径不能只靠 `/ui/*` |
| 鉴权 | 新 `WebApiAction` 基类：cookie 优先，Basic 兼容 | 复用 `HttpAuthManager` 会话，curl/测试仍可用 Basic |
| 登录 | `POST /api/web/v1/login`，JSON body，成功后种 `STARROCKS_SESSION_ID` | 不存密码到 localStorage；支持 LDAP（复用 `AuthenticationHandler.authenticate`） |
| 登出 | 新增 `HttpAuthManager.removeSessionValue`，清 cookie + 清 react-query 缓存 | 现在没有服务端登出，必须补 |
| CSRF | 依赖现有 `ServerCookieEncoder.LAX`（SameSite=Lax）+ POST 必须 `application/json` + 校验 `Origin` | cookie 已经是 Lax，无需再引入 token |
| BE 统一入口 | FE 代理，显式节点白名单，解析 BE `?raw` 页面为 JSON | 避免 CORS/SSRF；BE 是 C++，本期不在 macOS 上改 BE |
| 构建 | 提交 `fe/ui/dist`；`build.sh` 拷贝到 `output/fe/webroot/ui`；不把 Node 加进发布链 | 发布镜像无 Node；与现有 `webroot/` 提交方式一致 |

## 3. 登录设计（重点）

### 3.1 流程

```
/ui/sign-in (公开)
   └─ POST /api/web/v1/login {username,password}
        ├─ 401 用户名/密码错误（统一文案，不区分用户是否存在）
        ├─ 403 认证成功但没有 NODE 权限
        └─ 200 + Set-Cookie: STARROCKS_SESSION_ID=...; HttpOnly; Path=/; SameSite=Lax; Max-Age=86400
             └─ GET /api/web/v1/session → {user, canAdmin} → 跳转 redirect 或 /ui/
```

- 登录成功后 `queryClient.clear()`，防止上一个会话的缓存串到新用户。
- 路由守卫：`_authenticated` 的 `beforeLoad` 调 `/api/web/v1/session`；401 时 `redirect({ to: '/sign-in', search: { redirect: location.href } })`。
- 任意 API 返回 401：清会话状态、跳登录页；已在 `/ui/sign-in` 时不重复跳转。
- 登出：`POST /api/web/v1/logout` → 服务端删 session、cookie `Max-Age=0`、清缓存、跳登录。

### 3.2 权限

- 登录时执行与页面相同的检查：`Authorizer.checkSystemAction(context, PrivilegeType.NODE)`，失败再 `checkUserOwnsAdminRole`（照抄 `WebBaseAction.java:166-269` 的语义）。
- `canAdmin` 用于前端隐藏 log 配置的增删按钮；服务端仍要再校验，不信任前端。
- `/query-profile` 的新 API `WebQueryAction.getProfile` 调 `requireOperate`（OPERATE），**不能**因为走了新 API 就放宽；4.1.4 旧 `/api/profile` 也只有 OPERATE，没有 owner 过滤。
- `/system` 默认转发 leader（`LeaderOpExecutor`，`SystemAction.java:105`），不能只在 `forward=true` 时转发。

### 3.3 会话细节

- 服务端 `HttpAuthManager`：2 小时 idle、最多 100 个会话。V1 保持现状，但在文档里写明；超过 100 个并发管理员会被淘汰，需要单独评估是否调大。
- Cookie 1 天（`STARROCKS_SESSION_EXPIRED_TIME = 3600*24`，`WebBaseAction.java:70`），但服务端 2 小时 idle 后失效；前端必须处理 401，不能假设 cookie 还在就有效。
- `Secure` 仅在 `Config.enable_https` 或请求本身是 HTTPS 时设置；不要无条件加，否则 HTTP 部署登录失败。
- 顺手修掉现有泄漏：`BaseAction.getAuthorizationInfo` 的 debug 日志（`BaseAction.java:336`）和 `ActionAuthorizationInfo.toString()`（`BaseAction.java:285-291`）明文包含 password。新增 `WebAuthActionTest` 断言日志里不出现密码。
- 4.1.4 没有 `RejectedCredentialCache`；本期不新增登录限流，沿用 `AuthenticationHandler` 的认证错误处理。若需要防爆破，单列配置项再评估。

### 3.4 登录页

- 居中 Card + shadcn `Form`/`Input`/`Button`；错误用 toast/表单提示；Logo + “StarRocks Web Console”。
- 字段：username、password；`autocomplete="username"` / `"current-password"`。
- 状态：提交中禁用按钮；401/403 显示对应错误；网络错误单独提示。
- 已登录访问 `/ui/sign-in` 直接跳 `/ui/`。

## 4. API 契约

新增 `fe/fe-core/src/main/java/com/starrocks/http/action/web/`，统一 `WebApiAction` 基类（JSON 错误、会话、ConnectContext）。

| 方法 | 路径 | 权限 | 数据来源 |
|---|---|---|---|
| POST | `/api/web/v1/login` | 公开 | `AuthenticationHandler` |
| POST | `/api/web/v1/logout` | 会话 | `HttpAuthManager` |
| GET | `/api/web/v1/session` | 会话 | 当前 `SessionValue` |
| GET | `/api/web/v1/overview` | NODE | 版本/fe/backends/queries/sessions + `hardware`（受 `http_web_page_display_hardware` 控制，60s 缓存） |
| GET | `/api/web/v1/system?path=` | NODE | `SystemAction` 的 Proc 逻辑，补 `isDir`、默认转发 |
| GET | `/api/web/v1/backends` | NODE | `BackendAction` 的列，保持现状 |
| GET | `/api/web/v1/queries` | NODE | `ProfileManager`，SQL 按 `ui_queries_sql_statement_max_length` 截断 |
| GET | `/api/web/v1/sessions` | NODE | `ExecuteEnv.listConnection` |
| GET | `/api/web/v1/variables` | NODE | `Config.dump()` + `VariableMgr.dump(GLOBAL)` |
| GET | `/api/web/v1/ha` | NODE | `HaAction` |
| GET | `/api/web/v1/logs` | NODE | 日志文件列表 + level/verbose/audit |
| GET | `/api/web/v1/logs/{name}` | NODE | 1MB tail，`*.log` 白名单 |
| GET | `/api/web/v1/log-config` | NODE | `Log4jConfig` |
| POST | `/api/web/v1/log-config/verbose` | admin | `{add:[],remove:[]}`，替代现有 GET 改配置 |
| GET | `/api/web/v1/proc-profiles?node=` | NODE | FE 文件系统 / BE `/api/proc_profile/list` |
| GET | `/api/web/v1/proc-profiles/file?node=&filename=` | NODE | FE 解 tar.gz / BE 代理 `/proc_profile/file` |
| GET | `/api/web/v1/queries/{query_id}/profile?type=profile\|sql\|analyze` | OPERATE | `ProfileManager` + `SqlFormatter`/`ExplainAnalyzer` |
| GET | `/api/web/v1/be/{varz\|memz\|mem-tracker\|logs}?node=&upper_level=` | NODE | 代理 BE `?raw`，节点白名单，60s 超时 |

实现上优先复用旧页面的数据获取逻辑；v1 为降低改动面，`Web*Action` 内按原逻辑重新取数，等 BE/旧页面下线时再抽公共 helper。旧页面测试仍覆盖原路径。

## 5. 前端工程

### 5.1 脚手架

- 新建 `fe/ui/`：Vite + React + TS + Tailwind + shadcn/ui + TanStack Router/Table/Query。
- 从 shadcn-admin 只复制约 5.1k 行：`components/ui`、`components/layout`、`components/data-table`、`context/theme-provider`、`hooks/use-table-url-state.ts`、主题 CSS。
- 删除：`features/{dashboard,apps,chats,tasks,users,settings,auth}`、`routes/clerk`、`stores/auth-store`、Clerk、faker、react-day-picker、input-otp、recharts（现有 UI 没有图表）、Google Fonts、RTL/font 切换、demo 图片。
- 字体改为 system font stack 或自托管 Inter，禁止 CDN。
- 路由：`/sign-in` 公开；其余在 `_authenticated` 下。

### 5.2 页面

| 页面 | 组件 |
|---|---|
| overview | `Card` + 描述列表；保留硬件信息开关 |
| system | 面包屑 + Table；仅 `isDir` 的首列可点；URL 单元格校验后渲染 |
| backends | Table，默认 50 行，客户端过滤/分页，`filter`/`page` 入 URL |
| logs | `<pre>` tail + 刷新/下载 + 管理员 verbose 编辑器 |
| queries | Table + profile 链接 + Download CSV |
| sessions | Table |
| variables | Tabs（FE config / global variables）+ 搜索 |
| ha | Cards/描述列表 |
| proc profiles | Tabs（FE/BE）+ Table + View 链接 |
| query profile | 折叠树 + Profile/Formatted SQL/Analyze + Expand/Collapse/Copy/Download |

Profile 树重写：按行首缩进算 depth，渲染扁平列表；折叠时隐藏 depth 更大的后续行直到同/更低 depth。不要用 `innerHTML`，避免现有 XSS 面。CSV 下载用 `Blob` + `URL.createObjectURL`，列数与页面表头一致（running/finished 分开），不再硬编码 9。

### 5.3 数据层

- `src/lib/api.ts`：`fetch` + `credentials: 'same-origin'`，统一 `ApiError {status, code, message}`；401 交给全局处理。不引入 axios（模板里本来也没用）。
- TanStack Query：手动刷新按钮 + 可选自动刷新（默认关，10s，`refetchIntervalInBackground: false`）。现有 UI 是手动刷新，不要默认开轮询给 FE 加压。
- backends 用 `useSearch`/`useNavigate` 把 `filter`/`page` 放 URL；通用 `use-table-url-state.ts` 保留给后续表格。

## 6. 静态文件与构建

### 6.1 `UiResourceAction`

- 公开（`needPassword()=false`），只服务 `/ui` 和 `/ui/**`。
- 路径规范化：拒绝 `..`、绝对路径、null byte；`canonicalPath.startsWith(root)` 再读。
- `/ui`、`/ui/`、无扩展名的深链接 → `index.html`；未知的带扩展名资源 → 404。
- MIME 显式映射 `.js/.css/.svg/.woff2/.png/.ico`；加 `X-Content-Type-Options: nosniff`。
- 缓存：带 hash 的 `assets/*`（hash 至少 6 位，允许大写/连字符）用 `public, max-age=31536000, immutable`；`index.html` 用 `no-cache`。
- 可选：构建时生成 `.gz`，按 `Accept-Encoding` 返回；v1 先不做，实测 WAN 体验再定。

### 6.2 构建/发布

- `fe/ui/package.json` 固定 Node `22.12.x`（Vite 8 要求 20.19+/22.12+），提交 `pnpm-lock.yaml`。
- 提交 `fe/ui/dist`（和现有 `webroot/` 提交静态资源一致），`build.sh` 增加：
  ```bash
  install -d ${STARROCKS_OUTPUT}/fe/webroot/ui
  cp -r -p ${STARROCKS_HOME}/fe/ui/dist/* ${STARROCKS_OUTPUT}/fe/webroot/ui/
  ```
- 不把 Node 写进 `dev-env` 镜像；本地/CI 用固定 `node:22.12` 容器构建。
- 本地修改 `fe/ui/src` 后必须执行 `pnpm exec vite build`（刷新 `routeTree.gen.ts`）再 `pnpm build`，并提交 `fe/ui/dist`。
- License：`pnpm licenses list --prod` 作为门禁，禁止 GPL/AGPL；保留 shadcn-admin 的 MIT 版权声明；`fe/ui/NOTICE` + `fe/ui/LICENSE-shadcn-admin` 已补。
- 打包体积门禁：初始 JS gzip < 400KB；路由级自动拆包。

## 7. 测试

**Java（`fe/fe-core/src/test/java/com/starrocks/http/action/web/`）：**

- 登录成功/密码错/无 NODE/会话读取/登出失效。
- cookie 属性：HttpOnly、Path、SameSite=Lax、HTTPS 时 Secure。
- 日志中不出现密码（Log4j appender 捕获）。
- system JSON：`isDir`、path 规范化、follower 转发。
- query 列表：NODE 可读；4.1.4 无 owner 过滤。
- query profile：OPERATE/403（4.1.4 无 owner 过滤）。
- log：1MB 截断、类型白名单、verbose 增删仅 admin。
- `/ui`：深链接 fallback、hash 资源缓存头、路径穿越 400/404。
- BE 代理：未知节点拒绝、60s 超时。
- 旧测试 `SystemActionTest`、`HttpUtilsTest`、`IndexActionTest`、`LogActionTest`、`HttpAuthManagerTest` 必须保持绿。

**前端：**

- vitest：profile 树解析、API 401 处理、登录表单错误分支。
- Playwright：登录 → overview → backends → queries → logout，一条 smoke；CI 跑不了就保留手动清单。

## 8. 里程碑

| 阶段 | 内容 | 产出 | 估算 |
|---|---|---|---|
| M0 | 切分支、`fe/ui` 脚手架、删 demo、CI 构建 | 可 `pnpm dev` 的空壳 | 0.5–1 天 |
| M1 | 登录/登出/会话、`/ui` 静态服务、路由守卫、用户菜单 | 能登录进空壳 | 3–4 天 |
| M2 | overview/backends/queries/sessions + API + 测试 | MVP 可日常使用 | 4–5 天 |
| M3 | system/variables/HA/logs + API + 测试 | 覆盖 8 个导航页 | 4–5 天 |
| M4 | query profile、proc profiles（FE/BE） | 10 个 FE 页面齐 | 3–4 天 |
| M5 | BE 4 页 JSON + FE 代理 | 统一控制台 | 2–3 天 |
| M6 | 旧链接、文档、构建/发版、E2E、安全 review | 可发布 | 2–3 天 |

合计 **18–25 人天**，加 20% buffer ≈ **5–6 周**（1 人）。M2 结束即可内部试用。

## 9. 风险与决策

1. `HttpAuthManager` 最多 100 个会话、2 小时 idle：大团队可能不够。v1 先保持，是否调大单独决定。
2. BE 页面没有现成 JSON；已采用 FE 代理 BE `?raw` 并解析为 JSON 的适配层，不改 BE C++。若后续 BE 提供原生 JSON，直接替换适配层。
3. 旧 `/system?path=` 有 3 处官方文档引用；本期已由 legacy 302 接管，旧 HTML 仍保留一个版本，官方文档引用需后续更新。
4. 登录后 cookie 1 天、服务端 2 小时 idle，会出现“cookie 还在但 API 401”；前端必须统一处理，不要各自判断。
5. 如果 FE 部署在 TLS 终止代理后，`Secure` cookie 的判定要按实际请求协议，不要只看 `enable_https`。

**已确认的默认值（实施中采用）：**

- 新 UI 挂 `/ui/`；浏览器访问 `/` 302 到 `/ui/`，带 `Authorization` 的请求保留旧 IndexAction 一个版本。
- BE 6 页纳入本期 M5，采用 FE 解析 BE `?raw` 的适配层。
- 会话上限保持 100，不在本期改。


## 10. 实施状态（2026-11-13）

- M0 脚手架、M1 登录/会话/`/ui` 静态服务、M2 集群/查询/会话、M3 系统/变量/HA/日志、M4 proc profile、M5 BE 诊断、M6 构建/文档均已完成。
- 关键提交：`4e6cdbd082`（脚手架）、`a65a4ffee2`（登录）、`67ae60af4c`（M2）、`5024776ca4`（M3）、`fe2176302b`（M4）、`4fec2bb14e`（M5）、`17206ae628`/`3a6b11481d`（构建与根跳转）、`cf50fd0487`（登录/命令面板测试）、`fdc267e80d`（review 对齐）、`115e2dcff1`（所有 POST 校验 Origin）。
- 测试：`com.starrocks.http.action.web.*` 37 个 Java 用例全绿；`IndexActionTest`/`SystemActionTest`/`WebLegacyRedirectTest`/`HttpAuthManagerTest`/`LogActionTest`/`HttpUtilsTest` 13 个旧路径用例全绿；`BasicActionTest` 3 个用例全绿。前端 10 个文件 68 个用例全绿；`checkstyleMain/checkstyleTest` 通过。
- 旧 HTML 路由（`/system`、`/backend`、`/query`、`/session`、`/variable`、`/ha`、`/log`、`/proc_profile`、`/query_profile`）对浏览器 `Accept: text/html` 且无 `Authorization` 的请求 302 到对应的 `/ui/*`；带 Basic 的请求仍返回旧页面，避免破坏脚本和存量测试。
- 已知限制：BE 页面通过 FE 解析其 `?raw` HTML 提供；BE 自身的 Logs handler 仍输出 “Couldn't open INFO log file”，因此 BE Console 的 Logs 标签只显示该提示。BE 端 JSON 接口留待后续版本。
- 登录限流未单独实现（4.1.4 无 `RejectedCredentialCache`）；query profile 的 OPERATE 校验已由 `WebQueryAction` 执行。

## 11. 逐点 review（2026-11-13，第三轮）

| 文档点 | 实现/证据 | 结论 |
|---|---|---|
| 10 个 FE 页面 | `fe/ui/src/routes/_authenticated/`：overview/system/backends/logs/queries/sessions/variables/ha/proc-profiles/query-profile | 对齐 |
| 6 个 BE 页面 | `features/be-console` 覆盖 `/`、`/varz`、`/memz`、`/mem_tracker`、`/logs`、`/proc_profile` 语义；`WebBeAction` 代理 `?raw` | 对齐（适配层） |
| 不做 query kill | `WebQueryAction` 无 cancel 路由；`WebSessionAction` 无 kill 路由；前端无按钮 | 对齐 |
| 不改 `/api/*` | 只新增 `/api/web/v1/*`，旧 action 未改语义 | 对齐 |
| SPA `/ui` + `/` 302 | `UiResourceAction`、`HttpServerHandler:219-243`、`WebLegacyRedirectTest` | 对齐 |
| 登录/登出/会话 | `WebAuthAction` 三路由；`HttpAuthManager.removeSessionValue`；`WebAuthActionTest` | 对齐 |
| Cookie 属性 | HttpOnly/Path/SameSite=Lax/Max-Age=86400；`Secure` 由 `enable_https` 或 `X-Forwarded-Proto` 决定；测试覆盖 HTTP 无 Secure、代理 HTTPS 有 Secure | 对齐 |
| 登录后清缓存 | `user-auth-form.tsx` / `nav-user.tsx` 调 `queryClient.clear()` | 对齐 |
| 401/403、已登录跳转 | `_authenticated` 只对 401 跳；`sign-in` `beforeLoad` 已登录跳 `/`；错误文案区分 401/403/网络 | 对齐 |
| 密码不进日志 | `BaseAction.ActionAuthorizationInfo.toString()` 去 password、`getAuthorizationInfo` 去 debug 日志；`BasicActionTest` 断言 | 对齐 |
| NODE + admin 登录 | `WebApiAction.hasNodePrivilege` 先 `checkSystemAction(NODE)`，失败再 `checkUserOwnsAdminRole` | 对齐 |
| query profile OPERATE | `WebQueryAction.getProfile` 调 `requireOperate`；`type=profile/sql/analyze` | 对齐 |
| log verbose admin | `POST /log-config/verbose` 调 `requireAdmin`；前端按 `canAdmin` 显示 | 对齐 |
| leader 转发 | `WebSystemAction` leader 本地 fetch、follower `LeaderOpExecutor` | 对齐（follower 分支未做独立单测） |
| log 1MB/白名单 | `WebLogAction` 只读最后 1MB；`*.log` + verbose 名白名单 | 对齐 |
| CSV | `lib/csv.ts` + queries 页 Download CSV | 对齐 |
| 硬件开关 | overview `hardware` 受 `Config.http_web_page_display_hardware`，60s 缓存 | 对齐 |
| 默认关轮询 | overview/backends/queries/sessions 用 `AutoRefresh`，默认 false、可选 10s | 对齐 |
| backends 50 行/过滤 | 50 行分页、过滤、`filter`/`page` 入 URL | 对齐（轻量 Table，未引入通用 DataTable） |
| system 面包屑/目录链接 | `features/system` 面包屑 + `isDir` 首列 + `isUrl` 校验 | 对齐 |
| variables Tabs/搜索 | `features/variables` FE config / global variables + 搜索 | 对齐 |
| HA Cards | `features/ha` 状态卡 + 节点/数据库表 | 对齐 |
| logs 下载/verbose | 下载 `/logs/{name}`；verbose 仅 admin | 对齐 |
| proc profiles Tabs/View | FE/BE Tabs + 节点选择 + Table + View | 对齐 |
| query profile 树/Tabs/Download | `profile-tree-utils.ts` 折叠树；Profile/Formatted SQL/Analyze；Copy/Download | 对齐 |
| BE 60s 超时 | `WebBeAction` socket timeout 60s | 对齐（无自动化超时测试） |
| Node 22.12 | `package.json` engines + `.nvmrc` | 对齐 |
| dist 提交/build.sh | `fe/ui/dist` 已提交；`build.sh:620-621` 拷贝 | 对齐 |
| license 门禁 | `pnpm licenses list --prod` 结果记录在 §12 | 对齐 |
| bundle <400KB gzip | 主 chunk 65.73KB gzip；总 dist 约 1MB | 对齐 |
| Java 测试 | 37 个 web action + 13 个旧路径 + 3 个 BasicAction 用例全绿 | 对齐 |
| 前端测试 | 10 文件 68 用例全绿 | 对齐 |
| Playwright smoke | 未实现；CI 无浏览器，保留手动清单 | 已知缺口，见 §12 |

**仍需说明的偏差（不阻塞）**

1. BE 页面没有原生 JSON，`WebBeAction` 解析 `?raw` HTML；BE Logs handler 本身未实现，页面只能显示其错误提示。
2. 登录失败限流未实现（4.1.4 无 `RejectedCredentialCache`）；如要防爆破需单列。
3. 通用 `use-table-url-state.ts` 未用于所有表；backends 已手动同步 URL，queries/sessions 无筛选参数。
4. follower 转发、BE 60s 超时只有代码路径，没有独立集成测试。

## 12. 收尾证据与已知缺口（2026-11-13）

- Java：`com.starrocks.http.action.web.*` 37 例、旧路径 13 例、`BasicActionTest` 3 例全绿；`checkstyleMain`/`checkstyleTest` 通过。
- 前端：`eslint src` 0 error/0 warning；`tsc -b` 通过；`vitest run --browser.headless` 10 文件 68 例全绿；`vite build` 通过。
- 包体：`fe/ui/dist` 约 1.0MB；主 chunk 206.33KB，gzip 65.73KB，低于 400KB 门禁；路由级 chunk 已拆分。
- 依赖 license：116 MIT、4 ISC、2 MPL-2.0、2 Apache-2.0、1 Unlicense、1 BSD-3-Clause、1 0BSD；无 GPL/AGPL/LGPL。MPL-2.0 为 lightningcss（构建期依赖），保留其声明；shadcn-admin 的 MIT 版权声明保留在 `fe/ui/README.md`/`fe/ui/NOTICE` 与 `fe/ui/LICENSE-shadcn-admin`。
- 已知缺口：
  - BE 页面通过 FE 解析 `?raw` HTML 提供；BE 自身 `/logs` handler 仍输出 “Couldn't open INFO log file”，BE Console 的 Logs 标签只显示该提示。BE 端 JSON 接口留待后续版本。
  - 登录失败限流未实现（4.1.4 无 `RejectedCredentialCache`）；如要防爆破需单列。
  - follower 转发、BE 60s 超时只有代码路径，没有独立集成测试；未做真实多 BE/TLS 代理 E2E。
  - 通用 `use-table-url-state.ts` 未用于所有表；backends 已手动同步 URL，queries/sessions 没有筛选参数。
- 本轮 review 提交：`fdc267e80d`、`115e2dcff1`；`git status` 干净。

## 13. 端到端验证（2026-11-14）

在已有 1FE+1BE docker 集群（`sr-cluster`，镜像 `starrocks/fe-ubuntu:4.1.7` / `be-ubuntu:4.1.7`，arm64）上，用新构建的 `fe-core-main.jar` + `fe/ui/dist` 替换 FE 容器（复用 meta，BE 无需重注册），Playwright 逐页验证：

| 检查项 | 结果 |
| --- | --- |
| 登录/登出 | `POST /api/web/v1/login` 返回 cookie；登录页、登出、`/`/`/system` 等旧路由 302 → `/ui/*` |
| Overview | FE 角色/BE 数/查询/会话/硬件卡/版本正常 |
| System | proc 树目录列表，`/dbs` 钻取返回库名/表数 |
| Backends | `starrocks-be` 行数据齐全（alive/state/cpu/mem/disk/版本） |
| HA | Role/Journal/CanRead/Checkpoint/Frontends/Electable；Databases 显示 journal db id（与旧页一致，见下） |
| Sessions/Queries | 会话与查询列表渲染；Query Profile 页可搜索并显示 profile/sql/analyze |
| Variables | Config/Variables Tabs + 搜索 |
| Logs | 文件列表 + 1MB tail + 下载；verbose 名增删 |
| Proc Profiles | FE/BE Tabs + 文件列表 + View 新窗口 |
| BE Console | Configs/Memory/Mem Tracker/Logs 全通（下方 3 个修复） |
| 登录后路由 | `/ui/sign-in` 深链、刷新、旧路由 302 正常；console 仅预期 401/abort，无 JS 异常 |

E2E 中修复并回归的 bug：

1. `WebBeAction` 裸 `?raw` 被 BE `evhttp_parse_query_str` 拒绝（返回 “Empty reply”），改 `?raw=1`。
2. UI 路由 `/mem-tracker` 直接代理到 BE 404，BE 处理路径是 `/mem_tracker`（snake_case），加 `page.replace("-", "_")`。
3. BE `mem_tracker` 的 header `<tr>` 未在 `<tbody>` 前闭合，`parseTable` 把表头与首行合并；改为按 `<th>`/`<td>` 分别提取。
4. `UiResourceAction` 用 `starrocks.home` 属性（未设置）→ CWD 相对路径 404；改为优先 `STARROCKS_HOME`，再 `starrocks.home`，再 `.`。
5. `WebHaAction` `databases` 由 `journal.getDatabaseNames()`（db id）映射为 `LocalMetastore.getDb(id).getFullName()`，未命中保留 id（BDB journal 内部 db id 无业务库名）。

验证提交：E2E 修复 commit 见下（`UiResourceAction`、`WebBeAction`、`WebHaAction`、`types.ts`）。
- Java `:fe-core:compileJava`/`checkstyleMain`/`checkstyleTest` 与 web/legacy action 测试全绿；前端 `tsc`/`eslint`/`vitest` 68 例通过。
