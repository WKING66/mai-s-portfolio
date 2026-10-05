package dev.amai.portfolio.acceptance;

import dev.amai.portfolio.security.password.Argon2PasswordHasher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 真实项目联调的隔离夹具；不注册接口、不进入运行包、不改 public schema 或 OSS。 */
public final class ProjectAcceptanceFixtures {
    public static final String SCHEMA = "project_acceptance_20261005";

    public static void main(String[] args) throws Exception {
        Map<String, String> settings = new HashMap<>();
        for (String line : Files.readAllLines(Path.of("backend/.env"))) {
            int equals = line.indexOf('=');
            if (equals > 0 && !line.startsWith("#")) {
                settings.put(line.substring(0, equals).trim(),
                    line.substring(equals + 1).trim().replaceAll("^[\\\"']|[\\\"']$", ""));
            }
        }
        String url = "jdbc:postgresql://" + settings.getOrDefault("PSQL_HOST", "127.0.0.1")
            + ":" + settings.getOrDefault("PSQL_PORT", "5432") + "/portfolio_dev";
        try (var connection = DriverManager.getConnection(url, settings.get("PSQL_USERNAME"),
                settings.get("PSQL_PASSWORD"))) {
            if (args.length > 0 && !args[0].equals("init")) {
                String change = switch (args[0]) {
                    case "disable-owner" -> "status=0";
                    case "enable-owner" -> "status=1";
                    case "demote-owner" -> "type=1";
                    case "restore-owner" -> "type=0,status=1";
                    default -> throw new IllegalArgumentException("Unsupported fixture operation");
                };
                try (var statement = connection.createStatement()) {
                    if (statement.executeUpdate("UPDATE " + SCHEMA
                            + ".user_account SET " + change + " WHERE username='project_owner'") != 1) {
                        throw new IllegalStateException("Fixture owner not found");
                    }
                }
                System.out.println("PROJECT_FIXTURE_STATE_CHANGED=true");
                return;
            }
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA " + SCHEMA);
                statement.execute("SET LOCAL search_path TO " + SCHEMA);
                statement.execute(Files.readString(Path.of(
                    "backend/mai-portfolio-launch/src/main/resources/db/postgresql/V1__portfolio_and_document_core.sql")));
                statement.executeUpdate("INSERT INTO site_config (display_name,headline,intro,github_url,email,seo_title,seo_description,updated_at) "
                    + "SELECT display_name,headline,intro,github_url,email,seo_title,seo_description,updated_at FROM public.site_config");
                statement.executeUpdate("INSERT INTO tag (kind,name,normalized_name,slug,group_code,logo_key,is_featured,sort_order) "
                    + "SELECT kind,name,normalized_name,slug,group_code,logo_key,is_featured,sort_order FROM public.tag");
            }
            String owner = "Project-owner-" + UUID.randomUUID();
            String normal = "Project-normal-" + UUID.randomUUID();
            var hasher = new Argon2PasswordHasher();
            try (var statement = connection.prepareStatement("INSERT INTO user_account "
                    + "(username,type,password_hash,status,created_at,updated_at) VALUES (?,?,?,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")) {
                for (String name : new String[] {"project_owner", "project_normal"}) {
                    statement.setString(1, name);
                    statement.setInt(2, name.equals("project_owner") ? 0 : 1);
                    statement.setString(3, hasher.encode(name.equals("project_owner") ? owner : normal));
                    statement.executeUpdate();
                }
            }
            connection.commit();
            Path file = Path.of(".local-backups/project-acceptance.env");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "OWNER_PASSWORD=" + owner + "\nNORMAL_PASSWORD=" + normal + "\n");
            System.out.println("PROJECT_ACCEPTANCE_SCHEMA=" + SCHEMA);
        }
    }
}
