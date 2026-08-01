package com.pigpurchases.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the schema trap that adding {@code EXCLUDED_ONCE} walked into: Hibernate
 * writes a STRING enum as a native H2 {@code ENUM(...)} column pinned to the
 * constants that existed at creation time, and {@code ddl-auto=update} never widens
 * it. These tests build a column exactly as the live database has it, so they fail
 * the same way production would.
 */
class EnumColumnMigrationTest {

    /** The column as it exists in the real database, created before EXCLUDED_ONCE existed. */
    private static final String LEGACY_ENUM =
            "ENUM('EXCLUDED','MAPPED_AI','MAPPED_HINT','MAPPED_MANUAL','PARKED')";

    private DataSource freshDb(String name) {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1", "sa", "");
        ds.setDriverClassName("org.h2.Driver");
        return ds;
    }

    private void exec(DataSource ds, String sql) throws SQLException {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private String typeOf(DataSource ds, String table, String column) throws SQLException {
        try (Connection c = ds.getConnection();
             ResultSet rs = c.getMetaData().getColumns(null, null, table, column)) {
            return rs.next() ? rs.getString("TYPE_NAME") : null;
        }
    }

    @Test
    void aLegacyEnumColumnRejectsANewStatusUntilItIsMigrated() throws SQLException {
        DataSource ds = freshDb("enumguard1");
        exec(ds, "CREATE TABLE \"TRANSACTION_MAPPINGS\" (\"ID\" BIGINT, \"STATUS\" " + LEGACY_ENUM + ")");

        // This is the production failure the migration exists to prevent.
        assertThrows(SQLException.class, () ->
                        exec(ds, "INSERT INTO \"TRANSACTION_MAPPINGS\" VALUES (1, 'EXCLUDED_ONCE')"),
                "a legacy ENUM column must reject the new status — otherwise this test proves nothing");

        new EnumColumnMigration(ds).run(new DefaultApplicationArguments());

        assertTrue(typeOf(ds, "TRANSACTION_MAPPINGS", "STATUS").toUpperCase().contains("CHAR"),
                "the column should now be VARCHAR");
        assertDoesNotThrow(() -> exec(ds, "INSERT INTO \"TRANSACTION_MAPPINGS\" VALUES (1, 'EXCLUDED_ONCE')"),
                "after migrating, the new status must save");
    }

    @Test
    void existingRowsSurviveTheConversion() throws SQLException {
        DataSource ds = freshDb("enumguard2");
        exec(ds, "CREATE TABLE \"TRANSACTION_MAPPINGS\" (\"ID\" BIGINT, \"STATUS\" " + LEGACY_ENUM + ")");
        exec(ds, "INSERT INTO \"TRANSACTION_MAPPINGS\" VALUES (1, 'MAPPED_HINT'), (2, 'EXCLUDED')");

        new EnumColumnMigration(ds).run(new DefaultApplicationArguments());

        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT \"STATUS\" FROM \"TRANSACTION_MAPPINGS\" ORDER BY \"ID\"")) {
            assertTrue(rs.next());
            assertEquals("MAPPED_HINT", rs.getString(1));
            assertTrue(rs.next());
            assertEquals("EXCLUDED", rs.getString(1));
        }
    }

    @Test
    void runningTwiceIsHarmlessAndAMissingTableIsIgnored() throws SQLException {
        DataSource ds = freshDb("enumguard3");
        exec(ds, "CREATE TABLE \"TRANSACTION_MAPPINGS\" (\"ID\" BIGINT, \"STATUS\" " + LEGACY_ENUM + ")");

        EnumColumnMigration migration = new EnumColumnMigration(ds);
        migration.run(new DefaultApplicationArguments());
        // The other two tables don't exist here at all, and the first is already done:
        // a second pass must be a no-op rather than an error.
        assertDoesNotThrow(() -> migration.run(new DefaultApplicationArguments()));
        assertTrue(typeOf(ds, "TRANSACTION_MAPPINGS", "STATUS").toUpperCase().contains("CHAR"));
    }
}
