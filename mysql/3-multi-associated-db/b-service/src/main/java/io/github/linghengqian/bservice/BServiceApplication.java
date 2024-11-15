package io.github.linghengqian.bservice;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.seata.core.context.RootContext;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.Objects;
import java.util.stream.IntStream;

@SuppressWarnings({"SqlNoDataSourceInspection", "SqlDialectInspection"})
@SpringBootApplication
public class BServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(BServiceApplication.class, args);
    }

    @Bean
    ApplicationRunner runner(ServletWebServerApplicationContext applicationContext, JdbcClient jdbcClient) {
        return _ -> {
            jdbcClient.sql("""
                    CREATE TABLE IF NOT EXISTS t_order (
                        order_id BIGINT NOT NULL AUTO_INCREMENT,
                        order_type INT(11),
                        user_id INT NOT NULL,
                        address_id BIGINT NOT NULL,
                        status VARCHAR(50),
                        PRIMARY KEY (order_id)
                    )""").update();
            jdbcClient.sql("TRUNCATE TABLE t_order").update();
            IntStream.rangeClosed(1, 10).forEachOrdered(i -> {
                KeyHolder keyHolder = new GeneratedKeyHolder();
                jdbcClient.sql("INSERT INTO t_order (user_id, order_type, address_id, status) VALUES (?, ?, ?, ?)")
                        .param(1, i)
                        .param(2, i % 2)
                        .param(3, i)
                        .param(4, "INSERT_TEST")
                        .update(keyHolder);
                Number orderIdKey = keyHolder.getKey();
                Objects.requireNonNull(orderIdKey);
                long orderId = orderIdKey.longValue();
                Assert.isTrue(0 != orderId, "Generated snowflake id should never be equal to 0.");
            });
            RestClient restClient = RestClient.create();
            int port = applicationContext.getWebServer().getPort();
            restClient.get()
                    .uri("http://127.0.0.1:" + port + "/test_select")
                    .retrieve()
                    .body(Void.class);
            restClient.post()
                    .uri("http://127.0.0.1:" + port + "/test_transaction_template")
                    .retrieve()
                    .body(Void.class);
            restClient.post()
                    .uri("http://127.0.0.1:" + port + "/test_transactional")
                    .retrieve()
                    .body(Void.class);
            restClient.post()
                    .uri("http://127.0.0.1:" + port + "/test_consumer")
                    .retrieve()
                    .body(Void.class);
        };
    }
}

record Order(long orderId, int orderType, int userId, long addressId, String status) {
}

@SuppressWarnings({"SqlNoDataSourceInspection", "SqlDialectInspection"})
@RestController
class TestController {
    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;
    private final TestService testService;

    public TestController(JdbcClient jdbcClient, PlatformTransactionManager transactionManager, TestService testService) {
        this.jdbcClient = jdbcClient;
        transactionTemplate = new TransactionTemplate(transactionManager);
        this.testService = testService;
    }

    @GetMapping("/test_select")
    public void testAll() {
        jdbcClient.sql("SELECT * FROM t_order").query(Order.class).list();
    }

    @PostMapping("/test_transaction_template")
    public void testTransactionTransactionTemplate() {
        try {
            transactionTemplate.execute(new TransactionCallbackWithoutResult() {
                @Override
                protected void doInTransactionWithoutResult(@NonNull TransactionStatus status) {
                    jdbcClient.sql("INSERT INTO t_order (user_id, order_type, address_id, status) VALUES (2024, 0, 2024, 'INSERT_TEST')")
                            .update();
                    jdbcClient.sql("INSERT INTO t_order_does_not_exist (test_id_does_not_exist) VALUES (2024)")
                            .update();
                }
            });
        } catch (Exception ignored) {
        }
        jdbcClient.sql("SELECT * FROM t_order WHERE user_id = 2024")
                .query(Order.class)
                .optional()
                .ifPresent(_ -> {
                    throw new RuntimeException("Normally, this exception should not be thrown.");
                });
    }

    @PostMapping("/test_transactional")
    public void testTransactionTransactional() {
        try {
            testService.testTransactional();
        } catch (Exception ignored) {
        }
        jdbcClient.sql("SELECT * FROM t_order WHERE user_id = 2023")
                .query(Order.class)
                .optional()
                .ifPresent(_ -> {
                    throw new RuntimeException("Normally, this exception should not be thrown.");
                });
    }

    @PostMapping("/test_consumer")
    public void testConsumer() {
        RestClient restClient = RestClient.builder().requestInterceptor((request, body, execution) -> {
                    String xid = RootContext.getXID();
                    if (null != xid) {
                        request.getHeaders().add(RootContext.KEY_XID, xid);
                    }
                    return execution.execute(request, body);
                })
                .build();
        try {
            transactionTemplate.execute(new TransactionCallbackWithoutResult() {
                @Override
                protected void doInTransactionWithoutResult(@NonNull TransactionStatus status) {
                    restClient.post().uri("http://a-service:8080/test_provider").retrieve().toBodilessEntity();
                    jdbcClient.sql("INSERT INTO t_order (user_id, order_type, address_id, status) VALUES (114514, 0, 114514, 'INSERT_TEST')")
                            .update();
                    jdbcClient.sql("INSERT INTO t_order_does_not_exist (test_id_does_not_exist) VALUES (114514)")
                            .update();
                }
            });
        } catch (Exception ignored) {
        }
        jdbcClient.sql("SELECT * FROM t_order WHERE user_id = 114514")
                .query(Order.class)
                .optional()
                .ifPresent(_ -> {
                    throw new RuntimeException("Normally, this exception should not be thrown.");
                });
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl("jdbc:shardingsphere:classpath:a-service/demo-for-a-service.yaml");
        hikariConfig.setDriverClassName("org.apache.shardingsphere.driver.ShardingSphereDriver");
        try (HikariDataSource dataSource = new HikariDataSource(hikariConfig)) {
            JdbcClient jdbcClientMySQLForAService = JdbcClient.create(dataSource);
            jdbcClientMySQLForAService.sql("SELECT * FROM t_order WHERE user_id = 5201314")
                    .query(Order.class)
                    .optional()
                    .ifPresent(_ -> {
                        throw new RuntimeException("Normally, this exception should not be thrown.");
                    });
        }
    }
}

@SuppressWarnings("SqlNoDataSourceInspection")
@Service
class TestService {
    private final JdbcClient jdbcClient;

    public TestService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional
    public void testTransactional() {
        jdbcClient.sql("INSERT INTO t_order (user_id, order_type, address_id, status) VALUES (2023, 2, 2023, 'INSERT_TEST')")
                .update();
        jdbcClient.sql("INSERT INTO t_order_does_not_exist (test_id_does_not_exist) VALUES (2023)")
                .update();
    }
}
