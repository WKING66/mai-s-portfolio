import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One-time local development migration. Source and target must both be named portfolio_dev.
 * It refuses non-empty target tables and source data not yet covered by this migration.
 * Password hashes are copied verbatim and never logged.
 */
public final class MigratePortfolioDev {
    private static final List<String> SOURCE_TABLES = List.of(
            "user_account", "media_asset", "site_config", "tag", "project", "article",
            "project_link", "article_import_job", "article_tag", "project_tag",
            "article_media", "project_media");
    private static final List<String> TARGET_TABLES = List.of(
            "user_account", "media_asset", "site_config", "tag", "project", "project_link",
            "project_tag", "project_media", "document", "document_version", "blog_post",
            "document_version_asset", "document_version_tag", "document_import_job",
            "document_processing_task");
    private static final Map<String, String> COPY_COLUMNS = Map.of(
            "user_account", "id, username, type, password_hash, status, created_at, updated_at",
            "site_config", "id, display_name, headline, intro, github_url, email, avatar_media_id, "
                    + "resume_media_id, seo_title, seo_description, updated_at",
            "tag", "id, kind, name, normalized_name, slug, group_code, logo_key, "
                    + "is_featured, sort_order");
    private static final Set<String> SMALLINT_COLUMNS = Set.of("type", "status", "kind", "group_code", "is_featured");

