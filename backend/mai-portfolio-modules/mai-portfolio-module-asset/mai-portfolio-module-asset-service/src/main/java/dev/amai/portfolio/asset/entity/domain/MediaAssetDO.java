package dev.amai.portfolio.asset.entity.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("media_asset")
public class MediaAssetDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Integer assetType;
    private String storageKey;
    private String originalFilename;
    private String mimeType;
    private Long byteSize;
    private Integer status;
}
