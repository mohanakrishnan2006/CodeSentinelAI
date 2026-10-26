package com.codesentinel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("dev")
class PostgresConnectionTest {

    private static final Logger log = LoggerFactory.getLogger(PostgresConnectionTest.class);

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("Verify Spring Boot can connect and execute queries against PostgreSQL")
    void testPostgresConnection() throws Exception {
        assertNotNull(dataSource, "DataSource bean must be configured");

        try (Connection connection = dataSource.getConnection()) {
            assertNotNull(connection, "DataSource should provide a valid connection");
            assertTrue(connection.isValid(3), "Connection to PostgreSQL should be valid within timeout");

            DatabaseMetaData metaData = connection.getMetaData();
            String productName = metaData.getDatabaseProductName();
            String productVersion = metaData.getDatabaseProductVersion();
            String catalog = connection.getCatalog();

            log.info("==================================================================");
            log.info("SPRING BOOT TO POSTGRESQL CONNECTION VERIFIED SUCCESSFULLY");
            log.info("Database Product: {}", productName);
            log.info("Product Version:  {}", productVersion);
            log.info("Database Name:    {}", catalog);
            log.info("Driver Name:      {}", metaData.getDriverName());
            log.info("Driver Version:   {}", metaData.getDriverVersion());
            log.info("==================================================================");

            assertEquals("PostgreSQL", productName, "Connected database must be PostgreSQL");
            assertEquals("codesentinel_db", catalog, "Connected database catalog must be codesentinel_db");

            try (Statement statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery("SELECT 1 AS ping, current_database(), current_user")) {
                assertTrue(rs.next(), "Query should return at least one row");
                assertEquals(1, rs.getInt("ping"), "Ping query should return 1");
                assertEquals("codesentinel_db", rs.getString("current_database"));
                assertEquals("codesentinel_user", rs.getString("current_user"));
            }
        }
    }
}
