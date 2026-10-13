-- 用途与上传者成为明确元数据；路径只在这次历史迁移中使用，不参与运行时业务判断。
ALTER TABLE media_asset ADD COLUMN usage VARCHAR(32);
ALTER TABLE media_asset ADD COLUMN uploader_account_id BIGINT;

UPDATE media_asset SET usage = CASE
    WHEN asset_type = 0 AND storage_key LIKE 'account/avatars/%' THEN 'AVATAR'
    WHEN asset_type = 0 THEN 'IMAGE'
    ELSE 'ATTACHMENT'
END;

UPDATE media_asset AS asset SET uploader_account_id = profile.account_id
FROM (SELECT avatar_media_id, MIN(account_id) AS account_id FROM user_profile
    WHERE avatar_media_id IS NOT NULL GROUP BY avatar_media_id HAVING COUNT(DISTINCT account_id) = 1) AS profile
WHERE profile.avatar_media_id = asset.id;

ALTER TABLE media_asset ALTER COLUMN usage SET NOT NULL;
ALTER TABLE media_asset ADD CONSTRAINT fk_asset_uploader FOREIGN KEY (uploader_account_id)
    REFERENCES user_account(id) ON DELETE RESTRICT;
COMMENT ON COLUMN media_asset.usage IS '上传用途：AVATAR、IMAGE、ATTACHMENT；封面、截图和博客插图共用 IMAGE';
COMMENT ON COLUMN media_asset.uploader_account_id IS '上传时的有效账号；历史无法确定归属的资产保留 NULL，不允许通过新接口绑定为本人头像';
COMMENT ON COLUMN media_asset.asset_type IS '资源类型：0=图片、1=文档、2=普通附件';
