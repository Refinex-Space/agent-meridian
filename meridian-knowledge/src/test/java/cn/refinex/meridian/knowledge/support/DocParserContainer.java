package cn.refinex.meridian.knowledge.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * 容器化的 doc-parser（T18-A03），在同一个测试 JVM 内以单例复用。
 *
 * <p>镜像从仓库的 {@code doc-parser/} 构建，标签带构建输入（Dockerfile、依赖锁、源码）的摘要：输入不变时复用本机的镜像，变了才重建。
 * 构建用 docker 命令行（Dockerfile 用了 BuildKit 的缓存挂载，Testcontainers 的构建接口不支持）；首次构建要下载 Python 依赖与模型，
 * 约 10 分钟，需要代理时设置 {@code MERIDIAN_DOCKER_BUILD_PROXY}。
 */
public final class DocParserContainer {

    private static final String REPOSITORY = "agent-meridian/doc-parser";

    private static GenericContainer<?> container;

    private DocParserContainer() {}

    /**
     * doc-parser 的基础 URL，必要时构建镜像并启动容器。
     *
     * @return URL
     */
    public static synchronized String url() {
        if (container == null) {
            GenericContainer<?> started = new GenericContainer<>(DockerImageName.parse(image()))
                    .withExposedPorts(8000)
                    .waitingFor(Wait.forHttp("/healthz").forPort(8000).withStartupTimeout(Duration.ofMinutes(2)));
            started.start();
            container = started;
        }
        return "http://" + container.getHost() + ":" + container.getMappedPort(8000);
    }

    private static String image() {
        Path dir = locate();
        String name = REPOSITORY + ":test-" + digest(dir).substring(0, 12);
        boolean exists;
        try {
            DockerClientFactory.lazyClient().inspectImageCmd(name).exec();
            exists = true;
        } catch (com.github.dockerjava.api.exception.NotFoundException e) {
            exists = false;
        }
        if (!exists) {
            build(dir, name);
        }
        return name;
    }

    private static void build(Path dir, String name) {
        List<String> command = new java.util.ArrayList<>(List.of("docker", "build", "-t", name));
        String proxy = System.getenv("MERIDIAN_DOCKER_BUILD_PROXY");
        if (proxy != null && !proxy.isBlank()) {
            command.addAll(List.of("--build-arg", "HTTP_PROXY=" + proxy, "--build-arg", "HTTPS_PROXY=" + proxy));
        }
        command.add(dir.toString());
        try {
            Process process = new ProcessBuilder(command).inheritIO().start();
            if (process.waitFor() != 0) {
                throw new IllegalStateException("doc-parser 镜像构建失败：" + String.join(" ", command));
            }
        } catch (IOException e) {
            throw new IllegalStateException("无法执行 docker build", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static Path locate() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve("doc-parser/Dockerfile"))) {
                return dir.resolve("doc-parser");
            }
        }
        throw new IllegalStateException("在当前目录及其上级中找不到 doc-parser/Dockerfile");
    }

    /** 构建输入的摘要：Dockerfile、依赖与锁、src 与 tests（不含虚拟环境与缓存）。 */
    private static String digest(Path dir) {
        try (Stream<Path> files = Files.walk(dir)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<Path> inputs = files.filter(Files::isRegularFile)
                    .filter(file -> {
                        String relative = dir.relativize(file).toString();
                        return relative.equals("Dockerfile")
                                || relative.equals("pyproject.toml")
                                || relative.equals("uv.lock")
                                || ((relative.startsWith("src/") || relative.startsWith("tests/"))
                                        && !relative.contains("__pycache__"));
                    })
                    .sorted()
                    .toList();
            for (Path file : inputs) {
                digest.update(dir.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
                digest.update(Files.readAllBytes(file));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
