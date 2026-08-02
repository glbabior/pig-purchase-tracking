package com.pigpurchases.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Converts {@code @Enumerated(EnumType.STRING)} columns from H2's native
 * {@code ENUM(...)} type to plain {@code VARCHAR}.
 *
 * <p><b>Why this exists.</b> Hibernate 6 maps a STRING enum to a native H2
 * {@code ENUM} column listing exactly the constants that existed when the table
 * was created — e.g.
 * {@code ENUM('EXCLUDED','MAPPED_AI','MAPPED_HINT','MAPPED_MANUAL','PARKED')}.
 * That list is a hard constraint, and {@code ddl-auto=update} only ever <i>adds</i>
 * tables and columns; it will not widen an existing one. So adding a constant to
 * a Java enum (as {@code EXCLUDED_ONCE} was) compiles and passes tests against a
 * freshly-created test database, then fails at runtime on the real database the
 * moment a row carrying the new value is written.
 *
 * <p>Storing these as {@code VARCHAR} is the ordinary, portable mapping for a
 * STRING enum and removes the whole failure mode: the set of valid values becomes
 * the Java enum alone, so a future status needs no migration at all. Hibernate
 * reads and writes them identically either way, and {@code ddl-auto=update} leaves
 * an existing column's type alone, so this conversion sticks.
 *
 * <p>Idempotent and best-effort: each column is checked first and skipped if it is
 * already {@code VARCHAR}, and a failure is logged rather than allowed to stop
 * startup — the app is still usable, and the failure is visible.
 */
@Component
@Order(0) // ahead of DataInitializer and anything else that might write a row
public class EnumColumnMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EnumColumnMigration.class);

    /** table, column, width — every STRING-enum column in the schema. */
    record EnumColumn(String table, String column, int width) {}

    /**
     * Every {@code @Enumerated(EnumType.STRING)} column. This list must stay
     * complete: a column missing from it keeps H2's native {@code ENUM} type and
     * so still carries the failure this class exists to remove. {@code
     * EnumColumnMigrationTest} reflects over the model package and fails if a
     * STRING-enum field has no entry here, because the omission is invisible
     * until the day someone adds a constant.
     */
    static final List<EnumColumn> COLUMNS = List.of(
            new EnumColumn("TRANSACTION_MAPPINGS", "STATUS", 32),
            new EnumColumn("ANALYSIS_RUNS", "STATUS", 32),
            new EnumColumn("MERCHANT_CATEGORIES", "SOURCE", 32),
            new EnumColumn("APP_LOG_ENTRIES", "LEVEL", 32));

    private final DataSource dataSource;

    public EnumColumnMigration(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        migrate(dataSource);
    }

    /**
     * Convert any legacy {@code ENUM} columns in {@code target} to {@code VARCHAR}.
     *
     * <p>Public because startup is not the only moment this is needed: committing a
     * restore replaces the live database wholesale with an older dump
     * ({@code DROP ALL OBJECTS; RUNSCRIPT}), which re-creates whatever column types
     * that backup was written with. Without re-running this afterwards, restoring a
     * backup taken before a status existed would quietly reintroduce the constraint
     * until the next restart.
     */
    public void migrate(DataSource target) {
        try (Connection connection = target.getConnection()) {
            for (EnumColumn column : COLUMNS) {
                convertIfNeeded(connection, column);
            }
        } catch (SQLException e) {
            log.warn("Could not check enum column types; leaving the schema as it is.", e);
        }
    }

    private void convertIfNeeded(Connection connection, EnumColumn column) {
        try {
            String type = columnType(connection, column);
            // Test what it IS, not what its text contains. H2 returns the whole literal for
            // a native enum — ENUM('EXCLUDED','MAPPED_AI',...) — so a substring test for
            // "CHAR" was reading the constant names: the day someone added CHARGEBACK or
            // CHARITY, every database created afterwards would report a type containing
            // CHAR, this column would be skipped forever, and the NEXT constant added
            // would fail to save on exactly the databases this class exists to protect.
            if (type == null || !type.toUpperCase().startsWith("ENUM")) {
                return; // absent (fresh db, Hibernate will create it) or already VARCHAR
            }
            try (Statement st = connection.createStatement()) {
                st.execute("ALTER TABLE \"" + column.table() + "\" ALTER COLUMN \"" + column.column()
                        + "\" SET DATA TYPE VARCHAR(" + column.width() + ")");
            }
            log.info("Widened {}.{} from {} to VARCHAR({}) so new enum values can be stored.",
                    column.table(), column.column(), type, column.width());
        } catch (SQLException e) {
            log.warn("Could not convert {}.{} to VARCHAR; new enum values may fail to save.",
                    column.table(), column.column(), e);
        }
    }

    /** The column's current SQL type name, or null if the table/column isn't there yet. */
    private String columnType(Connection connection, EnumColumn column) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        try (ResultSet rs = meta.getColumns(null, null, column.table(), column.column())) {
            return rs.next() ? rs.getString("TYPE_NAME") : null;
        }
    }
}
