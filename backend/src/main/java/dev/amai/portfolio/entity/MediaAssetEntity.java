package dev.amai.portfolio.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("media_asset")
public class MediaAssetEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Integer assetType;
    private Integer status;
}
