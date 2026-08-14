package com.bizmcp.tools;

import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.stereotype.Component;

/**
 * The data dictionary, exposed as an MCP resource (spec section 6.4).
 *
 * <p>Giving the model the vocabulary but not a database connection is the
 * whole point: it can understand what "COMPLETED" means or which field holds
 * revenue, while every actual read still goes through a named template with a
 * tenant predicate.
 */
@Component
public class DataDictionaryResource {

    @McpResource(
            uri = "schema://bizmcp/data-dictionary",
            name = "data-dictionary",
            title = "BizMCP 資料字典",
            description = "實體、欄位意義與狀態機定義。用於理解營運資料的語意，"
                          + "但取數一律透過已定義的工具，本資源不提供資料庫存取。",
            mimeType = "text/markdown"
    )
    public String dataDictionary() {
        return """
                # BizMCP 資料字典

                本服務對應一個飲料團購平台的營運資料。所有查詢都限縮在你所屬的
                商戶（tenant）範圍內，這個範圍由伺服器依你的身分決定，無法以參數指定。

                ## 實體

                ### 店家 store
                | 欄位 | 意義 |
                |---|---|
                | storeId | 店家識別碼 |
                | storeName | 店名，例如「信義店」 |

                ### 品項 product / 庫存 inventory
                | 欄位 | 意義 |
                |---|---|
                | productId | 品項識別碼 |
                | name | 品名，例如「珍珠奶茶」 |
                | quantity | 目前庫存數量 |
                | lowWaterMark | 安全水位；低於此值視為需要補貨 |
                | belowThreshold | quantity <= lowWaterMark 時為 true |

                ### 團購單 group_order
                一次開團。狀態機：

                    OPEN ──► CLOSED ──► COMPLETED
                      │
                      └──► CANCELLED

                | 狀態 | 意義 |
                |---|---|
                | OPEN | 開放中，還可以加訂 |
                | CLOSED | 已結單，不再收單 |
                | COMPLETED | 已完成取貨 |
                | CANCELLED | 已取消 |

                ### 訂單 order
                團購單底下某位客戶的訂單。狀態機：

                    PENDING ──► COMPLETED
                       │
                       └──► CANCELLED

                **營收只計入 COMPLETED 的訂單**，PENDING 與 CANCELLED 不列入。
                這是所有營收類工具的一致口徑。

                ### 客戶 customer
                含個人資料。姓名、電話、Email 一律遮罩後回傳；
                地址僅客服主管與系統管理員可見完整內容。

                ## 審批狀態機

                寫入型操作不會立即生效，會先產生一張審批單：

                    PENDING ──► APPROVED ──► EXECUTED
                       │            │
                       │            └──► EXECUTION_FAILED ──► EXECUTED / ABANDONED
                       ├──► REJECTED
                       └──► EXPIRED

                只有 EXECUTED 代表資料真的變更了。
                """;
    }
}
