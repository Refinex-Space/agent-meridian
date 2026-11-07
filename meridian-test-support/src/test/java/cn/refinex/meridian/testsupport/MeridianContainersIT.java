package cn.refinex.meridian.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

class MeridianContainersIT {

    @Test
    void postgresImageHasChineseSearchAndVector() throws SQLException {
        try (Connection connection = TestDatabase.appDataSource().getConnection();
                Statement statement = connection.createStatement()) {
            assertThat(queryString(statement, "SELECT to_tsvector('chinese', '差旅住宿标准')::text"))
                    .contains("'差旅'", "'住宿'", "'标准'");
            assertThat(queryString(statement, "SELECT ('[1,2,3]'::vector <-> '[1,2,4]'::vector)::text"))
                    .isEqualTo("1");
        }
    }

    @Test
    void migrateFollowsConventionAndAppRoleHasOnlyDml() throws SQLException {
        TestDatabase.migrate("rt");
        TestDatabase.truncate("rt");

        try (Connection connection = TestDatabase.appDataSource().getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO rt.fixture_probes (id, body) VALUES ('p1', 'x')");
            assertThat(queryString(statement, "SELECT count(*)::text FROM rt.fixture_probes"))
                    .isEqualTo("1");
            assertThatThrownBy(() -> statement.execute("CREATE TABLE rt.forbidden (id int)"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
        }

        TestDatabase.truncate("rt");
        try (Connection connection = TestDatabase.appDataSource().getConnection();
                Statement statement = connection.createStatement()) {
            assertThat(queryString(statement, "SELECT count(*)::text FROM rt.fixture_probes"))
                    .isEqualTo("0");
        }
    }

    @Test
    void redisAndMinioAreReachable() throws Exception {
        assertThat(MeridianContainers.redis()
                        .execInContainer("redis-cli", "ping")
                        .getStdout())
                .contains("PONG");
        assertThat(MeridianContainers.minio()
                        .execInContainer("sh", "-c", "mc ls local/")
                        .getStdout())
                .contains(MeridianContainers.MINIO_BUCKET);
    }

    private static String queryString(Statement statement, String sql) throws SQLException {
        try (ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
