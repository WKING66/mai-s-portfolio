package dev.amai.portfolio.blog.entity.vo;

import java.util.List;

/** view 明确摘要来源，page/size 表示本次已成功查询的页；不是额外的业务分类。 */
public record BlogPageVo(
        String view,

        int page,

        int size,

        long total,

        List<BlogCardVo> items) {}
