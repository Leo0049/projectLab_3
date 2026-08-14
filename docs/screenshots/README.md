# 截圖

實機執行的畫面，非設計稿。取自 `java -jar target/bizmcp-1.2.0.jar` 接真的
PostgreSQL 16 與 Redis 7、灌入 50,000 筆 demo 訂單後的執行中系統。
逐字稿（含 MCP 協定層的輸入輸出）見 [../demo-transcript.md](../demo-transcript.md)。

| 檔案 | 內容 |
|---|---|
| `01-login.png` | 審批／稽核台的表單登入頁 |
| `02-approval-console.png` | 審批中心，同時呈現 5 張審批單的 4 種狀態 |
| `03-audit-trail.png` | 稽核軌跡（AUDITOR 身分讀取 `/audit/logs`） |

## 02 值得看的地方

- **狀態機是真的**：PENDING（可核准／駁回）、REJECTED（帶駁回原因）、
  EXECUTION_FAILED（帶失敗原因，且只有這一列有「重試」按鈕）、EXECUTED（終態，無操作）。
- **影響預覽**：審批者看到的是 `before: 120 → after: 100`、品名、以及受影響訂單數，
  不是一串參數。這是「審批」跟「橡皮圖章」的差別。
- **審批單參數未遮罩**（`reason: 盤點差異`）——這是刻意的，審批者必須看到真值才能判斷。
  代價是這張表的保存與存取政策比稽核表更嚴，見 ADR 與 §7.4。

## 03 值得看的地方

- 同一批操作在稽核軌跡裡的樣子：`decision=PENDING_APPROVAL` 的寫入、
  `check_approval_status` 的查詢、以及 **AUDITOR 自己讀稽核也留下 `audit_query_logs`**。
- 稽核表的 `arguments` 是**遮罩後**的版本，與審批單的未遮罩版本互不污染。

## 還沒有的截圖

Claude Desktop 的 OAuth 連線畫面需要桌面應用程式，這個環境產不出來，
列在 README 的已知限制。MCP 協定層的行為改以逐字稿佐證。
