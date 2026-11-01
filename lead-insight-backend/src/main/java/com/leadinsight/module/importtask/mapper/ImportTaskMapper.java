package com.leadinsight.module.importtask.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.leadinsight.module.importtask.entity.ImportTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 批量导入任务 Mapper。只做数据读写，不含业务逻辑。
 */
@Mapper
public interface ImportTaskMapper extends BaseMapper<ImportTask> {

    /**
     * 一次性累加任务计数（P-10 / §5.2-Q18）：processed 与四桶增量合并为 1 条 UPDATE。
     *
     * <p>相比原实现（每条条目一次 {@code UPDATE}，且桶列名靠字符串拼接
     * {@code setSql(bucket + " = " + bucket + " + 1")}）有三点改进：
     * ① 调用频次由"每条一次"降为"每 2 秒一次 + 收尾一次"，热点行锁等待大幅减少；
     * ② SET 列表固定，桶列名不再参与 SQL 文本拼接（同一业务动作永远同一条 digest）；
     * ③ 桶列名错误从"运行期才炸"变成"启动期就炸"（列名写死在注解里）。</p>
     *
     * <p>用增量而非绝对值：调用方不需要先读一遍任务行拿基线，也就不存在"读-改-写"丢更新。</p>
     */
    @Update("UPDATE `import_task` SET "
            + "processed_count = processed_count + #{processed}, "
            + "success_count = success_count + #{success}, "
            + "duplicate_count = duplicate_count + #{duplicate}, "
            + "manual_count = manual_count + #{manual}, "
            + "fail_count = fail_count + #{fail} "
            + "WHERE id = #{taskId}")
    int bumpCounters(@Param("taskId") Long taskId,
                     @Param("processed") int processed,
                     @Param("success") int success,
                     @Param("duplicate") int duplicate,
                     @Param("manual") int manual,
                     @Param("fail") int fail);
}
