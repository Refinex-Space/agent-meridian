package cn.refinex.meridian.runtime.support;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import javax.sql.DataSource;

/** 可以切换为"连不上数据库"的数据源包装，用于模拟续租时数据库不可用。 */
public final class FailingDataSource implements DataSource {

    private final DataSource delegate;
    private final AtomicBoolean failing = new AtomicBoolean();

    /**
     * 包装一个数据源。
     *
     * @param delegate 被包装的数据源
     */
    public FailingDataSource(DataSource delegate) {
        this.delegate = delegate;
    }

    /**
     * 切换是否失败。
     *
     * @param fail 为 true 时获取连接抛出异常
     */
    public void failing(boolean fail) {
        failing.set(fail);
    }

    @Override
    public Connection getConnection() throws SQLException {
        check();
        return delegate.getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        check();
        return delegate.getConnection(username, password);
    }

    private void check() throws SQLException {
        if (failing.get()) {
            throw new SQLException("模拟数据库不可用");
        }
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return delegate.isWrapperFor(iface);
    }
}
