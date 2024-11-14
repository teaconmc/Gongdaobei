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

import com.google.common.base.CaseFormat;
import com.google.common.net.HostAndPort;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.CodecException;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;

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

import static io.netty.handler.codec.ByteToMessageDecoder.COMPOSITE_CUMULATOR;

public final class GongdaobeiVelocityBandwidthCounter implements Closeable {
    private static final String TX_NAME = "gongdaobei-tx-bandwidth-counter";
    private static final String RX_NAME = "gongdaobei-rx-bandwidth-counter";

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
                var cm = Handles.vsCmVar.get(plugin.server);
                var scih = Handles.cmGetSciMethod.invoke(cm);
                var bcih = Handles.cmGetBciMethod.invoke(cm);
                var oldServer = (ChannelInitializer<Channel>) Handles.scihGetMethod.invoke(scih);
                var oldBackend = (ChannelInitializer<Channel>) Handles.bcihGetMethod.invoke(bcih);
                this.logger.info("Injecting packet collector to server handler ...");
                Handles.scihSetMethod.invoke(scih, new ServerInitializer(oldServer));
                this.logger.info("Injecting packet collector to backend handler ...");
                Handles.bcihSetMethod.invoke(bcih, new BackendInitializer(oldBackend));
            } catch (Throwable e) {
                var ex = e instanceof RuntimeException re ? re : new RuntimeException(e);
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

    private Entry calculateSize(NetworkChannel channel, ByteBuf msg, Entry entry) {
        try {
            if (msg.writerIndex() >= 5_000_000) {
                throw new CodecException("Buffered message too large (" + msg.writerIndex() + " bytes)");
            } else if (entry.txWithFrames() && channel.tx()) {
                return this.calculateSizeWithFrames(channel, msg, entry);
            } else if (entry.compressed()) {
                return this.calculateSizeCompressed(channel, msg, entry);
            } else {
                return this.calculateSizeUncompressed(channel, msg, entry);
            }
        } catch (CodecException e) {
            var joiner = new StringJoiner(", ", "Failed to read packet: ", "");
            joiner.add(CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.LOWER_HYPHEN, channel.name()));
            joiner.add(entry.txWithFrames() && channel.tx() ? "with-frames" : "without-frames");
            joiner.add(entry.compressed() ? "zlib-enabled" : "zlib-disabled");
            joiner.add(String.format("index: %08x", msg.readerIndex()));
            if (msg.isReadable()) {
                joiner.add("hexdump: \n" + ByteBufUtil.prettyHexDump(msg, 0, msg.writerIndex()));
            }
            this.logger.log(Level.WARNING, joiner.toString());
            throw channel.wrap(e);
        }
    }

    private Entry calculateSizeWithFrames(NetworkChannel channel, ByteBuf msg, Entry entry) {
        while (true) {
            var msgFrom = msg.readerIndex();
            var msgSizeOptional = this.readVarInt(msg, 21);
            if (msgSizeOptional.isEmpty() || msgSizeOptional.getAsInt() > msg.readableBytes()) {
                msg.readerIndex(msgFrom);
                return entry;
            }
            var msgSize = msgSizeOptional.getAsInt();
            if (msgSize > 0) {
                msg.discardSomeReadBytes();
                if (entry.compressed()) {
                    entry = this.calculateSizeCompressed(channel, msg.readSlice(msgSize), entry);
                } else {
                    entry = this.calculateSizeUncompressed(channel, msg.readSlice(msgSize), entry);
                }
            }
        }
    }

    private Entry calculateSizeCompressed(NetworkChannel channel, ByteBuf msg, Entry entry) {
        var msgTrimmed = 0;
        var msgSize = msg.readableBytes();
        var msgUncompressed = this.readVarInt(msg, 32).orElse(0);
        if (msgUncompressed > 0) {
            msgTrimmed = Math.max(0, this.prefixedSizeVarInt(msgUncompressed) - msgSize);
        }
        var msgPrefixedSize = this.prefixedSizeVarInt(msgSize);
        if (channel.tx()) {
            entry = entry.increaseTx(msgPrefixedSize, msgPrefixedSize + msgTrimmed);
        }
        if (channel.rx()) {
            entry = entry.increaseRx(msgPrefixedSize, msgPrefixedSize + msgTrimmed);
        }
        msg.skipBytes(msg.readableBytes());
        return entry;
    }

    private Entry calculateSizeUncompressed(NetworkChannel channel, ByteBuf msg, Entry entry) {
        var msgSize = msg.readableBytes();
        var msgId = this.readVarInt(msg, 32).orElse(-1);
        if (channel.compress(msgId)) {
            var msgCompressBound = this.readVarInt(msg, 31).orElse(-1);
            entry = entry.markCompressed(msgCompressBound >= 0);
            if (channel.tx()) {
                entry = entry.markTxWithFrames();
            }
        }
        var msgPrefixedSize = this.prefixedSizeVarInt(msgSize);
        if (channel.tx()) {
            entry = entry.increaseTx(msgPrefixedSize, msgPrefixedSize);
        }
        if (channel.rx()) {
            entry = entry.increaseRx(msgPrefixedSize, msgPrefixedSize);
        }
        msg.skipBytes(msg.readableBytes());
        return entry;
    }

    private int prefixedSizeVarInt(int size) {
        return (38 - Integer.numberOfLeadingZeros(size)) / 7 + size;
    }

    private OptionalInt readVarInt(ByteBuf msg, int allowedMaximumBits) {
        // bit 0-6
        if (!msg.isReadable()) {
            return OptionalInt.empty();
        }
        var b0 = msg.readByte();
        if ((b0 & 0xFF & ~0 << Math.min(allowedMaximumBits, 8)) != 0) {
            throw new CodecException(String.format("Illegal first byte " +
                    "of var int: 0x%02x (allowed bits: %d)", b0, allowedMaximumBits));
        }
        if (b0 >= 0) {
            return OptionalInt.of(b0);
        }
        // bit 7-13
        if (!msg.isReadable()) {
            return OptionalInt.empty();
        }
        var b1 = msg.readByte();
        if ((b1 & 0xFF & ~0 << Math.min(allowedMaximumBits, 15) - 7) != 0) {
            throw new CodecException(String.format("Illegal second byte " +
                    "of var int: 0x%02x (allowed bits: %d)", b1, allowedMaximumBits));
        }
        if (b1 >= 0) {
            return OptionalInt.of(b0 & 0x7F | b1 << 7);
        }
        // bit 14-20
        if (!msg.isReadable()) {
            return OptionalInt.empty();
        }
        var b2 = msg.readByte();
        if ((b2 & 0xFF & ~0 << Math.min(allowedMaximumBits, 22) - 14) != 0) {
            throw new CodecException(String.format("Illegal third byte " +
                    "of var int: 0x%02x (allowed bits: %d)", b2, allowedMaximumBits));
        }
        if (b2 >= 0) {
            return OptionalInt.of(b0 & 0x7F | b1 << 7 & 0x3FFF | b2 << 14);
        }
        // bit 21-27
        if (!msg.isReadable()) {
            return OptionalInt.empty();
        }
        var b3 = msg.readByte();
        if ((b3 & 0xFF & ~0 << Math.min(allowedMaximumBits, 29) - 21) != 0) {
            throw new CodecException(String.format("Illegal fourth byte " +
                    "of var int: 0x%02x (allowed bits: %d)", b3, allowedMaximumBits));
        }
        if (b3 >= 0) {
            return OptionalInt.of(b0 & 0x7F | b1 << 7 & 0x3FFF | b2 << 14 & 0x1FFFFF | b3);
        }
        // bit 28-31
        if (!msg.isReadable()) {
            return OptionalInt.empty();
        }
        var b4 = msg.readByte();
        if ((b4 & 0xFF & ~0 << Math.min(allowedMaximumBits, 32) - 28) != 0) {
            throw new CodecException(String.format("Illegal fifth byte " +
                    "of var int: 0x%02x (allowed bits: %d)", b4, allowedMaximumBits));
        }
        return OptionalInt.of(b0 & 0x7F | b1 << 7 & 0x3FFF | b2 << 14 & 0x1FFFFF | b3 << 21 & 0xFFFFFFF | b4 << 28);
    }

    @Override
    public void close() {
        this.enabled = false;
    }

    private static final class Handles {
        private static final VarHandle vsCmVar;
        private static final MethodHandle scihGetMethod;
        private static final MethodHandle bcihGetMethod;
        private static final MethodHandle scihSetMethod;
        private static final MethodHandle bcihSetMethod;
        private static final MethodHandle ciInitCMethod;
        private static final MethodHandle cmGetSciMethod;
        private static final MethodHandle cmGetBciMethod;

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
                throw new RuntimeException(e);
            }
        }

        private Handles() {
            throw new UnsupportedOperationException();
        }
    }

    private final class BackendInitializer extends ChannelInitializer<Channel> {
        private final ChannelInitializer<Channel> original;

        public BackendInitializer(ChannelInitializer<Channel> original) {
            this.original = original;
        }

        @Override
        protected void initChannel(@Nonnull Channel ch) throws Exception {
            try {
                Handles.ciInitCMethod.invoke(this.original, ch);
                // noinspection RedundantThrows
                ch.pipeline().addAfter("frame-encoder", RX_NAME, new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(@Nonnull ChannelHandlerContext ctx,
                                            @Nonnull Object msg) throws Exception {
                        if (GongdaobeiVelocityBandwidthCounter.this.enabled && msg instanceof ByteBuf buf) {
                            var retained = buf.retainedSlice();
                            try {
                                var addr = (InetSocketAddress) ch.remoteAddress();
                                GongdaobeiVelocityBandwidthCounter.this.backendEntries.compute(
                                        HostAndPort.fromParts(addr.getHostString(), addr.getPort()),
                                        (key, value) -> GongdaobeiVelocityBandwidthCounter.this.calculateSize(
                                                NetworkChannel.SERVER_OUTGOING, retained, Entry.emptyIfNull(value)));
                            } finally {
                                retained.release();
                                ctx.fireChannelRead(msg);
                            }
                        } else {
                            ctx.fireChannelRead(msg);
                        }
                    }
                });
                // noinspection RedundantThrows
                ch.pipeline().addAfter("frame-encoder", TX_NAME, new ChannelOutboundHandlerAdapter() {
                    private @Nullable ByteBuf cum = null;

                    @Override
                    public void write(ChannelHandlerContext ctx,
                                      Object msg, ChannelPromise promise) throws Exception {
                        if (GongdaobeiVelocityBandwidthCounter.this.enabled && msg instanceof ByteBuf buf) {
                            try {
                                var addr = (InetSocketAddress) ch.remoteAddress();
                                var old = this.cum == null ? Unpooled.EMPTY_BUFFER : this.cum;
                                this.cum = COMPOSITE_CUMULATOR.cumulate(ctx.alloc(), old, buf.retainedSlice());
                                // noinspection DataFlowIssue
                                GongdaobeiVelocityBandwidthCounter.this.backendEntries.compute(
                                        HostAndPort.fromParts(addr.getHostString(), addr.getPort()),
                                        (key, value) -> GongdaobeiVelocityBandwidthCounter.this.calculateSize(
                                                NetworkChannel.SERVER_INCOMING, this.cum, Entry.emptyIfNull(value)));
                            } finally {
                                // release the cumulated buf if it has been fully read
                                if (this.cum != null && !this.cum.isReadable()) {
                                    this.cum.release();
                                    this.cum = null;
                                }
                                ctx.write(msg, promise);
                            }
                        } else {
                            ctx.write(msg, promise);
                        }
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

    private final class ServerInitializer extends ChannelInitializer<Channel> {
        private final ChannelInitializer<Channel> original;

        public ServerInitializer(ChannelInitializer<Channel> original) {
            this.original = original;
        }

        @Override
        protected void initChannel(@Nonnull Channel ch) throws Exception {
            try {
                Handles.ciInitCMethod.invoke(this.original, ch);
                // noinspection RedundantThrows
                ch.pipeline().addAfter("frame-encoder", RX_NAME, new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(@Nonnull ChannelHandlerContext ctx,
                                            @Nonnull Object msg) throws Exception {
                        if (GongdaobeiVelocityBandwidthCounter.this.enabled && msg instanceof ByteBuf buf) {
                            var retained = buf.retainedSlice();
                            try {
                                GongdaobeiVelocityBandwidthCounter.this.serverEntry.updateAndGet(
                                        value -> GongdaobeiVelocityBandwidthCounter.this.calculateSize(
                                                NetworkChannel.PLAYER_INCOMING, retained, Entry.emptyIfNull(value)));
                            } finally {
                                retained.release();
                                ctx.fireChannelRead(msg);
                            }
                        } else {
                            ctx.fireChannelRead(msg);
                        }
                    }
                });
                // noinspection RedundantThrows
                ch.pipeline().addAfter("frame-encoder", TX_NAME, new ChannelOutboundHandlerAdapter() {
                    private @Nullable ByteBuf cum = null;

                    @Override
                    public void write(ChannelHandlerContext ctx,
                                      Object msg, ChannelPromise promise) throws Exception {
                        if (GongdaobeiVelocityBandwidthCounter.this.enabled && msg instanceof ByteBuf buf) {
                            try {
                                var old = this.cum == null ? Unpooled.EMPTY_BUFFER : this.cum;
                                this.cum = COMPOSITE_CUMULATOR.cumulate(ctx.alloc(), old, buf.retainedSlice());
                                // noinspection DataFlowIssue
                                GongdaobeiVelocityBandwidthCounter.this.serverEntry.updateAndGet(
                                        value -> GongdaobeiVelocityBandwidthCounter.this.calculateSize(
                                                NetworkChannel.PLAYER_OUTGOING, this.cum, Entry.emptyIfNull(value)));
                            } finally {
                                // release the cumulated buf if it has been fully read
                                if (this.cum != null && !this.cum.isReadable()) {
                                    this.cum.release();
                                    this.cum = null;
                                }
                                ctx.write(msg, promise);
                            }
                        } else {
                            ctx.write(msg, promise);
                        }
                    }

                    @Override
                    public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
                        // release the cumulated buf
                        if (this.cum != null) {
                            this.cum.discardSomeReadBytes().release();
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
                        long txUncompressed, long rxUncompressed, boolean txWithFrames, OffsetDateTime lastUpdate) {
        public static Entry emptyIfNull(@Nullable Entry entry) {
            return entry != null ? entry : new Entry(false, 0L, 0L, 0L, 0L, false, OffsetDateTime.now());
        }

        public Entry markTxWithFrames() {
            return new Entry(this.compressed, this.tx, this.rx,
                    this.txUncompressed, this.rxUncompressed, true, this.lastUpdate);
        }

        public Entry markCompressed(boolean compressed) {
            return new Entry(compressed, this.tx, this.rx,
                    this.txUncompressed, this.rxUncompressed, this.txWithFrames, this.lastUpdate);
        }

        public Entry increaseTx(int compressed, int uncompressed) {
            return new Entry(this.compressed, this.tx + compressed, this.rx,
                    this.txUncompressed + uncompressed, this.rxUncompressed, this.txWithFrames, OffsetDateTime.now());
        }

        public Entry increaseRx(int compressed, int uncompressed) {
            return new Entry(this.compressed, this.tx, this.rx + compressed,
                    this.txUncompressed, this.rxUncompressed + uncompressed, this.txWithFrames, OffsetDateTime.now());
        }
    }

    public enum NetworkChannel {
        PLAYER_INCOMING, PLAYER_OUTGOING, SERVER_INCOMING, SERVER_OUTGOING;

        public boolean tx() {
            return this == PLAYER_OUTGOING || this == SERVER_INCOMING;
        }

        public boolean rx() {
            return this == PLAYER_INCOMING || this == SERVER_OUTGOING;
        }

        public boolean compress(int id) {
            return (this == PLAYER_INCOMING || this == SERVER_INCOMING) && id == 0x03;
        }

        public CodecException wrap(RuntimeException e) {
            return switch (this) {
                case PLAYER_INCOMING, SERVER_INCOMING -> new DecoderException(e);
                case PLAYER_OUTGOING, SERVER_OUTGOING -> new EncoderException(e);
            };
        }
    }
}