    private MigratePortfolioDev() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !("--migrate-reviewed-snapshot".equals(args[0])
                || "--verify-current-data".equals(args[0]))) {
            throw new IllegalArgumentException("Explicit migration or verification flag is required");
        }
        String mysqlUrl = "jdbc:mysql://" + required("MYSQL_HOST") + ":" + required("MYSQL_PORT")
                + "/portfolio_dev?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai";
        String pgUrl = "jdbc:postgresql://" + required("PSQL_HOST") + ":" + required("PSQL_PORT")
                + "/portfolio_dev";
        try (Connection source = DriverManager.getConnection(mysqlUrl,
                required("MYSQL_USERNAME"), required("MYSQL_PASSWORD"));
             Connection target = DriverManager.getConnection(pgUrl,
                     required("PSQL_USERNAME"), required("PSQL_PASSWORD"))) {
            verifyDatabaseNames(source, target);
            source.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            source.setReadOnly(true);
            source.setAutoCommit(false);
            if ("--verify-current-data".equals(args[0])) {
                target.setReadOnly(true);
                verifyCurrentData(source, target);
                System.out.println("sourceAndTargetMatch=true");
                return;
            }
            target.setAutoCommit(false);
            try {
                for (String table : SOURCE_TABLES) {
                    if (!COPY_COLUMNS.containsKey(table) && count(source, table) != 0) {
                        throw new IllegalStateException("Source table requires a reviewed mapping: " + table);
                    }
                }
                for (String table : TARGET_TABLES) {
                    if (count(target, table) != 0) {
                        throw new IllegalStateException("Target table is not empty: " + table);
                    }
                }
                for (String table : List.of("user_account", "tag", "site_config")) {
                    copyTable(source, target, table, COPY_COLUMNS.get(table));
                    if (count(source, table) != count(target, table)) {
                        throw new IllegalStateException("Row count differs after copying: " + table);
                    }
                    advanceIdentity(target, table);
                }
                verifyPasswordHashes(source, target);
                target.commit();
                source.commit();
                System.out.println("migrationCommitted=true");
                for (String table : List.of("user_account", "site_config", "tag")) {
                    System.out.println(table + "=" + count(target, table));
                }
            } catch (Exception failure) {
                target.rollback();
                source.rollback();
                throw failure;
            }
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing environment variable: " + name);
        }
        return value;
    }

    private static void verifyDatabaseNames(Connection source, Connection target) throws Exception {
        try (Statement sourceStatement = source.createStatement();
             ResultSet sourceResult = sourceStatement.executeQuery("SELECT DATABASE()");
             Statement targetStatement = target.createStatement();
             ResultSet targetResult = targetStatement.executeQuery("SELECT current_database()")) {
            sourceResult.next();
            targetResult.next();
            if (!"portfolio_dev".equals(sourceResult.getString(1))
                    || !"portfolio_dev".equals(targetResult.getString(1))) {
                throw new IllegalStateException("Migration only permits portfolio_dev to portfolio_dev");
            }
        }
    }

    private static long count(Connection connection, String table) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT count(*) FROM " + table)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static void copyTable(Connection source, Connection target, String table, String columns) throws Exception {
        String[] names = columns.split(", ");
        String placeholders = String.join(", ", java.util.Collections.nCopies(names.length, "?"));
        try (Statement query = source.createStatement();
             ResultSet rows = query.executeQuery("SELECT " + columns + " FROM " + table + " ORDER BY id");
             PreparedStatement insert = target.prepareStatement(
                     "INSERT INTO " + table + " (" + columns + ") VALUES (" + placeholders + ")")) {
            ResultSetMetaData metadata = rows.getMetaData();
            while (rows.next()) {
                for (int index = 1; index <= metadata.getColumnCount(); index++) {
                    Object value = rows.getObject(index);
                    if (value == null) {
                        insert.setObject(index, null);
                    } else if (SMALLINT_COLUMNS.contains(names[index - 1])) {
                        insert.setShort(index, rows.getShort(index));
                    } else if (value instanceof java.sql.Timestamp timestamp) {
                        insert.setObject(index, timestamp.toLocalDateTime());
                    } else if (value instanceof LocalDateTime localDateTime) {
                        insert.setObject(index, localDateTime);
                    } else {
                        insert.setObject(index, value);
                    }
                }
                insert.executeUpdate();
            }
        }
    }

    private static void advanceIdentity(Connection connection, String table) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT max(id) FROM " + table)) {
            rows.next();
            long maximum = rows.getLong(1);
            if (maximum > 0) {
                try (PreparedStatement sequence = connection.prepareStatement(
                        "SELECT setval(pg_get_serial_sequence(?, 'id'), ?, true)")) {
                    sequence.setString(1, table);
                    sequence.setLong(2, maximum);
                    sequence.executeQuery().close();
                }
            }
        }
    }

    private static void verifyPasswordHashes(Connection source, Connection target) throws Exception {
        try (Statement sourceStatement = source.createStatement();
             ResultSet sourceRows = sourceStatement.executeQuery("SELECT id, password_hash FROM user_account ORDER BY id");
             Statement targetStatement = target.createStatement();
             ResultSet targetRows = targetStatement.executeQuery("SELECT id, password_hash FROM user_account ORDER BY id")) {
            while (sourceRows.next()) {
                if (!targetRows.next() || sourceRows.getLong(1) != targetRows.getLong(1)
                        || !sourceRows.getString(2).equals(targetRows.getString(2))) {
                    throw new IllegalStateException("Account password hash verification failed");
                }
            }
            if (targetRows.next()) {
                throw new IllegalStateException("Unexpected account in PostgreSQL target");
            }
        }
    }

    private static void verifyCurrentData(Connection source, Connection target) throws Exception {
        for (String table : SOURCE_TABLES) {
            if (!COPY_COLUMNS.containsKey(table) && count(source, table) != 0) {
                throw new IllegalStateException("Unmapped source table contains data: " + table);
            }
        }
        for (String table : TARGET_TABLES) {
            if (!COPY_COLUMNS.containsKey(table) && count(target, table) != 0) {
                throw new IllegalStateException("Unexpected target data in table: " + table);
            }
        }
        for (String table : List.of("user_account", "site_config", "tag")) {
            String columns = COPY_COLUMNS.get(table);
            try (Statement sourceStatement = source.createStatement();
                 ResultSet sourceRows = sourceStatement.executeQuery(
                         "SELECT " + columns + " FROM " + table + " ORDER BY id");
                 Statement targetStatement = target.createStatement();
                 ResultSet targetRows = targetStatement.executeQuery(
                         "SELECT " + columns + " FROM " + table + " ORDER BY id")) {
                int columnCount = sourceRows.getMetaData().getColumnCount();
                while (sourceRows.next()) {
                    if (!targetRows.next()) {
                        throw new IllegalStateException("Missing target row in " + table);
                    }
                    for (int index = 1; index <= columnCount; index++) {
                        String column = sourceRows.getMetaData().getColumnName(index);
                        boolean sameValue = column.endsWith("_at")
                                ? java.util.Objects.equals(
                                        sourceRows.getObject(index, LocalDateTime.class),
                                        targetRows.getObject(index, LocalDateTime.class))
                                : java.util.Objects.equals(sourceRows.getString(index), targetRows.getString(index));
                        if (!sameValue) {
                            String timeDetail = column.endsWith("_at")
                                    ? " source=" + sourceRows.getString(index) + " target=" + targetRows.getString(index)
                                    : "";
                            throw new IllegalStateException("Source and target differ in " + table
                                    + "." + column + timeDetail);
                        }
                    }
                }
                if (targetRows.next()) {
                    throw new IllegalStateException("Extra target row in " + table);
                }
            }
        }
    }
}
