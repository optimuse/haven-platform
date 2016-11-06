/*
 * Copyright 2016 Code Above Lab LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.codeabovelab.dm.cluman.ds.clusters;

import com.codeabovelab.dm.cluman.cluster.docker.management.DockerService;
import com.codeabovelab.dm.cluman.cluster.filter.FilterFactory;
import com.codeabovelab.dm.common.kv.DeleteDirOptions;
import com.codeabovelab.dm.common.kv.KeyValueStorage;
import com.codeabovelab.dm.common.kv.KvUtils;
import com.codeabovelab.dm.common.kv.WriteOptions;
import com.codeabovelab.dm.common.kv.mapping.KvMapperFactory;
import com.codeabovelab.dm.cluman.ds.nodes.NodeStorage;
import com.codeabovelab.dm.cluman.ds.swarm.DockerServices;
import com.codeabovelab.dm.cluman.model.*;
import com.codeabovelab.dm.cluman.reconfig.ReConfigObject;
import com.codeabovelab.dm.cluman.reconfig.ReConfigurable;
import com.codeabovelab.dm.cluman.validate.ExtendedAssert;
import com.codeabovelab.dm.common.mb.MessageBus;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * swarm discovery storage implementation based on eureka
 */
@ReConfigurable
@Primary
@Component
public class DiscoveryStorageImpl implements DiscoveryStorage {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final DockerServices services;
    private final NodeStorage nodeStorage;
    private final ConcurrentMap<String, NodesGroup> clusters = new ConcurrentHashMap<>();
    private final KvMapperFactory kvmf;
    private final String prefix;
    private final FilterFactory filterFactory;

    private final MessageBus<NodesGroupEvent> messageBus;

    @Autowired
    public DiscoveryStorageImpl(KvMapperFactory kvmf,
                                FilterFactory filterFactory,
                                DockerServices dockerServices,
                                NodeStorage nodeStorage,
                                @Qualifier(NodesGroupEvent.BUS) MessageBus<NodesGroupEvent> messageBus) {
        this.kvmf = kvmf;
        this.services = dockerServices;
        this.nodeStorage = nodeStorage;
        this.messageBus = messageBus;
        KeyValueStorage storage = kvmf.getStorage();
        this.filterFactory = filterFactory;
        this.prefix = storage.getDockMasterPrefix() + "/clusters/";
        //create clusters, its need for empty etcd database
        storage.setdir(this.prefix, WriteOptions.builder().build());
        storage.subscriptions().subscribeOnKey(e -> {
            String key = KvUtils.name(prefix, e.getKey());
            if(key == null) {
                return;
            }
            switch (e.getAction()) {
                case DELETE:
                    this.clusters.remove(key);
                    fireGroupEvent(key, StandardActions.DELETE);
                    break;
                case CREATE:
                    fireGroupEvent(key, StandardActions.CREATE);
                    break;
                default:
                    NodesGroup reg = this.clusters.get(key);
                    fireGroupEvent(key, StandardActions.UPDATE);
                    if(reg instanceof AbstractNodesGroup) {
                        //do reloading cache
                        ((AbstractNodesGroup<?, ?>)reg).getMapper().load();
                    }
            }
        }, prefix + "*");

        filterFactory.registerFilter(new OrphansNodeFilterFactory(this));
        // virtual cluster for any nodes
        getOrCreateGroup(new DefaultNodesGroupConfig(GROUP_ID_ALL, FilterFactory.ANY));
        // virtual cluster for nodes without cluster
        getOrCreateGroup(new DefaultNodesGroupConfig(GROUP_ID_ORPHANS, OrphansNodeFilterFactory.FILTER));
    }

    public void load() {
        this.kvmf.getStorage().setdir(this.prefix, WriteOptions.builder().build());
        try {
            List<String> list = kvmf.getStorage().list(prefix);
            for(String clusterPath: list) {
                String clusterId = KvUtils.suffix(prefix, clusterPath);
                try {
                    getOrCreateCluster(clusterId, null);
                } catch (Exception  e) {
                    log.error("Can not load cluster: {}", clusterId, e);
                    fireGroupEvent(NodesGroupEvent.builder()
                      .action("load")
                      .cluster(clusterId)
                      .severity(Severity.ERROR)
                      .message(e.toString())
                    );
                }
            }
        } catch (Exception  e) {
            log.error("Can not load clusters from storage", e);
        }
    }

    String getPrefix() {
        return prefix;
    }

    KvMapperFactory getKvMapperFactory() {
        return kvmf;
    }

    DockerServices getDockerServices() {
        return this.services;
    }

    public NodeStorage getNodeStorage() {
        return nodeStorage;
    }

    @Override
    public NodesGroup getClusterForNode(String nodeId, String clusterId) {
        NodesGroup cluster = findNodeCluster(nodeId);
        if (cluster == null && clusterId != null) {
            cluster = getOrCreateCluster(clusterId, null);
        }
        if(cluster == null) {
            // no clusters for node, so it orphan
            cluster = clusters.get(GROUP_ID_ORPHANS);
        }
        return cluster;
    }

