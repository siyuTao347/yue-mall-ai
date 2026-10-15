package com.example.agent.customer;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Statement;

/** Online agent queries share the application's two configured pools instead of cloning them. */
@Component
public class CustomerDatabase {
    public final JdbcTemplate mysql;
    public final JdbcTemplate vector;
    public final TransactionTemplate tx;

    public CustomerDatabase(@Qualifier("primaryDataSource") DataSource mysqlSource,
                            @Qualifier("vectorDataSource") DataSource vectorSource) {
        mysql=bounded(mysqlSource);
        vector=bounded(vectorSource);
        tx=new TransactionTemplate(new DataSourceTransactionManager(mysqlSource));
        tx.setTimeout(3);
    }

    private static JdbcTemplate bounded(DataSource source) {
        JdbcTemplate template=new JdbcTemplate(source) {
            @Override protected void applyStatementSettings(Statement statement) throws java.sql.SQLException {
                super.applyStatementSettings(statement);
                int remainingMs=CustomerBudget.remaining(3000);
                statement.setQueryTimeout(Math.max(1,(remainingMs+999)/1000));
            }
        };
        template.setQueryTimeout(3);
        return template;
    }
}
