package dev.amai.portfolio.blog.service;

import dev.amai.portfolio.blog.entity.domain.Document;
import dev.amai.portfolio.blog.entity.request.*;
import dev.amai.portfolio.blog.entity.vo.*;
import dev.amai.portfolio.blog.enums.BlogAction;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

/** 博客权限、可见性与文档流程编排，不重复实现文件上传或正文持久化。 */
public interface BlogService {
    BlogPageVo list(String view, int page, int size);

    BlogVo get(long id);

    BlogVo publicArticle(String slug);

    BlogVo save(Long id, BlogRequest request);

    BlogVo importDocument(MultipartFile file, List<MultipartFile> resources,
            boolean remoteRightsConfirmed, List<Long> tagIds, String requestId);

    BlogVo transition(long id, BlogRevisionRequest request, BlogAction action);

    void delete(long id, BlogRevisionRequest request);

    Document exportDocument(long id, String view);
}
