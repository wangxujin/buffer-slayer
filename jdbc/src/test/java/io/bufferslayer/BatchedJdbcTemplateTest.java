package io.bufferslayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.mchange.v2.c3p0.ComboPooledDataSource;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Created by guohang.bao on 2017/3/29.
 */
public class BatchedJdbcTemplateTest {

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  private static ComboPooledDataSource dataSource;
  private BatchedJdbcTemplate batchedJdbcTemplate;
  private JdbcTemplate delegate;
  private AsyncReporter reporter;

  private static final String CREATE_TABLE = "CREATE TABLE IF NOT EXISTS test(id INT PRIMARY KEY AUTO_INCREMENT, data VARCHAR(32), time TIMESTAMP);";
  private static final String TRUNCATE_TABLE = "TRUNCATE TABLE test;";
  private static final String INSERTION = "INSERT INTO test(data, time) VALUES(?, ?);";
  private static final String MODIFICATION = "UPDATE test SET data = ? WHERE id > 0;";
  private static final String ROW_COUNT = "SELECT COUNT(1) FROM test;";

  static String randomString() {
    return String.valueOf(ThreadLocalRandom.current().nextLong());
  }

  @BeforeClass
  public static void init() throws Exception {
    dataSource = new ComboPooledDataSource();
    dataSource.setDriverClass("org.h2.Driver");
    dataSource.setMinPoolSize(10);
    dataSource.setMaxPoolSize(50);
    dataSource.setJdbcUrl("jdbc:h2:~/test");
  }

  @Before
  public void setup() throws InterruptedException {
    delegate = new JdbcTemplate(dataSource);
    delegate.setDataSource(dataSource);
    delegate.update(CREATE_TABLE);
    delegate.update(TRUNCATE_TABLE);
  }

  @After
  public void clean() {
    reporter.close();
  }

  @Test
  public void bufferedUpdate() {
    reporter = AsyncReporter.builder(new JdbcTemplateSender(delegate))
        .pendingMaxMessages(100)
        .bufferedMaxMessages(10)
        .messageTimeout(0, TimeUnit.MILLISECONDS)
        .build();
    batchedJdbcTemplate = new BatchedJdbcTemplate(delegate, reporter);

    for (int i = 0; i < 100; i++) {
      batchedJdbcTemplate.update(INSERTION, new Object[]{randomString(), new Date()});
    }
    reporter.flush();
    int rowCount = batchedJdbcTemplate.queryForObject(ROW_COUNT, Integer.class);

    assertEquals(100, rowCount);
  }

  @Test
  public void strictOrder() {
    reporter = AsyncReporter.builder(new JdbcTemplateSender(delegate))
        .pendingMaxMessages(10)
        .bufferedMaxMessages(2)
        .messageTimeout(0, TimeUnit.MILLISECONDS)
        .strictOrder(true)
        .build();
    batchedJdbcTemplate = new BatchedJdbcTemplate(delegate, reporter);

    for (int i = 0; i < 8; i++) {
      batchedJdbcTemplate.update(INSERTION, new Object[]{randomString(), new Date()});
    }

    String dataOfFirstNineRows = randomString();
    batchedJdbcTemplate.update(MODIFICATION, new Object[]{dataOfFirstNineRows});

    String lastRowData = randomString();
    batchedJdbcTemplate.update(INSERTION, new Object[]{lastRowData, new Date()});

    reporter.flush();
    int distinct = batchedJdbcTemplate.queryForObject("SELECT COUNT(DISTINCT(data)) FROM test;", Integer.class);
    assertEquals(2, distinct);

    List<Map<String, Object>> datas = batchedJdbcTemplate.queryForList("SELECT DISTINCT(data) FROM test;");
    Set<String> unordered = new HashSet<>();
    for (Map<String, Object> data: datas) {
      unordered.add((String) data.get("data"));
    }
    assertTrue(unordered.contains(dataOfFirstNineRows));
    assertTrue(unordered.contains(lastRowData));
  }

