package dev.amai.portfolio.document.entity.domain;

import lombok.Getter;
import lombok.Setter;

/** 正式正文的联合查询投影，供调用方在同一事务中补充资源和标签。 */
@Getter
@Setter
public class PublishedDocumentData {
    private long id;

    private long contentId;

    private long revision;

    private int stage;

    private String title;

    private String summary;

    private String content;
}
