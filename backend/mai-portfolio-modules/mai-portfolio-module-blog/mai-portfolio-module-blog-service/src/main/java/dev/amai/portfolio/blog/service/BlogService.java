package dev.amai.portfolio.blog.service;

import dev.amai.portfolio.blog.entity.domain.Document;
import dev.amai.portfolio.blog.entity.request.*;
import dev.amai.portfolio.blog.entity.vo.*;

import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** 博客发布、管理权限及公开投影边界；文件格式解析和文档持久化交给各自已有能力。 */
public interface BlogService {
    /** PUBLIC 查询已发布快照，MANAGE 查询最新草稿并要求 OWNER。 */
    BlogPageVo list(String view, int page, int size);

    BlogVo get(long id);

    BlogVo publicArticle(String slug);

    /** id=null 创建草稿；更新必须携带当前锁版本，保存不会自动替换公开版本。 */
    BlogVo save(Long id, BlogRequest request);

    /** 正文与相对路径资源同步导入为草稿；未就绪资源保留供编辑，不伪装成全部成功。 */
    BlogVo importDocument(
            MultipartFile file, List<MultipartFile> resources, boolean remoteRightsConfirmed);

    BlogVo publish(long id, long version, boolean published);

    /** 完成登录、视图及下载许可校验后，返回指定已保存版本供 ExportService 使用。 */
    Document exportDocument(long id, String view);
}
