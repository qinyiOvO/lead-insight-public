package com.leadinsight.common.result;

import lombok.Data;

import java.util.List;

/**
 * 分页响应封装。结构：{items,total,page,limit,hasMore}。
 */
@Data
public class PageResult<T> {

    private List<T> items;
    private long total;
    private int page;
    private int limit;
    private boolean hasMore;

    public PageResult() {
    }

    public PageResult(List<T> items, long total, int page, int limit) {
        this.items = items;
        this.total = total;
        this.page = page;
        this.limit = limit;
        this.hasMore = (long) page * limit < total;
    }

    public static <T> PageResult<T> of(List<T> items, long total, int page, int limit) {
        return new PageResult<>(items, total, page, limit);
    }
}
