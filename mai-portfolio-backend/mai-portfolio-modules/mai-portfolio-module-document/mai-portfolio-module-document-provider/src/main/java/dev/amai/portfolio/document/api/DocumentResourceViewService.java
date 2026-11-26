package dev.amai.portfolio.document.api;

import dev.amai.portfolio.document.entity.domain.Document;
import dev.amai.portfolio.document.entity.vo.DocumentResourceVo;
import java.util.List;

/** 为正文资源生成页面投影。 */
public interface DocumentResourceViewService {
    List<DocumentResourceVo> resources(Document document, boolean manage);

    /** 管理预览暂不可用时返回既有资源状态，不生成签名地址。 */
    List<DocumentResourceVo> resourcesWithoutPreview(Document document);
}
