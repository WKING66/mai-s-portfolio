package dev.amai.portfolio.common;

import jakarta.validation.constraints.Size;

/**
 * 博客与项目共用的列表查询输入；只承载筛选值，视图权限和领域状态由各业务判断。
 * 分页仍继承 PageRequest，不能为两个列表复制 page/size 的默认值与校验。
 */
public class ContentListRequest extends PageRequest {
    public static final int MAX_KEYWORD_LENGTH = 200;

    private String view;

    private String status;

    @Size(max = MAX_KEYWORD_LENGTH, message = "搜索关键词不能超过 {max} 个字符")
    private String keyword;

    public ContentListRequest() {
    }

    public ContentListRequest(String view, int page, Integer size, String status) {
        super(page, size);
        this.view = view;
        this.status = status;
    }

    public String getView() {
        return view;
    }

    public void setView(String view) {
        this.view = view;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getKeyword() {
        return keyword;
    }

    /** 输入边界统一去除首尾空白，空白关键词等同未搜索；不在这里拼 SQL 或决定公开权限。 */
    public void setKeyword(String keyword) {
        this.keyword = keyword == null || keyword.isBlank() ? null : keyword.strip();
    }
}
