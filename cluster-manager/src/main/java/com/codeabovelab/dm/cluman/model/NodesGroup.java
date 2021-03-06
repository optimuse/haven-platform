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

package com.codeabovelab.dm.cluman.model;

import com.codeabovelab.dm.cluman.cluster.docker.management.DockerService;
import com.codeabovelab.dm.cluman.ds.clusters.AbstractNodesGroupConfig;
import com.codeabovelab.dm.cluman.security.WithAcl;

import java.util.Collection;
import java.util.Set;

/**
 * Interface represents node group
 */
public interface NodesGroup extends Named, WithAcl {

    void flush();

    AbstractNodesGroupConfig<?> getConfig();
    void setConfig(AbstractNodesGroupConfig<?> config);

    enum Feature {
        /**
         * Feature mean than nodes in group is united by single 'swarm' service.
         */
        SWARM,
        /**
         * Disallow node addition, usually applied on clusters which use filter for node list.
         */
        FORBID_NODE_ADDITION,
    }

    /**
     * Identifier of cluster.
     * @return
     */
    String getName();

    /**
     * Human friendly cluster name.
     * @return
     */
    String getTitle();

    void setTitle(String title);

    /**
     * Cluster description
     * @return
     */
    String getDescription();

    void setDescription(String description);

    /**
     * Return all current nodes
     * @return
     */
    Collection<NodeInfo> getNodes();

    /**
     * Collections with names of other intersected NodesGroups. Note that it
     * not mean 'enclosed' relationship. <p/>
     * For example 'all' - return all real clusters (in future list may alo include other groups, but current
     * implementation only consider it).
     * Any RealCluster always return empty collection.
     * @return non null collection
     */
    Collection<String> getGroups();

    boolean hasNode(String id);

    DockerService getDocker();

    Set<Feature> getFeatures();

    /**
     * SpEL string which applied to images. It evaluated over object with 'tag(name)' and 'label(key, val)' functions,
     * also it has 'r(regexp)' function which can combined with other, like: <code>'spel:tag(r(".*_dev")) or label("dev", "true")'</code>.
     * @return
     */
    String getImageFilter();

    void setImageFilter(String imageFilter);


}
