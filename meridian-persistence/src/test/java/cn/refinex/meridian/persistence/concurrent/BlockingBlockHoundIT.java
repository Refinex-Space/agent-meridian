package cn.refinex.meridian.persistence.concurrent;

import static org.assertj.core.api.Assertions.assertThat;

import cn.refinex.meridian.common.concurrent.Blocking;
import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.common.id.IdPrefix;
import cn.refinex.meridian.common.id.Ids;
import cn.refinex.meridian.persistence.PersistenceFixtures;
import cn.refinex.meridian.persistence.tenant.NoteRepository;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.blockhound.BlockHound;
import reactor.blockhound.BlockingOperationError;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/** T02-A06：Reactor 线程上直接调用阻塞 JDBC 被 BlockHound 检测为违规，经 Blocking 调用则通过。 */
class BlockingBlockHoundIT {

    private static final Logger log = LoggerFactory.getLogger(BlockingBlockHoundIT.class);

    private static DataSource dataSource;
    private static NoteRepository notes;
    private static PlatformContext context;

    @BeforeAll
    static void setUp() {
        dataSource = PersistenceFixtures.dataSource();
        notes = new NoteRepository(dataSource);
        context = PlatformContext.builder()
                .tenantId(Ids.newId(IdPrefix.TENANT))
                .projectId(Ids.newId(IdPrefix.PROJECT))
                .build();
        PlatformContexts.run(context, () -> notes.insert("n1", "经 Blocking 读取", List.of(), Instant.now()));
        BlockHound.install();
    }

    @Test
    void directJdbcOnReactorThreadIsDetected() {
        Mono<Long> direct = Mono.fromCallable(BlockingBlockHoundIT::countNotes).subscribeOn(Schedulers.parallel());

        AtomicReference<Throwable> error = new AtomicReference<>();
        StepVerifier.create(direct).expectErrorSatisfies(error::set).verify();

        // 在测试线程上断言与记录：Reactor 线程上写日志本身也是阻塞调用，会被 BlockHound 拦下
        assertThat(error.get()).isInstanceOf(BlockingOperationError.class).hasMessageContaining("Blocking call!");
        log.info("BlockHound 检测到违规：{}", error.get().getMessage());
    }

    @Test
    void jdbcThroughBlockingPassesAndSeesPlatformContext() {
        Mono<List<String>> viaBlocking = Mono.just("trigger")
                .publishOn(Schedulers.parallel())
                .flatMap(ignored -> Blocking.call(notes::listIds))
                .contextWrite(PlatformContexts.write(context));

        StepVerifier.create(viaBlocking).expectNext(List.of("n1")).verifyComplete();
    }

    @Test
    void rawJdbcThroughBlockingPasses() {
        Mono<Long> viaBlocking = Mono.just("trigger")
                .publishOn(Schedulers.parallel())
                .flatMap(ignored -> Blocking.call(BlockingBlockHoundIT::countNotes));

        StepVerifier.create(viaBlocking)
                .assertNext(count -> assertThat(count).isPositive())
                .verifyComplete();
    }

    private static long countNotes() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT count(*) FROM rt.t02_notes")) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
