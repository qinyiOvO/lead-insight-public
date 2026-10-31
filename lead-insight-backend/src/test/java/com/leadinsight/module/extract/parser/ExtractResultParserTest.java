package com.leadinsight.module.extract.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ExtractResultParser 校验规则单测（T2-02，亮点1 验收点：字段完整性 + intentCategory 枚举合法）。
 */
class ExtractResultParserTest {

    private final ExtractResultParser parser = new ExtractResultParser();

    private static final String FULL_JSON = """
            {
              "customerName": "张三",
              "companyName": "鼎新科技有限公司",
              "contactInfo": "13800138000",
              "intentCategory": "QUOTE",
              "demandSummary": "采购304不锈钢管500米，7天内需正式报价",
              "budgetAmount": 600000,
              "budgetText": "预算60万元",
              "expectedTime": "2026-10-08",
              "expectedTimeText": "7天内",
              "companyScale": "LARGE",
              "keywordsHit": "采购,报价,合同",
              "someUnknownField": "应被忽略"
            }
            """;

    @Test
    @DisplayName("合法完整 JSON → 字段全部映射，未知字段忽略")
    void parseFullJson() {
        ExtractedLead lead = parser.parseAndValidate(FULL_JSON);
        assertEquals("张三", lead.getCustomerName());
        assertEquals("QUOTE", lead.getIntentCategory());
        assertEquals(new BigDecimal("600000"), lead.getBudgetAmount());
        assertEquals(LocalDate.of(2026, 10, 8), lead.getExpectedTime());
        assertEquals("LARGE", lead.getCompanyScale());
        assertTrue(parser.toJson(lead).contains("\"expectedTime\":\"2026-10-08\""));
    }

    @Test
    @DisplayName("官方已知缺陷：空内容 → 解析失败")
    void emptyContentFails() {
        assertThrows(ExtractParseException.class, () -> parser.parseAndValidate("  "));
    }

    @Test
    @DisplayName("High-1 回归：JSON 字面量 null → 抛 ExtractParseException（而非 NPE 穿透）")
    void jsonNullLiteralFailsNotNpe() {
        ExtractParseException e = assertThrows(ExtractParseException.class,
                () -> parser.parseAndValidate("null"));
        assertTrue(e.getMessage().contains("JSON null"), "应折算为可读的解析失败原因，实际：" + e.getMessage());
    }

    @Test
    @DisplayName("High-1 加固：空串 / 纯空白 / Java null → 解析失败，全程不抛 NPE")
    void blankInputsFailNotNpe() {
        assertThrows(ExtractParseException.class, () -> parser.parseAndValidate(""));
        assertThrows(ExtractParseException.class, () -> parser.parseAndValidate("  \n\t "));
        assertThrows(ExtractParseException.class, () -> parser.parseAndValidate(null));
    }

    @Test
    @DisplayName("合法但字段全空的 {} → 走 sanitize 不 NPE，最终因 intentCategory 缺失判失败")
    void emptyObjectFailsOnIntentInsteadOfNpe() {
        ExtractParseException e = assertThrows(ExtractParseException.class,
                () -> parser.parseAndValidate("{}"));
        assertTrue(e.getMessage().contains("intentCategory 缺失"),
                "empty 对象应走到 validate 判 intentCategory 缺失，实际：" + e.getMessage());
    }

    @Test
    @DisplayName("非 JSON 对象 → 解析失败")
    void notJsonFails() {
        assertThrows(ExtractParseException.class,
                () -> parser.parseAndValidate("好的，我来帮你抽取：张三要买服务器"));
    }

    @Test
    @DisplayName("intentCategory 缺失 → 失败（枚举合法性为验收点）")
    void missingIntentFails() {
        String json = "{\"customerName\":\"张三\",\"demandSummary\":\"采购服务器\"}";
        ExtractParseException e = assertThrows(ExtractParseException.class,
                () -> parser.parseAndValidate(json));
        assertTrue(e.getMessage().contains("intentCategory 缺失"));
    }

    @Test
    @DisplayName("intentCategory 非法枚举 → 失败")
    void illegalIntentFails() {
        String json = "{\"intentCategory\":\"UNKNOWN_CATEGORY\",\"demandSummary\":\"x\"}";
        ExtractParseException e = assertThrows(ExtractParseException.class,
                () -> parser.parseAndValidate(json));
        assertTrue(e.getMessage().contains("intentCategory 非法"));
    }

    @Test
    @DisplayName("负数预算 → 失败；合法字段可全空（完整性只影响置信度不判失败）")
    void budgetAndEmptyFields() {
        assertThrows(ExtractParseException.class,
                () -> parser.parseAndValidate("{\"intentCategory\":\"CONSULT\",\"budgetAmount\":-1}"));
        ExtractedLead empty = parser.parseAndValidate("{\"intentCategory\":\"IRRELEVANT\"}");
        assertNotNull(empty);
        assertNull(empty.getCustomerName());
        assertNull(empty.getDemandSummary());
    }

    @Test
    @DisplayName("expectedTime 非 yyyy-MM-dd → 失败")
    void badDateFails() {
        assertThrows(ExtractParseException.class, () -> parser.parseAndValidate(
                "{\"intentCategory\":\"CONSULT\",\"expectedTime\":\"下周三之前\"}"));
    }

    @Test
    @DisplayName("companyScale 非法枚举 → 失败")
    void badScaleFails() {
        assertThrows(ExtractParseException.class, () -> parser.parseAndValidate(
                "{\"intentCategory\":\"CONSULT\",\"companyScale\":\"超大\"}"));
    }
}
