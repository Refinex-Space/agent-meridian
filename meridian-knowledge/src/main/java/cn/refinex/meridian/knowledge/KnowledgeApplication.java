package cn.refinex.meridian.knowledge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** knowledge 的入口（架构设计「部署单元」）：Spring MVC 加虚拟线程，schema {@code kb}。 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class KnowledgeApplication {

    /**
     * 启动 knowledge。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(KnowledgeApplication.class, args);
    }
}
