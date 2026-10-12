package com.example.agent.customer;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import jakarta.annotation.PreDestroy;
import javax.sql.DataSource;

/** Separate online pools keep ingestion and slow pool acquisition out of the customer budget. */
@Component
public class CustomerDatabase {
    public final JdbcTemplate mysql;
    public final JdbcTemplate vector;
    public final TransactionTemplate tx;
    private final HikariDataSource onlineMysql, onlineVector;
    public CustomerDatabase(@Qualifier("primaryDataSource") DataSource mysqlSource,
                            @Qualifier("vectorDataSource") DataSource vectorSource) {
        onlineMysql=copy(mysqlSource,"customer-mysql",8,500);
        onlineVector=copy(vectorSource,"customer-vector",4,1000);
        mysql=bounded(onlineMysql);
        vector=bounded(onlineVector);
        tx=new TransactionTemplate(new DataSourceTransactionManager(onlineMysql));
        tx.setTimeout(3);
    }
    private static JdbcTemplate bounded(DataSource source) {
        JdbcTemplate template=new JdbcTemplate(source) {
            @Override protected void applyStatementSettings(java.sql.Statement statement) throws java.sql.SQLException {
                super.applyStatementSettings(statement);
                int remaining=CustomerBudget.remaining(1000);
                statement.setQueryTimeout(1);
                statement.getConnection().setNetworkTimeout(Runnable::run,remaining);
            }
        };
        template.setQueryTimeout(1);return template;
    }
    private static HikariDataSource copy(DataSource source,String name,int size,int timeout) {
        HikariDataSource old=(HikariDataSource)source, ds=new HikariDataSource();
        String url=old.getJdbcUrl();
        if(url.startsWith("jdbc:postgresql:")) {
            url=url.replaceAll("([?&])(connectTimeout|socketTimeout)=[^&]*", "$1").replace("?&","?").replace("&&","&");
            url+=(url.contains("?")?"&":"?")+"connectTimeout=1&socketTimeout=2";
        }
        ds.setJdbcUrl(url); ds.setUsername(old.getUsername()); ds.setPassword(old.getPassword());
        ds.setDataSourceProperties(old.getDataSourceProperties());
        if(url.startsWith("jdbc:mysql:")){ds.addDataSourceProperty("connectTimeout","500");ds.addDataSourceProperty("socketTimeout","1000");}
        ds.setMaximumPoolSize(size);ds.setMinimumIdle(0);ds.setConnectionTimeout(timeout);
        ds.setValidationTimeout(500);ds.setInitializationFailTimeout(-1);ds.setPoolName(name);return ds;
    }
    @PreDestroy public void close(){onlineMysql.close();onlineVector.close();}
}
