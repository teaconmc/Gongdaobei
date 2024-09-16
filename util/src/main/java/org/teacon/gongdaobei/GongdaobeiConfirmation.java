package org.teacon.gongdaobei;

import com.google.common.collect.ImmutableMap;
import com.google.common.net.HostAndPort;

import java.net.InetSocketAddress;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

public sealed interface GongdaobeiConfirmation extends Predicate<GongdaobeiServiceParams> {
    @Override
    String toString();

    String toPlayerKey();

    HostAndPort internalAddress();

    @Override
    boolean test(GongdaobeiServiceParams params);

    static Map<GongdaobeiConfirmation, GongdaobeiServiceParams> filter(InetSocketAddress playerAddr,
                                                                       GongdaobeiRegistry registry) {
        var externalChoices = new LinkedHashSet<HostAndPort>();
        for (var addr : registry.getTargetedExternalAddrOnline()) {
            var sameHost = addr.getHost().equals(playerAddr.getHostString());
            var samePort = !addr.hasPort() || addr.getPort() == playerAddr.getPort();
            if (sameHost && samePort) {
                externalChoices.add(addr);
            }
        }
        var result = ImmutableMap.
                <GongdaobeiConfirmation, GongdaobeiServiceParams>
                builderWithExpectedSize(externalChoices.size() + 1);
        if (externalChoices.isEmpty()) {
            var fallbackInternals = registry.getFallbackInternalAddrOnline(true);
            for (var internalAddr : fallbackInternals) {
                result.put(new Fallback(internalAddr), registry.getParams(internalAddr));
            }
        }
        for (var externalAddr : externalChoices) {
            var targetedInternals = registry.getTargetedInternalAddrOnline(externalAddr, true);
            for (var internalAddr : targetedInternals) {
                result.put(new Targeted(internalAddr, externalAddr), registry.getParams(internalAddr));
            }
        }
        return result.build();
    }

    static Optional<GongdaobeiConfirmation> tryParse(String input) {
        try {
            return Optional.of(parse(input));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    static GongdaobeiConfirmation parse(String input) {
        if (input.startsWith("targeted:")) {
            var split = input.substring(9).split("/");
            if (split.length == 2) {
                var externalAddr = GongdaobeiUtil.getHostAndPort(split[0], "", false);
                var internalAddr = GongdaobeiUtil.getHostAndPort(split[1], "", true);
                if (externalAddr.isPresent() && internalAddr.isPresent()) {
                    return new Targeted(internalAddr.get(), externalAddr.get());
                }
            }
        }
        if (input.startsWith("fallback:")) {
            var split = input.substring(9).split("/");
            if (split.length == 1) {
                var internalAddr = GongdaobeiUtil.getHostAndPort(split[0], "", true);
                if (internalAddr.isPresent()) {
                    return new Fallback(internalAddr.get());
                }
            }
        }
        throw new IllegalArgumentException("unrecognized format");
    }

    record Targeted(HostAndPort internalAddress, HostAndPort externalAddress) implements GongdaobeiConfirmation {
        @Override
        public String toString() {
            return "targeted:" + this.externalAddress + "/" + this.internalAddress;
        }

        @Override
        public String toPlayerKey() {
            return "targeted:" + this.externalAddress;
        }

        @Override
        public boolean test(GongdaobeiServiceParams params) {
            return params.externalAddresses.contains(this.externalAddress);
        }
    }

    record Fallback(HostAndPort internalAddress) implements GongdaobeiConfirmation {
        @Override
        public String toString() {
            return "fallback:" + this.internalAddress;
        }

        @Override
        public String toPlayerKey() {
            return "fallback";
        }

        @Override
        public boolean test(GongdaobeiServiceParams params) {
            return params.isFallback;
        }
    }
}
