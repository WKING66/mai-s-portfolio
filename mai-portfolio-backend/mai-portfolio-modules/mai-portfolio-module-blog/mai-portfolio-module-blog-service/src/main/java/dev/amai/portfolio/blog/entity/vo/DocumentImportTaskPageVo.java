package dev.amai.portfolio.blog.entity.vo;

import java.util.List;

/** 导入记录分页；支持继续向前翻阅，不把超过固定条数的记录隐藏起来。 */
public record DocumentImportTaskPageVo(
        int page,

        int size,

        long total,

        List<DocumentImportTaskVo> items) {}
