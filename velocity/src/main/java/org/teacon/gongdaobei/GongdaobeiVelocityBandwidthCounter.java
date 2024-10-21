/*
 * Copyright (C) 2023 TeaConMC <contact@teacon.org>
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.teacon.gongdaobei;

import com.google.common.net.HostAndPort;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Closeable;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.net.InetSocketAddress;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.netty.handler.codec.ByteToMessageDecoder.MERGE_CUMULATOR;

public final class GongdaobeiVelocityBandwidthCounter implements Closeable {
    private static final String TX_NAME = "gongdaobei-tx-bandwidth-counter";
    private static final String RX_NAME = "gongdaobei-rx-bandwidth-counter";

    private static @Nullable VarHandle vsCmVar;
    private static @Nullable MethodHandle scihGetMethod;
    private static @Nullable MethodHandle bcihGetMethod;
    private static @Nullable MethodHandle scihSetMethod;
    private static @Nullable MethodHandle bcihSetMethod;
    private static @Nullable MethodHandle ciInitCMethod;
    private static @Nullable MethodHandle cmGetSciMethod;
    private static @Nullable MethodHandle cmGetBciMethod;
    private static @Nullable ReflectiveOperationException thrown;

    static {
        try {
            var cClass = Channel.class;
            var ciClass = ChannelInitializer.class;
            var vsClass = Class.forName("com.velocitypowered.proxy.VelocityServer");
            var cmClass = Class.forName("com.velocitypowered.proxy.network.ConnectionManager");
            var scihClass = Class.forName("com.velocitypowered.proxy.network.ServerChannelInitializerHolder");
            var bcihClass = Class.forName("com.velocitypowered.proxy.network.BackendChannelInitializerHolder");

            var lookup = MethodHandles.lookup();
            var ciLookup = MethodHandles.privateLookupIn(ciClass, lookup);
            var vsLookup = MethodHandles.privateLookupIn(vsClass, lookup);

            var ciGetType = MethodType.methodType(ciClass);
            var scihGetType = MethodType.methodType(scihClass);
            var bcihGetType = MethodType.methodType(bcihClass);
            var cInitType = MethodType.methodType(void.class, cClass);
            var ciSetType = MethodType.methodType(void.class, ciClass);

            vsCmVar = vsLookup.findVarHandle(vsClass, "cm", cmClass);
            scihGetMethod = lookup.findVirtual(scihClass, "get", ciGetType);
            bcihGetMethod = lookup.findVirtual(bcihClass, "get", ciGetType);
            scihSetMethod = lookup.findVirtual(scihClass, "set", ciSetType);
            bcihSetMethod = lookup.findVirtual(bcihClass, "set", ciSetType);
            ciInitCMethod = ciLookup.findVirtual(ciClass, "initChannel", cInitType);
            cmGetSciMethod = lookup.findVirtual(cmClass, "getServerChannelInitializer", scihGetType);
            cmGetBciMethod = lookup.findVirtual(cmClass, "getBackendChannelInitializer", bcihGetType);
        } catch (ReflectiveOperationException e) {
            thrown = e;
        }
    }

    private final Logger logger;
    private volatile boolean enabled;

    private final AtomicReference<Entry> serverEntry = new AtomicReference<>();
    private final ConcurrentMap<HostAndPort, Entry> backendEntries = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public GongdaobeiVelocityBandwidthCounter(GongdaobeiVelocity plugin, boolean enabled) {
        this.logger = plugin.logger;
        this.enabled = enabled;
        if (enabled) {
            try {
                var cm = Objects.requireNonNull(vsCmVar).get(plugin.server);
                var scih = Objects.requireNonNull(cmGetSciMethod).invoke(cm);
                var bcih = Objects.requireNonNull(cmGetBciMethod).invoke(cm);
                var oldServer = (ChannelInitializer<Channel>) Objects.requireNonNull(scihGetMethod).invoke(scih);
                var oldBackend = (ChannelInitializer<Channel>) Objects.requireNonNull(bcihGetMethod).invoke(bcih);
                this.logger.info("Injecting packet collector to server handler ...");
                Objects.requireNonNull(scihSetMethod).invoke(scih, new ServerInitializer(oldServer));
                this.logger.info("Injecting packet collector to backend handler ...");
                Objects.requireNonNull(bcihSetMethod).invoke(bcih, new BackendInitializer(oldBackend));
            } catch (Throwable e) {
                var ex = new RuntimeException(e);
                if (thrown != null) {
                    ex.addSuppressed(thrown);
                }
                logger.log(Level.SEVERE, "Failed to inject packet size collector: " + e.getMessage(), ex);
                throw ex;
            }
        }
    }

    public Optional<Entry> collectServer() {
        return Optional.ofNullable(this.serverEntry.getAndSet(null));
    }

    public Map<HostAndPort, Entry> collectBackend() {
        var collected = new HashMap<HostAndPort, Entry>(this.backendEntries.size() + 1);
        this.backendEntries.entrySet().removeIf(entry -> {
            collected.put(entry.getKey(), entry.getValue());
            return true;
        });
        return Collections.unmodifiableMap(collected);
    }

    private Entry calculateSize(boolean tx, boolean rx, ByteBuf msg, int msgSize, Entry entry) {
        var msgFrom = msg.readerIndex();
        var msgPrefixedSize = this.prefixedSizeVarInt(msgSize);
        if (entry.compressed()) {
            var msgTrimmedSize = 0;
            var msgUncompressedSize = this.readVarInt(msg).orElse(0);
            if (msgUncompressedSize > 0) {
                msgTrimmedSize = Math.max(0, this.prefixedSizeVarInt(msgUncompressedSize) - msgSize);
            }
            if (tx) {
                entry = entry.increaseTx(msgPrefixedSize, msgPrefixedSize + msgTrimmedSize);
            }
            if (rx) {
                entry = entry.increaseRx(msgPrefixedSize, msgPrefixedSize + msgTrimmedSize);
            }
            msg.readerIndex(msgFrom);
            return entry;
        } else {
            var msgId = this.readVarInt(msg).orElse(-1);
            if (msgId == 0x03) {
                var msgCompressBound = this.readVarInt(msg).orElse(-1);
                entry = entry.markCompressed(msgCompressBound >= 0);
            }
            if (tx) {
                entry = entry.increaseTx(msgPrefixedSize, msgPrefixedSize);
            }
            if (rx) {
                entry = entry.increaseRx(msgPrefixedSize, msgPrefixedSize);
            }
            msg.readerIndex(msgFrom);
            return entry;
        }
    }

    private int prefixedSizeVarInt(int size) {
        return (31 - Integer.numberOfLeadingZeros(size)) / 7 + 1 + size;
    }

    private OptionalInt readVarInt(ByteBuf msg) {
        var result = 0;
        var readable = msg.readableBytes();
        for (var i = 0; i < 5; ++i) {
            if (i >= readable) {
                return OptionalInt.empty();
            }
            var b = msg.readByte();
            result |= (b & (1 << 7) - 1) << i * 7;
            if ((b >> 7) == 0) {
                return OptionalInt.of(result);
            }
        }
        return OptionalInt.empty();
    }

    @Override
    public void close() {
        this.enabled = false;
    }

    private class BackendInitializer extends ChannelInitializer<Channel> {
        private final ChannelInitializer<Channel> original;

        public BackendInitializer(ChannelInitializer<Channel> original) {
            this.original = original;
        }

        @Override
        protected void initChannel(@Nonnull Channel ch) throws Exception {
            try {
                Objects.requireNonNull(ciInitCMethod).invoke(this.original, ch);
                // noinspection RedundantThrows
                ch.pipeline().addBefore("frame-encoder", RX_NAME, new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(@Nonnull ChannelHandlerContext ctx,
                                            @Nonnull Object msg) throws Exception {
                        if (GongdaobeiVelocityBandwidthCounter.this.enabled && msg instanceof ByteBuf buf) {
                            var addr = (InetSocketAddress) ch.remoteAddress();
                            GongdaobeiVelocityBandwidthCounter.this.backendEntries.compute(
                                    HostAndPort.fromParts(addr.getHostString(), addr.getPort()),
                                    (key, value) -> GongdaobeiVelocityBandwidthCounter.this.calculateSize(
                                            false, true, buf, buf.readableBytes(), Entry.emptyIfNull(value)));
                        }
                        ctx.fireChannelRead(msg);
                    }
                });
                // noinspection RedundantThrows
                ch.pipeline().addBefore("frame-encoder", TX_NAME, new ChannelOutboundHandlerAdapter() {
                    private @Nullable ByteBuf cum = null;

                    @Override
                    public void write(ChannelHandlerContext ctx,
                                      Object msg, ChannelPromise promise) throws Exception {
                        if (GongdaobeiVelocityBandwidthCounter.this.enabled && msg instanceof ByteBuf buf) {
                            var old = this.cum == null ? Unpooled.EMPTY_BUFFER : this.cum;
                            this.cum = MERGE_CUMULATOR.cumulate(ctx.alloc(), old, buf.retainedSlice());
                            while (true) {
                                var from = this.cum.readerIndex();
                                var size = GongdaobeiVelocityBandwidthCounter.this.readVarInt(this.cum);
                                if (size.isEmpty() || size.getAsInt() > this.cum.readableBytes()) {
                                    this.cum.readerIndex(from);
                                    break;
                                }
                                if (size.getAsInt() > 0) {
                                    var addr = (InetSocketAddress) ch.remoteAddress();
                                    GongdaobeiVelocityBandwidthCounter.this.backendEntries.compute(
                                            HostAndPort.fromParts(addr.getHostString(), addr.getPort()),
                                            (key, value) -> GongdaobeiVelocityBandwidthCounter.this.calculateSize(
                                                    true, false, this.cum, size.getAsInt(), Entry.emptyIfNull(value)));
                                    this.cum.skipBytes(size.getAsInt());
                                }
                            }
                            // release the cumulated buf if it has been fully read
                            if (this.cum != null && !this.cum.isReadable()) {
                                this.cum.release();
                                this.cum = null;
                            }
                        }
                        ctx.write(msg, promise);
                    }

                    @Override
                    public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
                        // release the cumulated buf
                        if (this.cum != null) {
                            this.cum.release();
                            this.cum = null;
                        }
                        ctx.close(promise);
                    }
                });
            } catch (Throwable e) {
                var logger = GongdaobeiVelocityBandwidthCounter.this.logger;
                var err = e instanceof Exception ex ? ex : new RuntimeException(e);
                logger.log(Level.SEVERE, "Failed to inject packet size collector: " + e.getMessage(), err);
                throw err;
            }
        }
    }

    private class ServerInitializer extends ChannelInitializer<Channel> {
        private final ChannelInitializer<Channel> original;

        public ServerInitializer(ChannelInitializer<Channel> original) {
            this.original = original;
        }

        @Override
        protected void initChannel(@Nonnull Channel ch) throws Exception {
            try {
                Objects.requireNonNull(ciInitCMethod).invoke(this.original, ch);
                // noinspection RedundantThrows
                ch.pipeline().addBefore("frame-encoder", RX_NAME, new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(@Nonnull ChannelHandlerContext ctx,
                                            @Nonnull Object msg) throws Exception {
                        if (GongdaobeiVelocityBandwidthCounter.this.enabled && msg instanceof ByteBuf buf) {
                            GongdaobeiVelocityBandwidthCounter.this.serverEntry.updateAndGet(
                                    v -> GongdaobeiVelocityBandwidthCounter.this.calculateSize(
                                            false, true, buf, buf.readableBytes(), Entry.emptyIfNull(v)));
                        }
                        ctx.fireChannelRead(msg);
                    }
                });
                // noinspection RedundantThrows
                ch.pipeline().addBefore("frame-encoder", TX_NAME, new ChannelOutboundHandlerAdapter() {
                    private @Nullable ByteBuf cum = null;

                    @Override
                    public void write(ChannelHandlerContext ctx,
                                      Object msg, ChannelPromise promise) throws Exception {
                        if (GongdaobeiVelocityBandwidthCounter.this.enabled && msg instanceof ByteBuf buf) {
                            var old = this.cum == null ? Unpooled.EMPTY_BUFFER : this.cum;
                            this.cum = MERGE_CUMULATOR.cumulate(ctx.alloc(), old, buf.retainedSlice());
                            while (true) {
                                var from = this.cum.readerIndex();
                                var size = GongdaobeiVelocityBandwidthCounter.this.readVarInt(this.cum);
                                if (size.isEmpty() || size.getAsInt() > this.cum.readableBytes()) {
                                    this.cum.readerIndex(from);
                                    break;
                                }
                                if (size.getAsInt() > 0) {
                                    GongdaobeiVelocityBandwidthCounter.this.serverEntry.updateAndGet(
                                            value -> GongdaobeiVelocityBandwidthCounter.this.calculateSize(
                                                    true, false, this.cum, size.getAsInt(), Entry.emptyIfNull(value)));
                                    this.cum.skipBytes(size.getAsInt());
                                }
                            }
                            // release the cumulated buf if it has been fully read
                            if (this.cum != null && !this.cum.isReadable()) {
                                this.cum.release();
                                this.cum = null;
                            }
                        }
                        ctx.write(msg, promise);
                    }

                    @Override
                    public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
                        // release the cumulated buf
                        if (this.cum != null) {
                            this.cum.release();
                            this.cum = null;
                        }
                        ctx.close(promise);
                    }
                });
            } catch (Throwable e) {
                var logger = GongdaobeiVelocityBandwidthCounter.this.logger;
                var err = e instanceof Exception ex ? ex : new RuntimeException(e);
                logger.log(Level.SEVERE, "Failed to inject packet size collector: " + e.getMessage(), err);
                throw err;
            }
        }
    }

    public record Entry(boolean compressed, long tx, long rx,
                        long txUncompressed, long rxUncompressed, OffsetDateTime lastUpdate) {
        public static Entry emptyIfNull(@Nullable Entry entry) {
            return entry != null ? entry : new Entry(false, 0L, 0L, 0L, 0L, OffsetDateTime.now());
        }

        public Entry markCompressed(boolean compressed) {
            return new Entry(compressed, this.tx, this.rx,
                    this.txUncompressed, this.rxUncompressed, this.lastUpdate);
        }

        public Entry increaseTx(int compressed, int uncompressed) {
            return new Entry(this.compressed, this.tx + compressed, this.rx,
                    this.txUncompressed + uncompressed, this.rxUncompressed, OffsetDateTime.now());
        }

        public Entry increaseRx(int compressed, int uncompressed) {
            return new Entry(this.compressed, this.tx, this.rx + compressed,
                    this.txUncompressed, this.rxUncompressed + uncompressed, OffsetDateTime.now());
        }
    }
}
