package org.teacon.gongdaobei;

import com.google.common.base.Preconditions;
import com.google.common.collect.*;
import com.google.common.net.HostAndPort;

import java.util.*;
import java.util.function.BiFunction;

public final class GongdaobeiRegistry {
    private final ImmutableSet<HostAndPort> onlineExternalAddrSet;
    private final ImmutableMap<HostAndPort, GongdaobeiServiceParams> onlineParamsByInternalAddr;
    private final ImmutableMap<HostAndPort, GongdaobeiServiceParams> retiredParamsByInternalAddr;
    private final ImmutableBiMap<HostAndPort, String> namesByInternal;
    private final ImmutableSet<HostAndPort> fallbackInternalAddrSet;
    private final ImmutableSet<HostAndPort> latestFallbackInternalAddrSet;
    private final ImmutableSetMultimap<HostAndPort, HostAndPort> targetedInternalAddrMap;
    private final ImmutableSetMultimap<HostAndPort, HostAndPort> latestTargetedInternalAddrMap;

    private GongdaobeiRegistry(Set<HostAndPort> whitelistExternalSet,
                               Map<HostAndPort, GongdaobeiServiceParams> onlineParamsByInternalAddr,
                               Map<HostAndPort, GongdaobeiServiceParams> retiredParamsByInternalAddr,
                               BiMap<HostAndPort, String> namesByInternal,
                               Set<HostAndPort> fallbackInternalAddrSet,
                               Set<HostAndPort> latestFallbackInternalAddrSet,
                               SetMultimap<HostAndPort, HostAndPort> targetedInternalAddrMap,
                               SetMultimap<HostAndPort, HostAndPort> latestTargetedInternalAddrMap) {
        this.onlineExternalAddrSet = ImmutableSet.copyOf(Sets.union(whitelistExternalSet, targetedInternalAddrMap.keySet()));
        this.onlineParamsByInternalAddr = ImmutableMap.copyOf(onlineParamsByInternalAddr);
        this.retiredParamsByInternalAddr = ImmutableMap.copyOf(retiredParamsByInternalAddr);
        this.namesByInternal = ImmutableBiMap.copyOf(namesByInternal);
        this.fallbackInternalAddrSet = ImmutableSet.copyOf(fallbackInternalAddrSet);
        this.latestFallbackInternalAddrSet = ImmutableSet.copyOf(latestFallbackInternalAddrSet);
        this.targetedInternalAddrMap = ImmutableSetMultimap.copyOf(targetedInternalAddrMap);
        this.latestTargetedInternalAddrMap = ImmutableSetMultimap.copyOf(latestTargetedInternalAddrMap);
    }

    public Set<HostAndPort> getInternalAddrOnline() {
        return this.onlineParamsByInternalAddr.keySet();
    }

    public Set<HostAndPort> getInternalAddrRetired() {
        return this.retiredParamsByInternalAddr.keySet();
    }

    public Set<HostAndPort> getFallbackInternalAddrOnline(boolean latestOnly) {
        return latestOnly ? this.latestFallbackInternalAddrSet : this.fallbackInternalAddrSet;
    }

    public Set<HostAndPort> getTargetedExternalAddrOnline() {
        return this.onlineExternalAddrSet;
    }

    public Set<HostAndPort> getTargetedInternalAddrOnline(HostAndPort externalAddr, boolean latestOnly) {
        return latestOnly ? this.latestTargetedInternalAddrMap.get(externalAddr) : this.targetedInternalAddrMap.get(externalAddr);
    }

    public GongdaobeiServiceParams getParams(HostAndPort internalAddr) {
        return Preconditions.checkNotNull(this.onlineParamsByInternalAddr.get(internalAddr), "addr %s not existed", internalAddr);
    }

