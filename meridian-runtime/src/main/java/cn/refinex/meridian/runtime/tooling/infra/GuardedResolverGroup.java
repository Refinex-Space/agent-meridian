package cn.refinex.meridian.runtime.tooling.infra;

import io.netty.resolver.AbstractAddressResolver;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * 在 DNS 解析之后校验地址的解析器组（架构设计「平台工具」：DNS 解析后再校验一次，ADR-0035）。{@code web_fetch} 的 HTTP 客户端用它
 * 解析主机名：解析结果中只要有一个地址被拒绝，整个解析失败，连接不会建立；通过时客户端连接的正是被校验过的地址，所以两次解析之间
 * 结果变化（DNS 重绑定）也绕不过去。
 *
 * <p>IP 字面量不经过解析器（Reactor Netty 直接使用），由 {@link GuardedWebFetcher} 在发请求之前校验。
 */
final class GuardedResolverGroup extends AddressResolverGroup<InetSocketAddress> {

    private final AddressResolverGroup<InetSocketAddress> delegate;
    private final Predicate<InetAddress> blocked;

    /**
     * 创建解析器组。
     *
     * @param delegate 实际做 DNS 解析的解析器组；随本组关闭
     * @param blocked 地址是否被拒绝
     */
    GuardedResolverGroup(AddressResolverGroup<InetSocketAddress> delegate, Predicate<InetAddress> blocked) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.blocked = Objects.requireNonNull(blocked, "blocked");
    }

    @Override
    protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
        return new GuardedResolver(executor, delegate.getResolver(executor), blocked);
    }

    @Override
    public void close() {
        super.close();
        delegate.close();
    }

    /** 主机名解析到了被拒绝的地址。 */
    static final class BlockedAddressException extends RuntimeException {

        private final String host;

        BlockedAddressException(String host, InetAddress address) {
            super("主机 " + host + " 解析到了被拒绝的地址 " + address.getHostAddress(), null, false, false);
            this.host = host;
        }

        /**
         * 被拒绝的主机名。
         *
         * @return 主机名
         */
        String host() {
            return host;
        }
    }

    /** 委托解析、校验全部结果的解析器。 */
    private static final class GuardedResolver extends AbstractAddressResolver<InetSocketAddress> {

        private final AddressResolver<InetSocketAddress> delegate;
        private final Predicate<InetAddress> blocked;

        GuardedResolver(
                EventExecutor executor, AddressResolver<InetSocketAddress> delegate, Predicate<InetAddress> blocked) {
            super(executor, InetSocketAddress.class);
            this.delegate = delegate;
            this.blocked = blocked;
        }

        @Override
        protected boolean doIsResolved(InetSocketAddress address) {
            return !address.isUnresolved();
        }

        @Override
        protected void doResolve(InetSocketAddress unresolved, Promise<InetSocketAddress> promise) {
            delegate.resolveAll(unresolved).addListener((Future<List<InetSocketAddress>> future) -> {
                List<InetSocketAddress> all = checked(unresolved, future, promise);
                if (all != null) {
                    promise.trySuccess(all.getFirst());
                }
            });
        }

        @Override
        protected void doResolveAll(InetSocketAddress unresolved, Promise<List<InetSocketAddress>> promise) {
            delegate.resolveAll(unresolved).addListener((Future<List<InetSocketAddress>> future) -> {
                List<InetSocketAddress> all = checked(unresolved, future, promise);
                if (all != null) {
                    promise.trySuccess(all);
                }
            });
        }

        /** 解析失败或有被拒绝的地址时让承诺失败并返回 null，否则返回全部地址。 */
        private List<InetSocketAddress> checked(
                InetSocketAddress unresolved, Future<List<InetSocketAddress>> future, Promise<?> promise) {
            if (!future.isSuccess()) {
                promise.tryFailure(future.cause());
                return null;
            }
            List<InetSocketAddress> all = future.getNow();
            if (all == null || all.isEmpty()) {
                promise.tryFailure(new java.net.UnknownHostException(unresolved.getHostString()));
                return null;
            }
            for (InetSocketAddress address : all) {
                if (address.getAddress() == null || blocked.test(address.getAddress())) {
                    promise.tryFailure(new BlockedAddressException(unresolved.getHostString(), address.getAddress()));
                    return null;
                }
            }
            return all;
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
