package dev.amai.portfolio.common;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 所有列表共用页码、条数和校验。size 未填写时允许业务选择自己的既有默认值，
 * 例如项目管理列表每页 20 条；不能因统一分页而改变原有页面的读取数量。
 */
public class PageRequest {
    public static final int FIRST_PAGE = 1;

    public static final int DEFAULT_SIZE = 12;

    public static final int MAX_SIZE = 50;

    public static final int MAX_PAGE = 10_000;

    @Min(value = FIRST_PAGE, message = "页码必须大于等于 {value}")
    @Max(value = MAX_PAGE, message = "页码不能超过 {value}")
    private int page = FIRST_PAGE;

    @Min(value = 1, message = "每页条数必须大于等于 {value}")
    @Max(value = MAX_SIZE, message = "每页条数不能超过 {value}")
    private Integer size;

    public PageRequest() {
    }

    public PageRequest(int page, Integer size) {
        this.page = page;
        this.size = size;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public Integer getSize() {
        return size;
    }

    public void setSize(Integer size) {
        this.size = size;
    }

    public int sizeOrDefault() {
        return sizeOrDefault(DEFAULT_SIZE);
    }

    public int sizeOrDefault(int defaultSize) {
        return size == null ? defaultSize : size;
    }
}
