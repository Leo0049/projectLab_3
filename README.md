# BizMCP — 企業系統 AI 代理層

把既有的營運系統（飲料團購平台）包裝成一個**帶身分授權、資料遮罩與稽核軌跡的 MCP Server**，
讓 Claude Desktop / MCP Inspector 這類 LLM 客戶端能用自然語言安全地查詢與操作企業資料。

> **核心主張：MCP Server 的價值不在「能不能接」，而在「敢不敢上線」。**
> 把 API 包成工具三天就能做完；難的是 LLM 是一個不可信的呼叫方——它會被話術影響、
> 會傳錯參數、會被 prompt injection 誘導。本專案的設計前提是**不信任呼叫方**，
> 所有安全決策都在伺服器端做，不依賴 prompt 約束。

實作依據 `BizMCP 專案規格書 v1.2`。與規格書不同之處都列在 [§ 與規格書的差異](#與規格書的差異)，
包含兩個 spike 推翻原設計的地方。

---

## 目錄

- [30 秒版本](#30-秒版本)
- [快速開始](#快速開始)
- [架構](#架構)
- [一次呼叫的完整資料流](#一次呼叫的完整資料流)
- [MCP 工具清單](#mcp-工具清單)
- [治理機制](#治理機制)
- [兩個 spike 的結論](#兩個-spike-的結論)
- [測試](#測試)
- [與規格書的差異](#與規格書的差異)
- [已知限制](#已知限制)

---

## 30 秒版本

LLM 呼叫工具時沿用使用者的 OAuth 身分，只能查到權限內的資料；個資欄位依角色遮罩；
任何寫入操作都會產生待審批紀錄，人按下核准才真的執行，全程有稽核。

測試刻意拆成兩層：**權限與遮罩是確定性測試，要求 100% 通過並擋 CI**；
**LLM 選對工具的正確率是浮動指標，另外量、不擋 build**。
安全性不能用機率保證——這是拆分的唯一理由。

---

## 快速開始

需要 Docker 與 Docker Compose。

```bash
docker compose up --build
```

首次啟動會跑 Flyway：建立 schema、參考資料，以及 **50,000 筆種子訂單**，會多花一點時間。
啟動完成後：

```bash
npx @modelcontextprotocol/inspector http://localhost:8080/mcp
```

只跑測試（不需要 Docker，見 [測試](#測試)）：

```bash
mvn verify
```

### 本機測試身分

正式路徑是 OAuth 2.0。但 demo 要在三分鐘內切換多個角色，重跑授權流程會卡住影片，
因此另外開了一條 **API key filter chain**，只供本機使用
（`BIZMCP_API_KEY_ENABLED=false` 可關閉）。

送出 header `X-API-Key: <key>`：

| API key | 帳號 | 角色 | 租戶 | 大致權限 |
|---|---|---|---|---|
| `demo-store-manager-key` | alice | `STORE_MANAGER` | 7 | 聚合查詢、庫存、發起調整 |
| `demo-cs-agent-key` | hana | `CS_AGENT` | 7 | 訂單明細（**地址遮罩**） |
| `demo-cs-lead-key` | bob | `CS_LEAD` | 7 | 訂單明細（**地址不遮罩**） |
| `demo-admin-key` | carol | `ADMIN` | 7 | 全部 |
| `demo-approver-key` | erin | `APPROVER` | 7 | 審批頁 + 查審批狀態 |
| `demo-analyst-key` | frank | `ANALYST` | 7 | 只有聚合查詢 |
| `demo-auditor-key` | grace | `AUDITOR` | 7 | **沒有任何工具權限**（示範預設拒絕） |
| `demo-tenant9-key` | dave | `STORE_MANAGER` | **9** | 另一個租戶，用來看隔離 |

審批頁在 <http://localhost:8080/approvals>，用 `erin` / `approver-pw` 或 `carol` / `admin-pw` 登入。

> ⚠️ 種子密碼與 API key 都是公開的 demo 值，**只適用於本機**。

---

## 架構

```
┌─────────────────────────────────────────────────────────────┐
│  MCP Client (Claude Desktop / MCP Inspector)                │
└───────────────┬───────────────────────────┬─────────────────┘
                │ OAuth 2.0 (DCR)           │ Streamable HTTP
                │                           │ Bearer <access_token>
                ▼                           ▼
┌──────────────────────────┐  ┌──────────────────────────────────┐
│ Spring Authorization     │  │        BizMCP Server             │
│ Server                   │  │                                  │
│ · Dynamic Client Reg.    │  │ ┌──────────────────────────────┐ │
│ · 核發 JWT               │◄─┼─┤ ① SecurityFilterChain        │ │
│ · claims:                │  │ │   驗簽 → SecurityContext      │ │
│   role / tenant_id       │  │ │   （看得到 token，看不到工具） │ │
└──────────────────────────┘  │ └──────────────┬───────────────┘ │
                              │                ▼                 │
                              │ ┌──────────────────────────────┐ │
                              │ │ ② GovernanceAspect (AOP)     │ │
                              │ │   @Order(HIGHEST_PRECEDENCE) │ │
                              │ │   a. RateLimiter             │ │
                              │ │   b. ToolAuthorizationVoter  │ │
                              │ │   c. AuditWriter（前置）      │ │
                              │ │   d. ApprovalGate（T3）       │ │
                              │ │   e. MaskingEngine（回傳前）  │ │
                              │ └──────────────┬───────────────┘ │
                              │                ▼                 │
                              │ ┌──────────────────────────────┐ │
                              │ │ ③ 工具執行層                  │ │
                              │ │   QueryTemplateRegistry      │ │
                              │ │   （強制注入 :__tenant）      │ │
                              │ │   ResultCapper（200 列上限）  │ │
                              │ └──────────────┬───────────────┘ │
                              └────────────────┼─────────────────┘
                                               ▼
                     ┌─────────────────────────┴──────────────┐
                     ▼                                        ▼
          ┌────────────────────┐                  ┌──────────────────┐
          │    PostgreSQL 16   │                  │     Redis 7      │
          │ · 業務表           │                  │ · 配額計數器      │
          │ · mcp_audit_log    │                  │ （不做查詢快取）  │
          │ · mcp_approval_req │                  └──────────────────┘
          └────────────────────┘

          ┌──────────────────────────────────────────────┐
          │ 審批頁（獨立 filter chain + 表單登入）          │
          │ /approvals → 核准 / 駁回 / 重試 → 執行         │
          └──────────────────────────────────────────────┘
```

**為什麼治理層不放在 HTTP filter**：`SecurityFilterChain` 只看得到 token 與路徑，
看不到工具名與參數，做不了工具級 RBAC、遮罩與審批。因此是兩段式：
HTTP 層負責「你是誰」，AOP 層負責「你能不能做這件事」。詳見
[ADR-002](docs/adr/ADR-002-governance-via-aop.md)。

---

## 一次呼叫的完整資料流

以「查詢上週各店營收」為例：

```
1.  LLM 呼叫 query_sales_summary(startDate=…, endDate=…, groupBy=STORE)
2.  → SecurityFilterChain 驗證 access token，建立 Authentication
3.  → GovernanceAspect 攔截，取出 Principal{userId=42, role=STORE_MANAGER, tenantId=7}
4.  → 配額：分鐘 -1、日 -1（不足則回可讀的等待秒數）
5.  → 授權投票：STORE_MANAGER 可用 query_sales_summary ✓（預設拒絕）
6.  → 稽核前置寫入【REQUIRES_NEW，獨立交易，立即 commit】
7.  → 非寫入型，跳過審批閘門
8.  → 取出具名模板 sales.summary_by_store
9.  → 租戶注入：SQL 強制帶 AND merchant_id = :__tenant（值來自 Principal）
10. → 執行查詢，回傳 12 列（模板外層再包 LIMIT，超過即標記 truncated）
11. → 依角色套用遮罩（本查詢為聚合資料，無 PII）
12. → 包進 untrusted_data 信封
13. → 稽核後置更新：rowCount=12, latencyMs=87
14. → 回傳給 LLM
```

**兩個關鍵設計**

- 第 9 步的 `merchant_id` **永遠來自 Principal**。即使 LLM 被 prompt injection 誘導傳
  `merchantId=9`，工具方法簽章裡根本沒有這個參數——而且啟動期靜態檢查會讓任何
  帶 tenant 類參數的工具直接開不起來。
- 第 6 步用獨立交易先 commit。若與業務查詢同交易，業務端 rollback 會把稽核紀錄一起帶走，
  fail-closed 就形同虛設。見 [ADR-004](docs/adr/ADR-004-audit-fail-closed-and-transaction-boundary.md)。

---

## MCP 工具清單

| 工具 | 級別 | 用途 | 可用角色 |
|---|---|---|---|
| `query_sales_summary` | T1 | 期間營收彙總（依店家／品項／日期） | STORE_MANAGER, CS_LEAD, ANALYST, ADMIN |
| `check_inventory` | T1 | 庫存水位與低水位警示 | STORE_MANAGER, CS_LEAD, ADMIN |
| `list_top_products` | T1 | 熱銷品項排行 | STORE_MANAGER, CS_LEAD, ANALYST, ADMIN |
| `count_group_orders` | T1 | 團購單數量統計 | STORE_MANAGER, CS_LEAD, ANALYST, ADMIN |
| `check_approval_status` | T1 | 查詢審批單狀態與執行結果 | STORE_MANAGER, CS_LEAD, APPROVER, ADMIN |
| `search_group_orders` | T2 | 搜尋團購單清單 | CS_AGENT, CS_LEAD, ADMIN |
| `get_order_detail` | T2 | 單筆訂單完整明細（含遮罩後客戶欄位） | CS_AGENT, CS_LEAD, ADMIN |
| `adjust_inventory` | **T3** | 調整庫存（**需人工審批**） | STORE_MANAGER, ADMIN |

另有 MCP Resource `schema://bizmcp/data-dictionary`：提供資料字典讓 LLM 理解語意，
但取數只能走上面這些工具——**給語意，不給連線**。

| 級別 | 治理措施 | 分鐘配額 | 日配額 |
|---|---|---|---|
| T1 唯讀聚合 | RBAC + 租戶隔離 | 60 | 2,000 |
| T2 唯讀明細 | T1 + 欄位遮罩 | 20 | 300 |
| T3 寫入 | T2 + 人工審批 + 冪等鍵 | 5 | 20 |

日配額不是裝飾：agent 迴圈能用 60 次/分連跑一整天，只有分鐘配額擋不住成本。

---

## 治理機制

### 1. 工具級 RBAC，預設拒絕

政策就寫在工具旁邊，新增工具時無法「忘記設定權限」：

```java
@McpTool(name = "get_order_detail", description = """…""")
@ToolRisk(tier = RiskTier.T2, allow = {Role.CS_AGENT, Role.CS_LEAD, Role.ADMIN})
public Object getOrderDetail(@McpToolParam(...) Long orderId) { … }
```

沒有 `@ToolRisk` 的 `@McpTool` **會讓應用啟動失敗**，不是執行期才拒絕。

### 2. 租戶隔離：伺服器強制注入

每個查詢模板都必須綁 `:__tenant`，值只來自 Principal。兩道啟動期檢查：

- 模板缺 `:__tenant` → 啟動失敗
- 工具簽章出現 `merchantId` / `tenantId` 之類參數 → 啟動失敗

租戶外洩在執行期被發現時已經洩漏了，所以這兩條刻意做成開不起來。

### 3. 欄位級遮罩，依角色決定程度

```java
public record OrderDetail(
    @Masked(strategy = NAME)  String customerName,   // 王小明 → 王○明
    @Masked(strategy = PHONE) String phone,          // 0912345678 → 0912***678
    @Masked(strategy = ADDRESS, unmaskFor = {CS_LEAD, ADMIN}) String address,
    @Masked(strategy = EMAIL) String email,
    …
) {}
```

遮罩在**序列化之前**完成，不依賴任何 ObjectMapper——原因見
[兩個 spike 的結論](#兩個-spike-的結論)。

### 4. 寫入走人工審批

```
LLM 呼叫 adjust_inventory(productId=1001, delta=-50, reason="盤點差異")
  ↓ 工具「不執行」，只建立審批單並立即回傳
  { "status": "PENDING_APPROVAL", "approvalId": "apr_8f2c",
    "preview": { "productName": "珍珠奶茶", "before": 120, "after": 70, … } }
  ↓ 審批者在 /approvals 看到「120 → 70」而不是一串參數
  ↓ 核准後才執行，approvalId 即冪等鍵
  ↓ LLM 用 check_approval_status 確認真正結果
```

狀態機（v1.2 補上「核准後執行失敗」）：

```
                    ┌─────────► REJECTED（終態）
                    │
  PENDING ──────────┤
    │               └─────────► APPROVED ──► EXECUTED（終態）
    │ TTL 逾時                       │
    ▼                                └──► EXECUTION_FAILED
  EXPIRED（終態）                            │ 人工重試（上限 3 次）
                                            ├──► EXECUTED
                                            └──► ABANDONED（終態）
```

**執行失敗不自動重試。** 核准的是「當時的 preview」，狀況已變時自動重試等於執行了
沒人看過的操作。`check_approval_status` 必須能回報 `EXECUTION_FAILED`，
讓 LLM 誠實說「核准了但沒成功」，而不是靜默假設成功。

### 5. 稽核 fail-closed

稽核前置寫入，寫入失敗則**拒絕執行**——沒有紀錄的操作在企業裡等同沒有發生。
稽核走 `REQUIRES_NEW` 獨立交易，業務端 rollback 不會把紀錄帶走。

稽核記錄的參數本身也會遮罩，避免日誌變成外洩點。但自由文字搜尋詞是**只洗掉個資、
保留其餘內容**：把 `客戶 0912345678 的團` 整串遮成 `客○○…○團` 雖然沒有外洩，
卻也讓稽核無法回答「他到底搜了什麼」。

### 6. Prompt injection：三道防線，不吹噓能根治

**誠實的立場是無法根治，只能降低影響半徑。**

1. **資料標記** — 工具回傳包在 `untrusted_data` 信封裡，server instructions
   於協定層聲明此區塊是資料而非指令
2. **最小權限** — 就算被誘導，能做的也只有身分內能做的事（這是租戶隔離必須做在
   伺服器端的原因）
3. **寫入需人工** — 最壞情況下，破壞性操作仍停在審批閘門

### 7. 錯誤訊息是介面的一部分

LLM 會讀錯誤訊息並嘗試自我修正，所以訊息要「寫給 LLM 看」：

| 情境 | 回傳 |
|---|---|
| 參數格式錯誤 | 「endDate (…) 早於 startDate (…)，請調換順序。」 |
| 權限不足 | 「此操作需要 CS_LEAD 角色…請告知使用者聯繫主管開通權限。」 |
| 超出配額 | 「已達分鐘速率上限，請於 45 秒後重試。」 |
| 查無資料 | 「查詢成功，但找不到訂單 …」（與「出錯」區分開） |
| 內部錯誤 | 「系統暫時無法處理，追蹤編號 trc_a91f。」 |

最後一列不是自動成立的：Spring AI 回報工具失敗時用的是 **root cause**，
直接拋例外會把 `Conversion from JSON to java.time.LocalDate failed`
或 PostgreSQL 的 parser 錯誤原封不動交給模型。因此有兩層清洗，
且採**白名單**——沒有標記為「寫給模型看」的訊息一律換成追蹤編號。

---

## 兩個 spike 的結論

規格書 §14 把兩件事列為全案技術前提，要求 Week 1 先驗證。
**兩個都推翻了規格書原本的設計**，這是本專案最有價值的部分。

### Spike ①：AOP 可以攔截 `@McpTool`，但會讓工具全部消失

Spring AI 2.0.0 用 `bean.getClass().getDeclaredMethods()` 枚舉工具方法。
一旦治理切面代理了工具 bean，`bean.getClass()` 就是 CGLIB 子類，
而**覆寫方法不會繼承註解**，於是框架找到 **0 個工具**。

沒有例外、沒有 log。應用正常啟動，只是什麼都不提供：

```
isProxy         = true
bean.getClass() = SalesTools$$SpringCGLIB$$0
query hasMcpTool= false
discoveredTools = 0     ← 加了安全層，功能全部消失
```

這個失效模式特別惡劣：如果測試是打 service 而不是走協定，它們會全部通過。

**修法**是三行——掃描前先解析 target class：

```java
new SyncMcpToolProvider(toolBeans) {
    @Override protected Method[] doGetClassMethods(Object bean) {
        return AopUtils.getTargetClass(bean).getDeclaredMethods();
    }
};
```

`GovernedMcpSpecificationConfig` 另外在發現 0 個工具時直接拋例外，
讓這個「安靜的失敗」永遠不可能再安靜。

### Spike ②：遮罩不能放在序列化層——那個掛載點不存在

規格書 §7.2 打算用自訂 Jackson Module 在序列化時遮罩，並正確地警告了風險：
*若 MCP 層用的不是同一個 ObjectMapper，遮罩會完全不生效且不報錯*。

實測結果是：**這不是風險，這是現況。**

```java
// AbstractMcpToolMethodCallback
private static final org.springframework.ai.util.JsonHelper jsonHelper;

// JsonHelper
private final tools.jackson.databind.json.JsonMapper jsonMapper;   // 自己 new 的
```

`private static final`、不是 Spring bean、而且 Spring AI 2.0 已遷到 **Jackson 3**
（`tools.jackson.databind`）。註冊到 Boot ObjectMapper 的 Module 永遠不會被呼叫到，
而且不會報錯。

**修法**是往前挪一步：`GovernanceAspect` 在回傳前就把物件遮罩好，
框架拿到的已經是遮罩後的資料。這比原設計**更強**而不是妥協——它與序列化器無關，
不會因為換 mapper、換 Jackson 大版本而失效。

對應的測試斷言在 **wire payload 字串**上，不是 DTO 物件——斷言 DTO 的話，
就算實際送出的位元組沒被遮罩，測試依然會通過，那正是規格書警告的錯誤。
另外有一條正規表達式掃描：回應中只要出現完整手機格式 `09\d{8}` 就失敗。

---

## 測試

```bash
mvn verify
```

**82 個測試，100% 通過，每次 push 擋 CI。**

| 類別 | 案例數 | 斷言重點 |
|---|---|---|
| 工具級 RBAC | 26 | 每個工具 × 角色，放行／拒絕符合矩陣 |
| 租戶隔離 | 8 | A 租戶查不到 B 租戶任何一列；偽造的 tenant 參數無效 |
| 遮罩（wire-level） | 8 | 回應 JSON 不含完整手機／姓名；地址依角色差異 |
| 稽核 fail-closed | 6 | 工具拋例外時稽核仍留痕；切面順序正確 |
| 稽核寫入失敗 | 1 | 稽核寫不進去時工具被拒 |
| 審批與冪等 | 7 | 同一 approvalId 執行兩次庫存只變動一次；失敗轉 EXECUTION_FAILED |
| 限流 | 4 | 超過分鐘配額回正確錯誤並留痕 |
| 啟動期檢查 | 5 | 模板缺 `:__tenant`、工具含 tenant 參數時啟動失敗 |
| 錯誤訊息契約 | 6 | 可自我修正、且不洩漏內部細節 |
| 工具註冊 | 2 | 8 個工具確實註冊且治理鏈生效 |
| Spike ① 回歸 | 2 | 代理破壞發現機制的行為被釘住 |
| 評測資料集驗證 | 6 | 30 題格式正確、期望工具都存在、計分正確 |
| Migration + seed | 1 | schema 與種子資料可套用 |

**測試跑在真的 PostgreSQL 上**（JSONB 欄位用 H2 代替沒有意義）。
規格書寫 Testcontainers，但實作改用 zonky `embedded-postgres`：它在行程內啟動
真正的 PostgreSQL binary，**不需要 Docker daemon**，所以在受限的 CI runner
或沙箱裡也能跑同一套測試。

Suite B（LLM 評測，30 題）**尚未執行，因此沒有任何分數**。
資料集、計分器與其單元測試都已完成並在 Suite A 中驗證；
真正驅動模型的部分還沒寫，見 [docs/eval/README.md](docs/eval/README.md)。
`docs/eval-report.md` 的數字欄位刻意留白——**先編數字比沒有數字更傷**，
因為第一個問題一定是「你怎麼量的」。

---

## 與規格書的差異

| # | 規格書 | 實作 | 原因 |
|---|---|---|---|
| 1 | 遮罩掛在 MCP 的 ObjectMapper（§7.2） | 改在治理鏈回傳前遮罩 | **Spike ② 推翻**：掛載點不存在，見 ADR-003 |
| 2 | AOP 直接環繞 `@McpTool`（§5.3） | AOP + 自訂 tool provider | **Spike ① 推翻**：代理會讓工具全部消失，見 ADR-002 |
| 3 | 工具回傳具體型別（§6.3） | 回傳 `Object` | CGLIB 會把 advice 回傳值轉型成宣告型別；具體型別會 ClassCastException。具體型別退到 service／DTO 層 |
| 4 | `tools/list` 依角色過濾（§5.4） | **未實作**，降為 P2 | Spring AI 2.0 / MCP SDK 2.0 沒有 per-request 的工具列表掛載點。規格書本來就寫「技術上不可行則降級，呼叫時攔截仍然有效」——攔截確實有效且有 26 個測試覆蓋 |
| 5 | Redis 令牌桶（§6.1） | 固定視窗計數器 | 規格描述的是「每分鐘 60、每日 2000」的配額語意；固定視窗能給出精確的「請於 N 秒後重試」。代價是跨視窗邊界最多 2 倍瞬時速率 |
| 6 | Testcontainers（§4） | zonky embedded-postgres | 仍是真的 PostgreSQL，但不需要 Docker daemon |
| 7 | 角色未列舉 | 新增 `CS_AGENT` | 原設計中 `get_order_detail` 只有 CS_LEAD／ADMIN 能呼叫，而這兩個角色又都在地址的 `unmaskFor` 名單裡——地址遮罩永遠不會生效。加一個前線客服角色讓這條規則變成真的 |
| 8 | 稽核參數遮罩（§7.2） | 搜尋關鍵字改為只洗個資 | 整串遮掉會讓稽核無法回答「他搜了什麼」 |
| 9 | — | 新增錯誤訊息清洗 | Spring AI 回報 root cause，原設計會把內部錯誤直接交給模型（§10 要求不洩漏） |
| 10 | 稽核封存機制（§7.4） | **只有政策沒有實作** | 規格書自己也說 4 週做不完，但政策必須寫 |

---

## 已知限制

主動列出來，比被問出來好：

- **`tools/list` 沒有依角色過濾。** 低權限使用者仍會在列表看到用不到的工具；
  呼叫時會被拒絕，資料不會外洩，但能力面確實有洩漏。原因是框架限制（見差異 #4）。
- **Suite B 沒有跑過，沒有任何 LLM 相關數字。**
- **單租戶架構的簡化版。** 真正的多租戶 SaaS 還要處理跨租戶報表、租戶級配置。
- **審批只有單層。** 實務上應依金額或影響範圍分級。
- **稽核封存只有政策沒有實作**（保存 90 天 → 分區封存 → 一年後刪除）。
- **JWT 簽章金鑰在啟動時產生**，重啟後既有 token 失效。正式部署要換成受管金鑰。
- **API key 路徑是本機捷徑**，正式環境必須關閉。
- **沒做成本歸屬**（哪個部門用掉多少 token）——那是 LLM Gateway 那一層的職責，
  兩個專案刻意切開。
- **沒有真實企業資料**，種子資料只是貼近真實分布的 demo 資料。

---

## 設計決策記錄（ADR）

1. [ADR-001 不做 text-to-SQL，改用具名查詢模板](docs/adr/ADR-001-named-query-templates.md)
2. [ADR-002 治理鏈用 AOP，以及它造成的工具發現 bug（Spike ①）](docs/adr/ADR-002-governance-via-aop.md)
3. [ADR-003 遮罩改在序列化前（Spike ②）](docs/adr/ADR-003-masking-before-serialization.md)
4. [ADR-004 稽核 fail-closed、REQUIRES_NEW 與切面順序](docs/adr/ADR-004-audit-fail-closed-and-transaction-boundary.md)

---

## 技術棧

Java 21 · Spring Boot 4.1.0 · Spring AI 2.0.0（MCP, Streamable HTTP）·
Spring Authorization Server · `org.springaicommunity:mcp-server-security` 0.1.14 ·
PostgreSQL 16 · Redis 7 · Flyway · Micrometer · JUnit 5 · zonky embedded-postgres

> Spring AI 2.0.0 於 2026-06-12 GA，相對 1.x 有破壞性變更
> （MCP annotation 套件更名為 `org.springframework.ai.mcp.annotation`、Jackson 2→3）。
> 版本以 `spring-ai-bom` 鎖定。Spring Boot 4 也把自動組態拆進 `spring-boot-<tech>` 模組，
> 例如 Flyway 需要額外引入 `spring-boot-flyway`，AOP starter 更名為 `spring-boot-starter-aspectj`。
