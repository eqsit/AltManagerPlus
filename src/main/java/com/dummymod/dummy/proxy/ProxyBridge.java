package com.dummymod.dummy.proxy;

import com.dummymod.config.DummyConfig;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;

public final class ProxyBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("DummyMod-Proxy");
    private static volatile Context activeContext;

    public record Context(DummyConfig.SocksProxy proxy, String targetHost, int targetPort) {}

    private ProxyBridge() {}

    public static synchronized <T> T withContext(DummyConfig.SocksProxy proxy, String targetHost, int targetPort, java.util.concurrent.Callable<T> action) throws Exception {
        activeContext = new Context(proxy, targetHost, targetPort);
        try {
            return action.call();
        } finally {
            activeContext = null;
        }
    }

    public static void inject(ChannelPipeline pipeline) {
        Context ctx = activeContext;
        if (ctx == null || ctx.proxy == null || !ctx.proxy.enabled || ctx.proxy.host == null || ctx.proxy.host.isBlank()) {
            return;
        }
        pipeline.addFirst("dummymod-socks5", new Socks5ClientHandler(ctx.proxy, ctx.targetHost, ctx.targetPort));
        LOGGER.info("[DummyMod-Proxy] Injected SOCKS5 handler for {}:{} via proxy {}:{}",
                ctx.targetHost, ctx.targetPort, ctx.proxy.host, ctx.proxy.port);
    }

    public static class Socks5ClientHandler extends ByteToMessageDecoder {
        private enum State {
            INIT,
            AUTH_RESPONSE,
            AUTH_STATUS,
            CONNECT_RESPONSE,
            DONE
        }

        private final DummyConfig.SocksProxy proxy;
        private final String targetHost;
        private final int targetPort;
        private State state = State.INIT;

        public Socks5ClientHandler(DummyConfig.SocksProxy proxy, String targetHost, int targetPort) {
            this.proxy = proxy;
            this.targetHost = targetHost;
            this.targetPort = targetPort;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            super.channelActive(ctx);
            sendGreeting(ctx);
        }

        private void sendGreeting(ChannelHandlerContext ctx) {
            boolean hasAuth = proxy.username != null && !proxy.username.isBlank();
            ByteBuf buf = ctx.alloc().buffer();
            buf.writeByte(0x05); // SOCKS5
            if (hasAuth) {
                buf.writeByte(2); // 2 auth methods
                buf.writeByte(0x00); // NO AUTH
                buf.writeByte(0x02); // USER/PASS
            } else {
                buf.writeByte(1); // 1 auth method
                buf.writeByte(0x00); // NO AUTH
            }
            ctx.writeAndFlush(buf);
            state = State.AUTH_RESPONSE;
        }

        private void sendAuth(ChannelHandlerContext ctx) {
            byte[] userBytes = (proxy.username == null ? "" : proxy.username).getBytes(StandardCharsets.UTF_8);
            byte[] passBytes = (proxy.password == null ? "" : proxy.password).getBytes(StandardCharsets.UTF_8);
            ByteBuf buf = ctx.alloc().buffer();
            buf.writeByte(0x01); // Subnegotiation version 1
            buf.writeByte(userBytes.length);
            buf.writeBytes(userBytes);
            buf.writeByte(passBytes.length);
            buf.writeBytes(passBytes);
            ctx.writeAndFlush(buf);
            state = State.AUTH_STATUS;
        }

        private void sendConnect(ChannelHandlerContext ctx) {
            byte[] hostBytes = targetHost.getBytes(StandardCharsets.UTF_8);
            ByteBuf buf = ctx.alloc().buffer();
            buf.writeByte(0x05); // SOCKS5
            buf.writeByte(0x01); // CMD: CONNECT
            buf.writeByte(0x00); // RSV
            buf.writeByte(0x03); // ATYP: DOMAINNAME
            buf.writeByte(hostBytes.length);
            buf.writeBytes(hostBytes);
            buf.writeShort(targetPort);
            ctx.writeAndFlush(buf);
            state = State.CONNECT_RESPONSE;
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
            if (state == State.AUTH_RESPONSE) {
                if (in.readableBytes() < 2) return;
                byte version = in.readByte();
                byte method = in.readByte();
                if (version != 0x05) {
                    throw new IllegalStateException("SOCKS5 version error: " + version);
                }
                if (method == 0x00) {
                    sendConnect(ctx);
                } else if (method == 0x02) {
                    sendAuth(ctx);
                } else {
                    throw new IllegalStateException("SOCKS5 proxy rejected auth method: " + method);
                }
            } else if (state == State.AUTH_STATUS) {
                if (in.readableBytes() < 2) return;
                byte authVer = in.readByte();
                byte status = in.readByte();
                if (status != 0x00) {
                    throw new IllegalStateException("SOCKS5 username/password auth failed (status " + status + ")");
                }
                sendConnect(ctx);
            } else if (state == State.CONNECT_RESPONSE) {
                if (in.readableBytes() < 4) return;
                in.markReaderIndex();
                byte version = in.readByte();
                byte rep = in.readByte();
                byte rsv = in.readByte();
                byte atyp = in.readByte();

                int needed;
                if (atyp == 0x01) {
                    needed = 4 + 2; // IPv4 + port
                } else if (atyp == 0x03) {
                    if (in.readableBytes() < 1) {
                        in.resetReaderIndex();
                        return;
                    }
                    int domainLen = in.readUnsignedByte();
                    needed = domainLen + 2;
                } else if (atyp == 0x04) {
                    needed = 16 + 2; // IPv6 + port
                } else {
                    throw new IllegalStateException("Unknown SOCKS5 ATYP: " + atyp);
                }

                if (in.readableBytes() < needed) {
                    in.resetReaderIndex();
                    return;
                }

                in.skipBytes(needed);
                if (rep != 0x00) {
                    throw new IllegalStateException("SOCKS5 connect failed: code " + rep);
                }

                state = State.DONE;
                ctx.pipeline().remove(this);
                LOGGER.info("[DummyMod-Proxy] SOCKS5 tunnel established successfully to {}:{}", targetHost, targetPort);
            }
        }
    }
}
