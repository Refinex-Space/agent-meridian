package cn.refinex.meridian.runtime;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * runtime 应用入口（Spring WebFlux，schema {@code rt}）。启动时由 Flyway 以迁移角色执行 {@code db/migration/rt} 下的脚本，
 * 应用以只有 DML 的角色连接数据库；本地运行方式见模块 {@code CLAUDE.md}。
 */
@SpringBootApplication
public class RuntimeApplication {

    /**
     * 启动 runtime。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(RuntimeApplication.class, args);
    }
}
