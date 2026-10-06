package dev.amai.portfolio.system.constant;

/** 个人中心提示集中维护；密码/文件原文从不放入错误消息。 */
public final class AccountMessageConstants {
    public static final String NICKNAME_INVALID = "昵称最多 64 个字符，不能包含控制字符";
    public static final String CURRENT_PASSWORD_INVALID = "当前密码不正确";
    public static final String PASSWORD_UNCHANGED = "新密码不能与当前密码相同";
    public static final String ACCOUNT_WRITE_CONFLICT = "账号资料已更新，请重新操作";
    public static final String AVATAR_NOT_FOUND = "尚未上传头像或头像暂不可用";
    public static final String AVATAR_TOO_LARGE = "头像图片不能超过 2 MiB";
    public static final String AVATAR_READ_FAILED = "头像文件读取失败，请重新选择";
    public static final String PASSWORD_TRANSACTION_REQUIRED = "Password changes require an active transaction";

    private AccountMessageConstants() {
    }
}
