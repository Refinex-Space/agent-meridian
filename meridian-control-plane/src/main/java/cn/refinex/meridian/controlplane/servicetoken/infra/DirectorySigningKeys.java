package cn.refinex.meridian.controlplane.servicetoken.infra;

import cn.refinex.meridian.controlplane.servicetoken.app.SigningKeySource;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.text.ParseException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 签名密钥的开发实现（ADR-0044）：目录中每把密钥一个 JWK 文件（含私钥，权限 600），启动时没有密钥就生成 RSA 2048 并写入，所以重启后
 * 已签发的令牌仍能校验。最新（JWK 的 {@code iat}）的一把签名，全部发布到 JWKS；{@link #rotate()} 生成新密钥并删除上一把之前的。
 *
 * <p>只适合单副本与开发：多副本要共用同一个卷或改用密钥管理系统（T28）。线程安全：读写在本对象的锁内，密钥列表不可变。
 */
public final class DirectorySigningKeys implements SigningKeySource {

    /** 密钥长度。 */
    static final int KEY_SIZE = 2048;

    /** 保留的密钥数：当前一把加上一把。 */
    static final int RETAINED = 2;

    static final String SUFFIX = ".jwk.json";

    private final Path directory;
    private final Clock clock;
    private volatile List<RSAKey> keys;

    /**
     * 打开目录，没有密钥时生成一把。
     *
     * @param directory 密钥目录；不存在时创建
     * @param clock 时钟，写入密钥的签发时间
     * @throws UncheckedIOException 目录不可读写时
     */
    public DirectorySigningKeys(Path directory, Clock clock) {
        this.directory = directory;
        this.clock = clock;
        synchronized (this) {
            List<RSAKey> loaded = load();
            if (loaded.isEmpty()) {
                write(generate());
                loaded = load();
            }
            this.keys = loaded;
        }
    }

    @Override
    public RSAKey current() {
        return keys.getFirst();
    }

    @Override
    public List<RSAKey> publicKeys() {
        return keys.stream().map(RSAKey::toPublicJWK).toList();
    }

    @Override
    public synchronized void rotate() {
        write(generate());
        List<RSAKey> loaded = load();
        for (RSAKey stale : loaded.subList(Math.min(RETAINED, loaded.size()), loaded.size())) {
            try {
                Files.deleteIfExists(file(stale.getKeyID()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        this.keys = List.copyOf(loaded.subList(0, Math.min(RETAINED, loaded.size())));
    }

    private RSAKey generate() {
        try {
            return new RSAKeyGenerator(KEY_SIZE)
                    .keyID("sk_" + UUID.randomUUID())
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .issueTime(Date.from(clock.instant()))
                    .generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("生成签名密钥失败", e);
        }
    }

    private void write(RSAKey key) {
        try {
            Files.createDirectories(directory);
            Path target = file(key.getKeyID());
            Path temp = Files.createTempFile(directory, ".tmp-", SUFFIX);
            restrict(temp);
            Files.writeString(temp, key.toJSONString(), StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(temp, target);
        } catch (IOException e) {
            throw new UncheckedIOException("写入签名密钥失败：" + directory, e);
        }
    }

    /** 按签发时间从新到旧；签发时间相同按 kid，保证各副本的顺序一致。 */
    private List<RSAKey> load() {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<RSAKey> loaded = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path path : files.filter(p -> p.getFileName().toString().endsWith(SUFFIX)
                            && !p.getFileName().toString().startsWith("."))
                    .toList()) {
                loaded.add(RSAKey.parse(Files.readString(path, StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读取签名密钥失败：" + directory, e);
        } catch (ParseException e) {
            throw new IllegalStateException("签名密钥文件不是合法的 JWK：" + directory, e);
        }
        loaded.sort(Comparator.comparing((RSAKey key) -> key.getIssueTime() == null ? new Date(0) : key.getIssueTime())
                .reversed()
                .thenComparing(RSAKey::getKeyID));
        return List.copyOf(loaded);
    }

    private Path file(String keyId) {
        return directory.resolve(keyId + SUFFIX);
    }

    private static void restrict(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // 非 POSIX 文件系统（Windows）：依赖目录本身的权限
        }
    }
}
