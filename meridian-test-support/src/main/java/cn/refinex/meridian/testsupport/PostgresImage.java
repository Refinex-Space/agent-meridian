package cn.refinex.meridian.testsupport;

import com.github.dockerjava.api.exception.NotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;

/**
 * 从 {@code deploy/docker/postgres/} 构建测试用的 PostgreSQL 镜像，与本地 compose 使用同一份 Dockerfile。
 *
 * <p>镜像标签带构建目录内容的摘要：目录不变时直接复用本机已有的镜像，不再调用 docker build；
 * Dockerfile 或初始化脚本一改，摘要随之变化，自动重建。构建需要代理时设置环境变量
 * {@code MERIDIAN_DOCKER_BUILD_PROXY}（例如 {@code http://http.docker.internal:3128}）。
 */
final class PostgresImage {

    /** Dockerfile 所在目录，相对仓库根目录。 */
    private static final Path DOCKER_DIR = Path.of("deploy", "docker", "postgres");

    /** 测试镜像的仓库名，与 compose 的 {@code agent-meridian/postgres:17-local} 区分。 */
    private static final String REPOSITORY = "agent-meridian/postgres";

    private PostgresImage() {}

    /**
     * 返回可用的镜像名，必要时先构建。
     *
     * @return 声明为与官方 postgres 镜像兼容的镜像名
     */
    static DockerImageName resolve() {
        Path dir = locateDockerDir();
        String name = REPOSITORY + ":test-" + contentHash(dir).substring(0, 12);
        if (!exists(name)) {
            ImageFromDockerfile image = new ImageFromDockerfile(name, false).withDockerfile(dir.resolve("Dockerfile"));
            String proxy = System.getenv("MERIDIAN_DOCKER_BUILD_PROXY");
            if (proxy != null && !proxy.isBlank()) {
                image.withBuildArg("HTTP_PROXY", proxy).withBuildArg("HTTPS_PROXY", proxy);
            }
            image.get();
        }
        return DockerImageName.parse(name).asCompatibleSubstituteFor("postgres");
    }

    /** 从当前目录（Maven 运行测试时是模块目录）向上查找仓库根目录。 */
    private static Path locateDockerDir() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(DOCKER_DIR);
            if (Files.isRegularFile(candidate.resolve("Dockerfile"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("在当前目录及其上级中找不到 " + DOCKER_DIR + "/Dockerfile");
    }

    private static boolean exists(String name) {
        try {
            DockerClientFactory.lazyClient().inspectImageCmd(name).exec();
            return true;
        } catch (NotFoundException e) {
            return false;
        }
    }

    private static String contentHash(Path dir) {
        try (Stream<Path> files = Files.walk(dir)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<Path> sorted = files.filter(Files::isRegularFile).sorted().toList();
            for (Path file : sorted) {
                digest.update(dir.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
                digest.update(Files.readAllBytes(file));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
