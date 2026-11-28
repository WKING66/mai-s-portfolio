package dev.amai.portfolio.blog.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import dev.amai.portfolio.blog.api.BlogKnowledgeSource;
import dev.amai.portfolio.blog.entity.domain.BlogPostDO;
import dev.amai.portfolio.blog.mapper.BlogPostMapper;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class BlogKnowledgeSourceImpl implements BlogKnowledgeSource {
    private final BlogPostMapper blogPostMapper;

    public BlogKnowledgeSourceImpl(BlogPostMapper blogPostMapper) {
        this.blogPostMapper = blogPostMapper;
    }

    @Override
    public Metadata metadata(long documentId) {
        var row = blogPostMapper.selectOne(Wrappers.<BlogPostDO>lambdaQuery().eq(BlogPostDO::getDocumentId, documentId));
        return row == null ? null : metadata(row);
    }

    @Override
    public List<Metadata> metadataSnapshot() {
        return blogPostMapper.selectList(Wrappers.<BlogPostDO>lambdaQuery()
                .select(BlogPostDO::getDocumentId, BlogPostDO::getSlug, BlogPostDO::getPublishedAt))
                .stream()
                .map(this::metadata)
                .toList();
    }

    private Metadata metadata(BlogPostDO row) {
        return new Metadata(row.getDocumentId(), row.getSlug(),
                row.getPublishedAt() == null ? null : row.getPublishedAt().toInstant(ZoneOffset.UTC));
    }
}