  @Test
  public void rejectedPromise() throws InterruptedException {
    FakeSender sender = new FakeSender();
    RuntimeException ex = new RuntimeException();
    sender.onMessages(messages -> { throw ex; });
    reporter = AsyncReporter.builder(sender).build();
    batchedJdbcTemplate = new BatchedJdbcTemplate(delegate, reporter);

    CountDownLatch countDown = new CountDownLatch(1);
    batchedJdbcTemplate.update(INSERTION, new Object[]{randomString(), new Date()}).fail(d -> {
      assertTrue(d instanceof MessageDroppedException);
      assertEquals(ex, ((MessageDroppedException) d).getCause());
      countDown.countDown();
    });
    countDown.await();
  }

  @Test
  public void chainedDeferred() throws InterruptedException {
    reporter = AsyncReporter.builder(new JdbcTemplateSender(delegate)).build();
    batchedJdbcTemplate = new BatchedJdbcTemplate(delegate, reporter);

    CountDownLatch countDown = new CountDownLatch(1);
    batchedJdbcTemplate.update(INSERTION, new Object[]{randomString(), new Date()}).done(d -> {
      assertEquals(1, d);
      String expected = randomString();
      batchedJdbcTemplate.update(MODIFICATION, new Object[]{expected}).done(dd -> {
        assertEquals(1, dd);
        int rowCount = batchedJdbcTemplate.queryForObject(ROW_COUNT, Integer.class);
        assertEquals(1, rowCount);
        Object data = batchedJdbcTemplate
            .queryForObject("SELECT data FROM test LIMIT 1", String.class);
        assertEquals(expected, data);
        countDown.countDown();
      });
    });
    countDown.await();
  }

  @Test
  public void unpreparedStatementUseSameFlushThread() throws InterruptedException {
    reporter = AsyncReporter.builder(new JdbcTemplateSender(delegate))
        .pendingMaxMessages(2)
        .bufferedMaxMessages(2)
        .flushThreadKeepalive(10, TimeUnit.SECONDS)
        .build();
    batchedJdbcTemplate = new BatchedJdbcTemplate(delegate, reporter);

    for (int i = 0; i < 2; i++) {
      batchedJdbcTemplate.update("INSERT INTO test(data, time) VALUES ('data', now())");
    }
    assertEquals(1, reporter.flushThreadCount());

    Thread.sleep(1000);
    int rowCount = batchedJdbcTemplate.queryForObject("SELECT COUNT(1) FROM test;", Integer.class);
    assertEquals(2, rowCount);
  }

  @Test
  public void samePreparedStatementUseSameFlushThread() {
    reporter = AsyncReporter.builder(new JdbcTemplateSender(delegate))
        .pendingMaxMessages(2)
        .bufferedMaxMessages(2)
        .flushThreadKeepalive(10, TimeUnit.SECONDS)
        .build();
    batchedJdbcTemplate = new BatchedJdbcTemplate(delegate, reporter);

    for (int i = 0; i < 2; i++) {
      batchedJdbcTemplate.update(INSERTION, new Object[]{randomString(), new Date()});
    }
    assertEquals(1, reporter.flushThreadCount());
  }

  @Test
  public void differentPreparedStatementUseDifferentFlushThreads() {
    reporter = AsyncReporter.builder(new JdbcTemplateSender(delegate))
        .pendingMaxMessages(2)
        .bufferedMaxMessages(2)
        .flushThreadKeepalive(10, TimeUnit.SECONDS)
        .build();
    batchedJdbcTemplate = new BatchedJdbcTemplate(delegate, reporter);

    batchedJdbcTemplate.update(INSERTION, new Object[]{randomString(), new Date()});
    batchedJdbcTemplate.update(MODIFICATION, new Object[]{randomString()});
    assertEquals(2, reporter.flushThreadCount());
  }
}
