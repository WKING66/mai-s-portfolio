package dev.amai.portfolio.document.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import dev.amai.portfolio.document.api.DocumentKnowledgeSource;
import dev.amai.portfolio.document.entity.domain.DocumentContentDO;
import dev.amai.portfolio.document.entity.domain.DocumentContentTagDO;
import dev.amai.portfolio.document.entity.domain.DocumentDO;
import dev.amai.portfolio.document.enums.ContentStage;
import dev.amai.portfolio.document.mapper.DocumentContentMapper;
import dev.amai.portfolio.document.mapper.DocumentContentTagMapper;
import dev.amai.portfolio.document.mapper.DocumentMapper;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class DocumentKnowledgeSourceImpl implements DocumentKnowledgeSource {
    private final DocumentMapper documentMapper;
    private final DocumentContentMapper documentContentMapper;
    private final DocumentContentTagMapper documentContentTagMapper;

    public DocumentKnowledgeSourceImpl(DocumentMapper documentMapper, DocumentContentMapper documentContentMapper,
                                       DocumentContentTagMapper documentContentTagMapper) {
        this.documentMapper = documentMapper;
        this.documentContentMapper = documentContentMapper;
        this.documentContentTagMapper = documentContentTagMapper;
    }

    @Override
    public Access access(long id) {
        var document = documentMapper.selectById(id);
        return document == null ? null : access(document);
    }

    @Override
    public List<Access> accessSnapshot() {
        return documentMapper.selectList(Wrappers.<DocumentDO>lambdaQuery()
                .select(DocumentDO::getId, DocumentDO::getPublishStatus, DocumentDO::getVisibilityLevel,
                        DocumentDO::getAiIndexEnabled)).stream().map(this::access).toList();
    }

    private Access access(DocumentDO document) {
        return new Access(document.getId(), document.getPublishStatus(), document.getVisibilityLevel(),
                document.getAiIndexEnabled());
    }

    @Override
    public boolean lock(long id) {
        return locked(id) != null;
    }

    private DocumentDO locked(long id) {
        return documentMapper.selectOne(Wrappers.<DocumentDO>lambdaQuery()
                .eq(DocumentDO::getId, id).last("FOR UPDATE"));
    }

    @Override
    public List<Formal> formalSnapshot(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        var contents = documentContentMapper.selectList(Wrappers.<DocumentContentDO>lambdaQuery()
                .select(DocumentContentDO::getId, DocumentContentDO::getDocumentId, DocumentContentDO::getTitle,
                        DocumentContentDO::getSummary, DocumentContentDO::getContent)
                .in(DocumentContentDO::getDocumentId, ids)
                .eq(DocumentContentDO::getStage, ContentStage.PUBLISHED.code()));
        if (contents.isEmpty()) {
            return List.of();
        }
        var tags = documentContentTagMapper.selectList(Wrappers.<DocumentContentTagDO>lambdaQuery()
                .select(DocumentContentTagDO::getContentId, DocumentContentTagDO::getName)
                .in(DocumentContentTagDO::getContentId, contents.stream().map(DocumentContentDO::getId).toList()))
                .stream().collect(Collectors.groupingBy(DocumentContentTagDO::getContentId,
                        Collectors.mapping(DocumentContentTagDO::getName, Collectors.toList())));
        return contents.stream().map(content -> new Formal(content.getDocumentId(), content.getTitle(),
                content.getSummary(), content.getContent(), tags.getOrDefault(content.getId(), List.of()))).toList();
    }

    @Override
    public Formal formal(long id) {
        var content = documentContentMapper.selectOne(Wrappers.<DocumentContentDO>lambdaQuery()
                .eq(DocumentContentDO::getDocumentId, id)
                .eq(DocumentContentDO::getStage, ContentStage.PUBLISHED.code()));
        return content == null ? null : new Formal(id, content.getTitle(), content.getSummary(),
                content.getContent(), documentContentTagMapper.selectList(Wrappers.<DocumentContentTagDO>lambdaQuery()
                        .select(DocumentContentTagDO::getName)
                        .eq(DocumentContentTagDO::getContentId, content.getId()))
                        .stream().map(DocumentContentTagDO::getName).toList());
    }
}