    /**
     * get or create cluster. Consumer will be invoked before cluster process start and allow modification of swarm parameters
     * @param clusterId
     * @param consumer consumer or null
     * @return
     */
    @Override
    public NodesGroup getOrCreateCluster(String clusterId, Consumer<ClusterCreationContext> consumer) {
        ClusterUtils.checkRealClusterName(clusterId);
        return clusters.computeIfAbsent(clusterId, RealCluster.factory(this, null, consumer));
    }

    @Override
    public NodesGroup getOrCreateGroup(AbstractNodesGroupConfig<?> config) {
        final String clusterId = config.getName();
        return clusters.computeIfAbsent(clusterId, (cid) -> {
            NodesGroup cluster;
            if(config instanceof DefaultNodesGroupConfig) {
                cluster = NodesGroupImpl.builder()
                  .config((DefaultNodesGroupConfig) config)
                  .filterFactory(filterFactory)
                  .storage(this)
                  .dockerServices(services)
                  .feature(NodesGroup.Feature.FORBID_NODE_ADDITION)
                  .build();
            } else if(config instanceof SwarmNodesGroupConfig) {
                cluster = RealCluster.factory(this, (SwarmNodesGroupConfig) config, null).apply(cid);
            } else {
                throw new IllegalArgumentException("Unsupported config: " + config);
            }
            return cluster;
        });
    }

    @Override
    public NodesGroup getClusterForNode(String nodeId) {
        NodesGroup cluster = findNodeCluster(nodeId);
        return cluster;
    }


    private NodesGroup findNodeCluster(String nodeId) {
        //we need resolve real cluster or orphans otherwise
        NodesGroup candidate = clusters.get(GROUP_ID_ORPHANS);
        for (NodesGroup cluster : clusters.values()) {
            if ((candidate == null || (isVirtual(candidate) && !isVirtual(cluster))) &&
                  cluster.hasNode(nodeId)) {
                candidate = cluster;
            }
        }
        return candidate;
    }

    private boolean isVirtual(NodesGroup cluster) {
        return cluster instanceof NodesGroupImpl;
    }

    @Override
    public NodesGroup getCluster(String clusterId) {
        return clusters.get(clusterId);
    }

    @Override
    public void deleteCluster(String clusterId) {
        NodesGroup cluster = clusters.get(clusterId);
        ExtendedAssert.notFound(cluster, "Cluster: " + clusterId + " is not found.");
        Assert.isTrue(cluster instanceof RealCluster, "Can not delete non real cluster: " + clusterId);
        deleteGroup(clusterId);
    }

    private void deleteGroup(String clusterId) {
        try {
            final String clusterPath = KvUtils.join(prefix, clusterId);
            kvmf.getStorage().deletedir(clusterPath, DeleteDirOptions.builder().recursive(true).build());
        } catch (Exception e) {
            log.error("Can not delete '{}' cluster.", clusterId, e);
            throw new IllegalArgumentException("Can not delete cluster: " + clusterId + " due to error: " + e.getMessage(), e);
        }
    }

    public void deleteNodeGroup(String clusterId) {

        NodesGroup cluster = clusters.get(clusterId);
        Assert.notNull(cluster, "GroupId: " + clusterId + " is not found.");
        Assert.isTrue(!(cluster instanceof RealCluster), "Can not delete a real cluster: " + clusterId);
        Assert.isTrue(!SYSTEM_GROUPS.contains(clusterId), "Can't delete system group");
        deleteGroup(clusterId);
    }

    @Override
    public DockerService getService(String clusterId) {
        Assert.hasText(clusterId, "Name of cluster is null or empty");
        NodesGroup eurekaCluster = clusters.get(clusterId);
        ExtendedAssert.notFound(eurekaCluster, "Registry does not contains service with clusterId: " + clusterId);
        return eurekaCluster.getDocker();
    }

    @Override
    public Set<String> getServices() {
        return ImmutableSet.copyOf(clusters.keySet());
    }

    private void fireGroupEvent(String clusterId, String action) {
        NodesGroupEvent.Builder logEvent = new NodesGroupEvent.Builder();
        logEvent.setAction(action);
        logEvent.setCluster(clusterId);
        logEvent.setSeverity(StandardActions.toSeverity(action));
        fireGroupEvent(logEvent);
    }

    private void fireGroupEvent(NodesGroupEvent.Builder eventBuilder) {
        messageBus.accept(eventBuilder.build());
    }

    @Override
    public List<NodesGroup> getClusters() {
        return ImmutableList.copyOf(clusters.values());
    }

    @ReConfigObject
    private NodesGroupsConfig getConfig() {
        List<AbstractNodesGroupConfig<?>> groups = clusters.values()
          .stream()
          .map((c) -> c.getConfig())
          .collect(Collectors.toList());
        NodesGroupsConfig ngc = new NodesGroupsConfig();
        ngc.setGroups(groups);
        return ngc;
    }

    @ReConfigObject
    private void getConfig(NodesGroupsConfig config) {
        List<AbstractNodesGroupConfig<?>> groupsConfigs = config.getGroups();
        for(AbstractNodesGroupConfig<?> groupConfig: groupsConfigs) {
            NodesGroup group = getOrCreateGroup(groupConfig);
            if(group != null) {
                group.flush();
            }
        }
    }
}