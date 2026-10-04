package dev.amai.portfolio.acceptance;

import com.aliyun.oss.OSSClientBuilder;
import dev.amai.portfolio.security.password.Argon2PasswordHasher;
import dev.amai.portfolio.storage.ObjectStorageWriteRequest;
import dev.amai.portfolio.storage.oss.AliyunOssObjectStorage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 本轮真实浏览器验收的数据夹具，不注册 HTTP 接口，也不进入生产运行包。
 * 独立 schema 隔离已有资料；OSS 仅新增验收对象，不删除 bucket 中的任何对象。
 */
public final class AcceptanceFixtures {
    public static final String SCHEMA = "api_acceptance_20261004";

    public static void main(String[] args) throws Exception {
        Map<String, String> settings = new HashMap<>();
        for (String line : Files.readAllLines(Path.of("backend/.env"))) {
            int equals = line.indexOf('=');
            if (equals > 0 && !line.startsWith("#")) {
                settings.put(line.substring(0, equals).trim(),
                    line.substring(equals + 1).trim().replaceAll("^[\"']|[\"']$", ""));
            }
        }
        if (args.length > 0 && args[0].equals("export-public-key")) {
            var resource = new org.springframework.core.io.DefaultResourceLoader()
                .getResource(settings.get("AUTH_RSA_PRIVATE_KEY_LOCATION"));
            String pem;
            try (var input = resource.getInputStream()) {
                pem = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII);
            }
            byte[] encoded = Base64.getMimeDecoder().decode(pem
                .replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", ""));
            var factory = java.security.KeyFactory.getInstance("RSA");
            var privateKey = (java.security.interfaces.RSAPrivateCrtKey) factory.generatePrivate(
                new java.security.spec.PKCS8EncodedKeySpec(encoded));
            var publicKey = factory.generatePublic(new java.security.spec.RSAPublicKeySpec(
                privateKey.getModulus(), privateKey.getPublicExponent()));
            Files.writeString(Path.of(".local-backups/acceptance-public-key.txt"),
                Base64.getEncoder().encodeToString(publicKey.getEncoded()));
            java.util.Arrays.fill(encoded, (byte) 0);
            System.out.println("ACCEPTANCE_PUBLIC_KEY_EXPORTED=true");
            return;
        }
        String url = "jdbc:postgresql://" + settings.getOrDefault("PSQL_HOST", "127.0.0.1")
            + ":" + settings.getOrDefault("PSQL_PORT", "5432") + "/portfolio_dev";
        try (var database = DriverManager.getConnection(url, settings.get("PSQL_USERNAME"),
                settings.get("PSQL_PASSWORD"))) {
            if (args.length > 0 && args[0].equals("isolate-owner-password")) {
                String ownerPassword = "Acceptance-owner-" + UUID.randomUUID();
                try (var statement = database.prepareStatement("UPDATE " + SCHEMA
                        + ".user_account SET password_hash=? WHERE username='owner'")) {
                    statement.setString(1, new Argon2PasswordHasher().encode(ownerPassword));
                    if (statement.executeUpdate() != 1) {
                        throw new IllegalStateException("验收站长账号数量不符合预期");
                    }
                }
                Files.writeString(Path.of(".local-backups/acceptance-20261004.env"),
                    "OWNER_PASSWORD=" + ownerPassword + "\n",
                    java.nio.file.StandardOpenOption.APPEND);
                System.out.println("ACCEPTANCE_OWNER_PASSWORD_ISOLATED=true");
                return;
            }
            if (args.length > 0 && args[0].equals("revoke-media")) {
                try (var statement = database.createStatement()) {
                    statement.executeUpdate("UPDATE " + SCHEMA
                        + ".site_config SET avatar_media_id=NULL, resume_media_id=NULL");
                }
                System.out.println("ACCEPTANCE_MEDIA_REVOKED=true");
                return;
            }
            if (args.length > 0 && args[0].equals("restore-media")) {
                try (var statement = database.createStatement()) {
                    statement.executeUpdate("UPDATE " + SCHEMA
                        + ".site_config SET avatar_media_id=1, resume_media_id=2");
                }
                System.out.println("ACCEPTANCE_MEDIA_RESTORED=true");
                return;
            }
            if (args.length > 0 && args[0].equals("inspect")) {
                try (var statement = database.createStatement();
                        var rows = statement.executeQuery("SELECT 'public' AS scope, display_name, updated_at "
                            + "FROM public.site_config UNION ALL SELECT 'acceptance', display_name, updated_at FROM "
                            + SCHEMA + ".site_config")) {
                    while (rows.next()) {
                        System.out.println("ACCEPTANCE_PROFILE_STATE=" + rows.getString(1)
                            + "," + rows.getString(2) + "," + rows.getTimestamp(3));
                    }
                }
                return;
            }
            if (args.length > 0 && args[0].equals("replace-resume")) {
                byte[] content = resumePdf();
                String key = "acceptance/2026-10-04/" + UUID.randomUUID() + "/resume.pdf";
                var client = new OSSClientBuilder().build(settings.get("OSS_ENDPOINT"),
                    settings.get("OSS_ACCESS_KEY_ID"), settings.get("OSS_ACCESS_KEY_SECRET"));
                try {
                    new AliyunOssObjectStorage(client, settings.get("OSS_BUCKET_NAME"))
                        .store(new ObjectStorageWriteRequest(key,
                            new ByteArrayInputStream(content), content.length, "application/pdf"));
                    try (var statement = database.prepareStatement("UPDATE " + SCHEMA
                            + ".media_asset SET storage_key=?,byte_size=? WHERE id=2")) {
                        statement.setString(1, key);
                        statement.setLong(2, content.length);
                        if (statement.executeUpdate() != 1) {
                            throw new IllegalStateException("验收简历资源数量不符合预期");
                        }
                    }
                } finally {
                    client.shutdown();
                }
                System.out.println("ACCEPTANCE_RESUME_KEY=" + key);
                return;
            }
            database.setAutoCommit(false);
            try (var statement = database.createStatement()) {
                // 不使用 IF NOT EXISTS：拒绝覆盖已有验收环境或其他人的状态。
                statement.execute("CREATE SCHEMA " + SCHEMA);
                statement.execute("SET LOCAL search_path TO " + SCHEMA);
                statement.execute(Files.readString(Path.of(
                    "backend/mai-portfolio-launch/src/main/resources/db/postgresql/V1__portfolio_and_document_core.sql")));
                statement.executeUpdate("INSERT INTO site_config (display_name, headline, intro, github_url, email, seo_title, seo_description, updated_at) "
                    + "SELECT display_name, headline, intro, github_url, email, seo_title, seo_description, updated_at FROM public.site_config");
                statement.executeUpdate("INSERT INTO tag (kind,name,normalized_name,slug,group_code,logo_key,is_featured,sort_order) "
                    + "SELECT kind,name,normalized_name,slug,group_code,logo_key,is_featured,sort_order FROM public.tag");
            }
            String normalPassword = "Acceptance-" + UUID.randomUUID();
            String ownerPassword = "Acceptance-owner-" + UUID.randomUUID();
            var hasher = new Argon2PasswordHasher();
            try (var insert = database.prepareStatement("INSERT INTO user_account "
                    + "(username,type,password_hash,status,created_at,updated_at) VALUES (?,?,?,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")) {
                for (String username : new String[] {"owner", "acceptance_normal"}) {
                    insert.setString(1, username);
                    insert.setInt(2, username.equals("owner") ? 0 : 1);
                    insert.setString(3, hasher.encode(username.equals("owner")
                        ? ownerPassword : normalPassword));
                    insert.executeUpdate();
                }
            }
            byte[] image = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a5ioAAAAASUVORK5CYII=");
            byte[] resume = resumePdf();
            String prefix = "acceptance/2026-10-04/" + UUID.randomUUID() + "/";
            var client = new OSSClientBuilder().build(settings.get("OSS_ENDPOINT"),
                settings.get("OSS_ACCESS_KEY_ID"), settings.get("OSS_ACCESS_KEY_SECRET"));
            try {
                var storage = new AliyunOssObjectStorage(client, settings.get("OSS_BUCKET_NAME"));
                try (var insert = database.prepareStatement("INSERT INTO media_asset "
                        + "(asset_type,storage_key,source_type,original_filename,mime_type,byte_size,status,created_at,updated_at) "
                        + "VALUES (?,?,0,?,?,?,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")) {
                    for (String name : new String[] {"avatar.png", "resume.pdf", "private.png"}) {
                        boolean document = name.endsWith("pdf");
                        byte[] content = document ? resume : image;
                        String type = document ? "application/pdf" : "image/png";
                        storage.store(new ObjectStorageWriteRequest(prefix + name,
                            new ByteArrayInputStream(content), content.length, type));
                        insert.setInt(1, document ? 1 : 0);
                        insert.setString(2, prefix + name);
                        insert.setString(3, name);
                        insert.setString(4, type);
                        insert.setLong(5, content.length);
                        insert.executeUpdate();
                    }
                }
            } finally {
                client.shutdown();
            }
            try (var statement = database.createStatement()) {
                statement.executeUpdate("UPDATE site_config SET avatar_media_id=1,resume_media_id=2");
            }
            database.commit();
            Path localSecrets = Path.of(".local-backups/acceptance-20261004.env");
            Files.createDirectories(localSecrets.getParent());
            Files.writeString(localSecrets, "NORMAL_PASSWORD=" + normalPassword + "\n"
                + "OWNER_PASSWORD=" + ownerPassword + "\n");
            System.out.println("ACCEPTANCE_SCHEMA=" + SCHEMA);
            System.out.println("ACCEPTANCE_OSS_PREFIX=" + prefix);
            System.out.println("ACCEPTANCE_MEDIA_IDS=1,2,3");
        }
    }

    /** 有效单页 PDF，仅承载验收文字，不包含真实简历或个人隐私。 */
    private static byte[] resumePdf() {
        String content = "BT /F1 18 Tf 72 740 Td (Portfolio acceptance resume) Tj ET\n";
        String[] objects = {
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] "
                + "/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            "<< /Length " + content.length() + " >>\nstream\n" + content + "endstream"
        };
        var pdf = new StringBuilder("%PDF-1.4\n");
        int[] offsets = new int[objects.length];
        for (int index = 0; index < objects.length; index++) {
            offsets[index] = pdf.length();
            pdf.append(index + 1).append(" 0 obj\n").append(objects[index]).append("\nendobj\n");
        }
        int xref = pdf.length();
        pdf.append("xref\n0 6\n0000000000 65535 f \n");
        for (int offset : offsets) {
            pdf.append(String.format(java.util.Locale.ROOT, "%010d 00000 n \n", offset));
        }
        pdf.append("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n")
            .append(xref).append("\n%%EOF\n");
        return pdf.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }
}
