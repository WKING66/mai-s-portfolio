-- SEO 由各页面维护，公开站点资料接口不读取这两个旧字段。
ALTER TABLE site_config
    DROP COLUMN seo_title,
    DROP COLUMN seo_description;
