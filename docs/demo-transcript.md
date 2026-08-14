# Demo 逐字稿（實機執行紀錄）

規格書 §13 的 3 分鐘 demo 腳本，在**真的 PostgreSQL 16 + 真的 Redis 7 + 打包好的
jar** 上實際跑過一次的輸出，非設計稿、非預期值。

- 執行方式：`java -jar target/bizmcp-1.2.0.jar`，資料庫由 Flyway 建立並灌入
  50,000 筆 demo 訂單；MCP 呼叫走 Streamable HTTP `/mcp`（initialize → session →
  tools/call），審批走瀏覽器表單（含 CSRF）。
- 這份逐字稿取代交付清單裡的「連線截圖」：截圖只能證明畫面，逐字稿能證明行為。
- Claude Desktop 的 OAuth 連線畫面仍需人工錄製，見 README 已知限制。

## 1. 啟動（真 PostgreSQL 16 + 真 Redis 7）
```
  Redis-backed quotas (shared across instances)
  loaded 9 query templates, all tenant-scoped
  registered 8 governed MCP tools from 4 beans
  tool governance validation passed for 8 tools
  Started BizMcpApplication in 19.971 seconds (process running for 20.596)
```

## 2. 資料量與租戶分布
```
   商戶 | 訂單數 
  ------+--------
      7 |  30000
      9 |  20000
  (2 rows)
  
```

## 3. 租戶隔離：同一個問題，兩個身分
```
  alice/租戶7    → 中山店, 大安店, 信義店  總營收 378000.0
  dave/租戶9     → 板橋店, 新莊店  總營收 291344.0
  alice 硬塞 merchantId=9 → 中山店, 大安店, 信義店  ← 參數被忽略
```

## 4. 欄位遮罩：同一筆訂單，三種角色
```
  角色    姓名   電話        地址
  CS_AGENT  陳○麗      0922***444    台北市大安區***
  CS_LEAD   陳○麗      0922***444    台北市大安區忠孝東路四段2號
  ADMIN     陳○麗      0922***444    台北市大安區忠孝東路四段2號
```

## 5. 工具級 RBAC：store manager 呼叫同一工具
```
  isError: True
  此操作需要 CS_AGENT 或 CS_LEAD 或 ADMIN 角色，目前身分無權呼叫 get_order_detail。請告知使用者聯繫主管開通權限。
```

## 6. 寫入治理：LLM 要求把珍珠庫存 120 → 100
```
  status  : PENDING_APPROVAL
  approval: apr_d3c0639d
  preview : {"productName": "珍珠奶茶", "before": 120, "after": 100, "delta": -20, "affectedOrders": 0}
  message : 此操作需人工核准，尚未生效。請告知使用者前往 /approvals 確認；核准後請用 check_approval_status（approvalId=apr_d3c0639d）查詢實際結果，在查到 EXECUTED 之前不要向使用者宣稱已完成。
  核准前實際庫存: 120  ← 工具回覆了，但資料沒有變
```

## 7. 人工核准後，LLM 才敢說完成
```
  erin 登入 /approvals → 核准 apr_d3c0639d   (HTTP 302)
  核准後實際庫存: 100
  check_approval_status → EXECUTED | 已核准並成功執行，資料已變更。
```

## 8. 稽核軌跡（grace = AUDITOR）
```
  工具                     角色             決策                參數（已遮罩）
  audit_query_logs       AUDITOR        ALLOW             {}
  check_approval_status  STORE_MANAGER  ALLOW             {"approvalId": "apr_d3c0639d"}
  adjust_inventory       STORE_MANAGER  PENDING_APPROVAL  {"delta": "-20", "reason": "盤點差異", "productI
```

## 9. 稽核台的存取控制
```
  alice (STORE_MANAGER) 讀 /audit/logs → HTTP 403（預期 403）
```

## 10. 配額（T3 每分鐘 5 次）

```
  第 1 次: ALLOW PENDING_APPROVAL
  第 2 次: ALLOW PENDING_APPROVAL
  第 3 次: ALLOW PENDING_APPROVAL
  第 4 次: ALLOW PENDING_APPROVAL
  第 5 次: ALLOW PENDING_APPROVAL
  第 6 次: DENY  已達分鐘速率上限，請於 38 秒後重試。
```

---

## 這次實機跑出來、單元測試沒抓到的兩個問題

1. **50,000 筆訂單全部落在租戶 7，租戶 9 是空的。** demo 種子的 LATERAL 用
   `ORDER BY (store_id * 13 + n) % 5` 挑店，運算式在店家之間有 tie，而 tie 每次
   都解到同一邊。結果是「對空租戶示範租戶隔離」——正是測試註解裡寫明不該做的事。
   已改為 `ORDER BY store_id OFFSET (n % 5)`（精確均勻），並在效能測試加上
   「兩個租戶都必須有實際訂單」的斷言。

2. **待審批訊息裡的 `approvalId=%s` 沒有被代入。** Java 的
   `a + b + c.formatted(x)` 只會把 `formatted` 綁到 `c`，而 `%s` 在中間那段。
   模型會literally讀到 `%s`，等於拿不到單號、無法回頭查狀態。已加括號修正，並用
   測試釘住「訊息不得含 `%s`、必須含真實單號」。

兩個都只有在真的跑起來、真的讀輸出時才會現形——這是做這次實機排練的理由。
