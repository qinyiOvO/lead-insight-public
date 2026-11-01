package com.leadinsight.module.lead.mapper;

import com.leadinsight.module.lead.entity.Lead;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 写入路径精确列更新（P-07 / §5.3-Q12）的机械护栏。
 *
 * <p>这类"改完一时对、以后被人加回来"的优化，光靠一次性 EXPLAIN + 日志对照守不住：
 * 后续有人图省事把 {@code original_text} 塞回 SET，SQL 文本会立刻裂成新的 digest，
 * 但页面、接口、单测全都不报错——只有压测时才看得出来。故把判据直接钉在 SQL 文本上。</p>
 *
 * <p>同时守 {@code deleted_at IS NULL}：裸 {@code @Update} 不经过 {@code @TableLogic} 注入，
 * 漏写会更新已逻辑删除的行，属于静默数据错误。</p>
 */
class LeadMapperColumnUpdateTest {

    /** updateExtractFields 的 SET 列数（16 个业务列 + updated_at） */
    private static final int EXTRACT_FIELDS_COLUMNS = 17;

    /** updateExtractStatusGuarded 的 SET 列数（extract_status + updated_at） */
    private static final int EXTRACT_STATUS_COLUMNS = 2;

    private static final Pattern ASSIGNMENT = Pattern.compile("`([a-z_]+)`\\s*=");

    @Test
    @DisplayName("P-07：updateExtractFields 固定 17 列，且永不写入原文/指纹/审计列")
    void updateExtractFields_fixedColumnSet() {
        String sql = sqlOf("updateExtractFields", Lead.class);

        List<String> columns = setColumns(sql);
        assertEquals(EXTRACT_FIELDS_COLUMNS, columns.size(),
                "SET 列数必须固定为 17（16 业务列 + updated_at），实际=" + columns);
        assertEquals(columns.size(), columns.stream().distinct().count(),
                "SET 列不得重复，实际=" + columns);

        // 抽取结果列必须在
        for (String required : List.of("subject_title", "customer_name", "company_name",
                "intent_category", "demand_summary", "extract_confidence", "confidence_level",
                "keywords_hit", "extract_status", "status", "updated_at")) {
            assertTrue(columns.contains(required), required + " 必须写入，实际=" + columns);
        }

        // 永不写入的列：原文不可改（MEDIUMTEXT 重写是 P-07 的优化对象）、
        // 指纹由原文决定、审计事实列与统计口径无关、评分列由 ScoringServiceImpl 单独更新
        for (String forbidden : List.of("original_text", "content_fingerprint", "created_by",
                "import_task_id", "import_source", "created_at",
                "priority_score", "priority_level", "score_reason")) {
            assertFalse(sql.contains(forbidden), forbidden + " 不得进入 updateExtractFields，实际=" + sql);
        }

        assertTrue(sql.contains("`deleted_at` IS NULL"),
                "裸 @Update 不经 @TableLogic，必须显式写 deleted_at IS NULL，实际=" + sql);
        assertTrue(sql.contains("`id` = #{e.id}"), "主键条件必须存在，实际=" + sql);
    }

    @Test
    @DisplayName("§5.3：persistWaiting 只 SET 2 列（extract_status + updated_at），保留归属守卫")
    void updateExtractStatusGuarded_onlyTwoColumns() {
        String sql = sqlOf("updateExtractStatusGuarded", Long.class, String.class, Long.class);

        List<String> columns = setColumns(sql);
        assertEquals(EXTRACT_STATUS_COLUMNS, columns.size(),
                "置 WAITING 只该动 1 个业务列，实际=" + columns);
        assertTrue(columns.contains("extract_status"), "实际=" + columns);
        assertTrue(columns.contains("updated_at"), "实际=" + columns);

        assertFalse(sql.contains("original_text"), "原文不得写入，实际=" + sql);
        assertTrue(sql.contains("`deleted_at` IS NULL"), "必须显式写 deleted_at IS NULL，实际=" + sql);
        // 归属守卫：与并发认领交错时影响行数为 0，调用方据此判并发失败
        assertTrue(sql.contains("`owner_id` IS NULL") && sql.contains("`owner_id` = #{userId}"),
                "必须保留「未认领或本人」的归属条件，实际=" + sql);
    }

    // ------------------------------------------------------------------ helpers

    private static String sqlOf(String methodName, Class<?>... parameterTypes) {
        Method method;
        try {
            method = LeadMapper.class.getMethod(methodName, parameterTypes);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("LeadMapper 缺少方法 " + methodName, e);
        }
        Update update = method.getAnnotation(Update.class);
        assertNotNull(update, methodName + " 必须是 @Update 注解方法（否则逻辑删除与列集合都不可控）");
        return String.join(" ", update.value()).replaceAll("\\s+", " ").trim();
    }

    /** 截取 SET 与 WHERE 之间的赋值列名 */
    private static List<String> setColumns(String sql) {
        int setIdx = sql.indexOf(" SET ");
        int whereIdx = sql.indexOf(" WHERE ");
        assertTrue(setIdx >= 0 && whereIdx > setIdx, "SQL 结构不符合预期：" + sql);
        String setPart = sql.substring(setIdx + 5, whereIdx);
        List<String> columns = new ArrayList<>();
        Matcher matcher = ASSIGNMENT.matcher(setPart);
        while (matcher.find()) {
            columns.add(matcher.group(1));
        }
        return columns;
    }
}