    public static final class Builder {
        private final BiFunction<? super HostAndPort, ? super GongdaobeiServiceParams, String> nameGenerator;
        private final Set<HostAndPort> whitelistExternalSet = new LinkedHashSet<>();
        private final Map<HostAndPort, GongdaobeiServiceParams> onlineParamsByInternal = new LinkedHashMap<>();
        private final Map<HostAndPort, GongdaobeiServiceParams> retiredParamsByInternal = new LinkedHashMap<>();
        private final BiMap<HostAndPort, String> namesByInternal = HashBiMap.create();
        private final Set<HostAndPort> fallbackInternalSet = new LinkedHashSet<>();
        private final Set<HostAndPort> latestFallbackInternalSet = new LinkedHashSet<>();
        private GongdaobeiTomlConfig.VersionPattern latestFallback = new GongdaobeiTomlConfig.VersionPattern();
        private final SetMultimap<HostAndPort, HostAndPort> targetedInternalMap = LinkedHashMultimap.create();
        private final SetMultimap<HostAndPort, HostAndPort> latestTargetedInternalMap = LinkedHashMultimap.create();
        private final Map<HostAndPort, GongdaobeiTomlConfig.VersionPattern> latestTargeted = new HashMap<>();

        public Builder(BiFunction<? super HostAndPort, ? super GongdaobeiServiceParams, String> nameGenerator) {
            this.nameGenerator = nameGenerator;
        }

        public Builder whitelist(HostAndPort externalAddr) {
            Preconditions.checkArgument(this.whitelistExternalSet.add(externalAddr), "duplicate whitelist addr %s", externalAddr);
            return this;
        }

        public Builder params(HostAndPort internalAddr, GongdaobeiServiceParams params) {
            var name = this.nameGenerator.apply(internalAddr, params);
            if (params.isRetired) {
                Preconditions.checkArgument(!this.onlineParamsByInternal.containsKey(internalAddr), "duplicate addr %s", internalAddr);
                Preconditions.checkArgument(this.retiredParamsByInternal.put(internalAddr, params) == null, "duplicate addr %s", internalAddr);
                Preconditions.checkArgument(this.namesByInternal.forcePut(internalAddr, name) == null, "duplicate name for addr %s", internalAddr);
            } else {
                Preconditions.checkArgument(!this.retiredParamsByInternal.containsKey(internalAddr), "duplicate addr %s", internalAddr);
                Preconditions.checkArgument(this.onlineParamsByInternal.put(internalAddr, params) == null, "duplicate addr %s", internalAddr);
                Preconditions.checkArgument(this.namesByInternal.forcePut(internalAddr, name) == null, "duplicate name for addr %s", internalAddr);
                if (params.isFallback) {
                    this.fallbackInternalSet.add(internalAddr);
                    var cmp = params.version.compareTo(this.latestFallback);
                    if (cmp > 0) {
                        this.latestFallbackInternalSet.clear();
                        this.latestFallback = params.version;
                    }
                    if (cmp == 0) {
                        this.latestFallbackInternalSet.add(internalAddr);
                    }
                }
                for (var externalAddr : params.externalAddresses) {
                    this.targetedInternalMap.put(externalAddr, internalAddr);
                    this.latestTargeted.compute(externalAddr, (k, v) -> {
                        var latestTargeted = v == null ? new GongdaobeiTomlConfig.VersionPattern() : v;
                        var cmp = params.version.compareTo(latestTargeted);
                        if (cmp > 0) {
                            this.latestTargetedInternalMap.removeAll(k);
                            latestTargeted = params.version;
                        }
                        if (cmp == 0) {
                            this.latestTargetedInternalMap.put(k, internalAddr);
                        }
                        return latestTargeted;
                    });
                }
            }
            return this;
        }

        public GongdaobeiRegistry build() {
            return new GongdaobeiRegistry(
                    this.whitelistExternalSet,
                    this.onlineParamsByInternal,
                    this.retiredParamsByInternal,
                    this.namesByInternal,
                    this.fallbackInternalSet,
                    this.latestFallbackInternalSet,
                    this.targetedInternalMap,
                    this.latestTargetedInternalMap);
        }
    }
}
