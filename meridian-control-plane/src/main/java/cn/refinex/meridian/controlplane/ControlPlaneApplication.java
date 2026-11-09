package cn.refinex.meridian.controlplane;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** control-plane 的入口（架构设计「部署单元」）：Spring MVC 加虚拟线程，schema {@code cp}。 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ControlPlaneApplication {

    /**
     * 启动 control-plane。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(ControlPlaneApplication.class, args);
    }
}
