package dev.amai.portfolio.common;

import java.util.List;
import lombok.Getter;

/** 所有分页响应的共同结构；业务额外字段通过扩展表达，不重复声明分页字段。 */
@Getter
public class PageVO<T> {
    private final int page;

    private final int size;

    private final long total;

    private final List<T> items;

    public PageVO(int page, int size, long total, List<T> items) {
        this.page = page;
        this.size = size;
        this.total = total;
        this.items = List.copyOf(items);
    }

}
