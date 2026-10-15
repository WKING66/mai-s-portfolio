package dev.amai.portfolio.asset.entity.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("media_asset")
public class MediaAssetDO {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Integer assetType;

    private Integer sourceType;

    private String usage;

    private Long uploaderAccountId;

    private String storageKey;

    private String originalFilename;

    private String mimeType;

    private Long byteSize;

    private byte[] sha256;

    private Integer width;

    private Integer height;

    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
