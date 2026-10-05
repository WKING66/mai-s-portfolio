package dev.amai.portfolio.portfolio.constant;

/** 项目提示集中管理；不回显 SQL、内部对象键或异常详情。 */
public final class ProjectMessageConstants {
    public static final String NOT_FOUND = "项目不存在";
    public static final String INVALID_QUERY = "项目视图、状态或分页参数不正确";
    public static final String INVALID_FIELDS = "项目字段、长度、排序或版本号不正确";
    public static final String INVALID_SLUG = "项目标识仅支持小写字母、数字和连字符，最长 160 字符";
    public static final String SLUG_CONFLICT = "项目标识已被使用";
    public static final String SLUG_IMMUTABLE = "曾发布项目的标识不能修改";
    public static final String INVALID_TAGS = "请选择有效且不重复的技术标签";
    public static final String INVALID_LINKS = "外链类型、HTTP(S) 地址、长度或重复关联不正确";
    public static final String PUBLISH_REQUIRED = "发布需填写标题、摘要、本人贡献和至少一个技术标签";
    public static final String CONFLICT = "项目已被其他操作更新，请重新读取后提交";
    private ProjectMessageConstants() { }
}
