package dev.amai.portfolio.common;

import java.util.List;

/** 项目和博客都用视图区分公开与管理投影，因此共用这一扩展，不各造一套分页响应。 */
public final class ViewPageVO<T> extends PageVO<T> {
    private final String view;

    public ViewPageVO(String view, int page, int size, long total, List<T> items) {
        super(page, size, total, items);
        this.view = view;
    }

    public String getView() {
        return view;
    }
}
