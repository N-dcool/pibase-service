package com.pibase.pibase_api.entity;

import lombok.Getter;

import java.util.Arrays;
import java.util.List;

@Getter
public enum DatabaseEngine {

    POSTGRESQL(
            "postgresql",
            "15",
            5432,
            "/var/lib/postgresql/data",
            "jdbc:postgresql://%s:%d/%s",
            "postgresql://%s:%s@%s:%d/%s",
            """
                    SELECT t.table_name, c.column_name, c.data_type, c.is_nullable
                    FROM information_schema.tables t
                    JOIN information_schema.columns c
                        ON t.table_name = c.table_name AND t.table_schema = c.table_schema
                    WHERE t.table_schema = 'public' AND t.table_type = 'BASE TABLE'
                    ORDER BY t.table_name, c.ordinal_position
                    """
    ) {
        @Override
        public List<String> buildEnvVars(String dbName, String user, String password) {
            return List.of(
                    "POSTGRES_DB=" + dbName,
                    "POSTGRES_USER=" + user,
                    "POSTGRES_PASSWORD=" + password
            );
        }
    },
    MYSQL(
            "mysql",
            "8.0",
            3306,
            "/var/lib/mysql",
            "jdbc:mysql://%s:%d/%s",
            "mysql://%s:%s@%s:%d/%s",
            """
                    SELECT t.table_name, c.column_name, c.data_type, c.is_nullable
                    FROM information_schema.tables t
                    JOIN information_schema.columns c
                        ON t.table_name = c.table_name AND t.table_schema = c.table_schema
                    WHERE t.table_schema = DATABASE() AND t.table_type = 'BASE TABLE'
                    ORDER BY t.table_name, c.ordinal_position
                    """
    ) {
        @Override
        public List<String> buildEnvVars(String dbName, String user, String password) {
            return List.of(
                    "MYSQL_DATABASE=" + dbName,
                    "MYSQL_USER=" + user,
                    "MYSQL_PASSWORD=" + password,
                    "MYSQL_ROOT_PASSWORD=" + password
            );
        }
    };

    private final String id;
    private final String defaulVersion;
    private final int containerPort;
    private final String dataDir;
    private final String jdbcUrlPattern;
    private final String directUriPattern;
    private final String schemaSql;

    DatabaseEngine(String id, String defaulVersion, int containerPort, String dataDir, String jdbcUrlPattern, String directUriPattern, String schemaSql) {
        this.id = id;
        this.defaulVersion = defaulVersion;
        this.containerPort = containerPort;
        this.dataDir = dataDir;
        this.jdbcUrlPattern = jdbcUrlPattern;
        this.directUriPattern = directUriPattern;
        this.schemaSql = schemaSql;
    }

    // Lookup
    public static DatabaseEngine fromId(String id) {
        return Arrays.stream(values())
                .filter(e -> e.id.equalsIgnoreCase(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported engine: " + id));
    }

    // Builder

    public String buildJdbcUrl(String host, int port, String dbName) {
        return String.format(jdbcUrlPattern, host, port, dbName);
    }

    public String buildReadinessJdbcUrl(String host, int port) {
        String defaultDb = this == POSTGRESQL ? "postgres" : id;
        return String.format(jdbcUrlPattern, host, port, defaultDb);
    }

    public String buildDirectUri(String user, String password, String host, int port, String dbName) {
        return String.format(directUriPattern, user, password, host, port, dbName);
    }

    public String buildSniUri(String user, String password, String sniHostname, String dbName) {
        String schema = this == POSTGRESQL ? "postgresql" : "mysql";
        String sqlParam = this == POSTGRESQL ? "?sslmode=require&sslnegotiation=direct" : "?useSSL=true";
        return String.format("%s://%s:%s@%s.db.nareshchoudhary.com:5432/%s%s", schema, user, password, sniHostname, dbName, sqlParam);
    }

    public abstract List<String> buildEnvVars(String dbName, String user, String password);

}
