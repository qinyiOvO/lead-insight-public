package com.leadinsight.module.importtask.executor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 计数缓冲的时间窗口单测（P-10 / Q18 选型 C：按时间刷而非按条数攒）。
 *
 * <p>只测纯函数式的窗口判定，不 sleep：把"当前时间"作为入参，避免测试变慢或抖动。
 * 桶计数正确性由 {@link ImportTaskExecutorBatchRateLimitTest} 对 {@code bumpCounters}
 * 增量参数的断言覆盖，此处不重复。</p>
 */
class ImportTaskExecutorCounterFlushTest {

    @Test
    @DisplayName("P-10：缓冲未满 2 秒不刷，满 2 秒才刷；空缓冲永远不刷")
    void buffer_flushDueOnlyAfterIntervalAndOnlyWhenDirty() {
        ImportTaskExecutor.CounterBuffer buffer = new ImportTaskExecutor.CounterBuffer();
        long start = System.currentTimeMillis();

        // 空缓冲：即便过了很久也不该刷（否则产生一次无意义的 0 增量 UPDATE）
        assertFalse(buffer.isFlushDue(start + 10_000), "空缓冲不得因超时触发刷库");

        buffer.record(ImportTaskExecutor.Bucket.SUCCESS);
        assertFalse(buffer.isFlushDue(start + ImportTaskExecutor.FLUSH_INTERVAL_MILLIS - 1),
                "未到 2 秒窗口不得刷库（避免退化成每条一刷）");
        assertTrue(buffer.isFlushDue(start + ImportTaskExecutor.FLUSH_INTERVAL_MILLIS),
                "满 2 秒必须刷库（进度最多滞后 2 秒）");
        assertTrue(buffer.isFlushDue(start + 5_000), "超过窗口后任意时刻都应可刷");

        // 刷完重置：计数清零、窗口重新计时
        buffer.reset(start + 5_000);
        assertTrue(buffer.isEmpty(), "刷库后缓冲必须清空，否则下个窗口会重复计增量");
        assertFalse(buffer.isFlushDue(start + 5_001), "重置后重新计时，不得立即再次刷库");
    }
}
