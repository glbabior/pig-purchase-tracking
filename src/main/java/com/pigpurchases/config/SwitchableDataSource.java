package com.pigpurchases.config;

import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * A DataSource that normally serves the live database but can be temporarily
 * routed to a "preview" database instead. This is what lets the whole app show a
 * backup's data during a restore preview without touching the live database — the
 * live db is only modified if the user commits the restore.
 *
 * <p>Single-user app, so the active target is a simple volatile flag rather than
 * a thread-local. Switching only happens between requests (preview / commit /
 * cancel are discrete operations), never mid-request.
 */
public class SwitchableDataSource extends AbstractDataSource {

    private final DataSource live;
    private volatile DataSource preview;
    private volatile boolean usePreview = false;

    public SwitchableDataSource(DataSource live) {
        this.live = live;
    }

    private DataSource current() {
        return (usePreview && preview != null) ? preview : live;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return current().getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return current().getConnection(username, password);
    }

    /** The always-live database, used to apply a committed restore in place. */
    public DataSource getLive() {
        return live;
    }

    public boolean isPreviewing() {
        return usePreview;
    }

    /** Route the app at the given preview database. */
    public void startPreview(DataSource previewDataSource) {
        this.preview = previewDataSource;
        this.usePreview = true;
    }

    /** Route back to live and return the retired preview DataSource so the caller can close it. */
    public DataSource endPreview() {
        this.usePreview = false;
        DataSource retired = this.preview;
        this.preview = null;
        return retired;
    }
}
