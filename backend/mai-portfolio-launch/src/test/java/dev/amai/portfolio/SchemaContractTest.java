package dev.amai.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(named = "PSQL_PASSWORD", matches = ".+")
class SchemaContractTest extends AuthKeyTestSupport {
    @Autowired JdbcTemplate jdbc;

    @Test
    void allFifteenBusinessTablesUseIdentityPrimaryKeys() {
        List<String> tables = jdbc.queryForList("""
            SELECT tablename FROM pg_tables
            WHERE schemaname = current_schema() AND tablename <> 'flyway_schema_history'
            """, String.class);
        assertThat(tables).containsExactlyInAnyOrder(
            "user_account", "site_config", "project", "project_link", "tag", "media_asset",
            "project_tag", "project_media", "document", "document_version", "blog_post",
            "document_version_asset", "document_version_tag", "document_import_job",
            "document_processing_task");
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM information_schema.columns
            WHERE table_schema = current_schema() AND column_name = 'id'
              AND data_type = 'bigint' AND is_identity = 'YES'
            """, Integer.class);
        assertThat(count).isEqualTo(15);
        Integer primaryKeys = jdbc.queryForObject("""
            SELECT COUNT(*) FROM information_schema.table_constraints c
            JOIN information_schema.key_column_usage k
              ON k.constraint_schema = c.constraint_schema AND k.constraint_name = c.constraint_name
            WHERE c.table_schema = current_schema() AND c.constraint_type = 'PRIMARY KEY'
              AND k.column_name = 'id'
            """, Integer.class);
        assertThat(primaryKeys).isEqualTo(15);
        List<String> uniqueIndexes = jdbc.queryForList("""
            SELECT indexname FROM pg_indexes
            WHERE schemaname = current_schema()
              AND tablename IN ('user_account', 'tag', 'blog_post')
            """, String.class);
        assertThat(uniqueIndexes).contains("uk_user_account_username_ci", "uk_tag_slug_ci",
            "uk_tag_kind_name", "uk_blog_post_slug_ci");
    }

    @Test
    void statesAreSmallintsAndForeignKeysNeverCascade() {
        Integer nonSmallint = jdbc.queryForObject("""
            SELECT COUNT(*) FROM information_schema.columns
            WHERE table_schema = current_schema() AND table_name <> 'flyway_schema_history'
              AND column_name IN
              ('status', 'type', 'kind', 'role', 'source_type', 'origin_type',
               'asset_type', 'link_type', 'is_visible', 'is_featured',
               'allow_visitor_download', 'source_format', 'task_type', 'group_code')
              AND data_type <> 'smallint'
            """, Integer.class);
        assertThat(nonSmallint).isZero();
        Integer cascading = jdbc.queryForObject("""
            SELECT COUNT(*) FROM information_schema.referential_constraints
            WHERE constraint_schema = current_schema() AND delete_rule <> 'RESTRICT'
            """, Integer.class);
        assertThat(cascading).isZero();
        Integer foreignKeys = jdbc.queryForObject("""
            SELECT COUNT(*) FROM information_schema.referential_constraints
            WHERE constraint_schema = current_schema()
            """, Integer.class);
        assertThat(foreignKeys).isEqualTo(20);
        Integer businessChecks = jdbc.queryForObject("""
            SELECT COUNT(*) FROM pg_constraint
            WHERE connamespace = current_schema()::regnamespace AND contype = 'c'
            """, Integer.class);
        assertThat(businessChecks).isZero();
    }

    @Test
    void everyBusinessTableAndColumnHasDatabaseDescription() {
        Integer missingTableComments = jdbc.queryForObject("""
            SELECT COUNT(*) FROM pg_class c
            WHERE c.relnamespace = current_schema()::regnamespace AND c.relkind = 'r'
              AND c.relname <> 'flyway_schema_history'
              AND obj_description(c.oid, 'pg_class') IS NULL
            """, Integer.class);
        Integer missingColumnComments = jdbc.queryForObject("""
            SELECT COUNT(*) FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid
            WHERE c.relnamespace = current_schema()::regnamespace AND c.relkind = 'r'
              AND c.relname <> 'flyway_schema_history'
              AND a.attnum > 0 AND NOT a.attisdropped
              AND col_description(c.oid, a.attnum) IS NULL
            """, Integer.class);
        assertThat(missingTableComments).isZero();
        assertThat(missingColumnComments).isZero();
    }
}
