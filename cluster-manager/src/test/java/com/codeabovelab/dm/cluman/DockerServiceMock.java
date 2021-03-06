package com.codeabovelab.dm.cluman;

import com.codeabovelab.dm.cluman.cluster.docker.ClusterConfig;
import com.codeabovelab.dm.cluman.cluster.docker.ClusterConfigImpl;
import com.codeabovelab.dm.cluman.cluster.docker.management.DockerService;
import com.codeabovelab.dm.cluman.cluster.docker.management.argument.*;
import com.codeabovelab.dm.cluman.cluster.docker.management.result.ProcessEvent;
import com.codeabovelab.dm.cluman.cluster.docker.management.result.RemoveImageResult;
import com.codeabovelab.dm.cluman.cluster.docker.management.result.ResultCode;
import com.codeabovelab.dm.cluman.cluster.docker.management.result.ServiceCallResult;
import com.codeabovelab.dm.cluman.cluster.docker.model.*;
import com.codeabovelab.dm.cluman.model.*;
import com.codeabovelab.dm.cluman.model.Node;
import com.codeabovelab.dm.common.utils.PojoBeanUtils;
import com.google.common.collect.ImmutableMap;
import org.springframework.beans.BeanUtils;
import org.springframework.security.crypto.codec.Hex;
import org.springframework.util.Assert;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 */
public class DockerServiceMock implements DockerService {

    private class ContainerHolder {
        private final DockerContainer container;
        private String name;
        private LocalDateTime started;
        private LocalDateTime stopped;
        private HostConfig hostConfig;
        private ContainerConfig config;

        ContainerHolder(DockerContainer container) {
            this.container = container;
            this.name = this.container.getName();
        }

        synchronized void start() {
            started = LocalDateTime.now();
        }

        synchronized void stop() {
            stopped = LocalDateTime.now();
            started = null;
        }

        synchronized boolean isStarted() {
            return started != null && (stopped == null || started.isAfter(stopped));
        }

        DockerContainer asDockerContainer() {
            return container.toBuilder().name(name).build();
        }

        public String getId() {
            return container.getId();
        }

        public String getName() {
            return name;
        }

        public synchronized HostConfig getHostConfig() {
            return hostConfig;
        }

        public synchronized void setHostConfig(HostConfig hostConfig) {
            this.hostConfig = hostConfig;
        }

        public synchronized ContainerConfig getConfig() {
            return config;
        }

        public synchronized void setConfig(ContainerConfig config) {
            this.config = config;
        }

        public synchronized void restart() {
            stop();
            start();
        }

        public synchronized void setName(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return "ContainerHolder{" +
              "name='" + name + '\'' +
              '}';
        }
    }

    private class NetworkHolder {

        private final String name;
        private final String driver;

        NetworkHolder(CreateNetworkCmd cmd) {
            this.name = cmd.getName();
            this.driver = cmd.getDriver();
        }

        public String getName() {
            return name;
        }

        public String getDriver() {
            return driver;
        }

        Network asNetwork() {
            Network network = new Network();
            network.setName(name);
            return network;
        }
    }


    static final int ID_LEN = 12;
    private final Map<String, ContainerHolder> containers = new HashMap<>();
    private final Map<String, NetworkHolder> networks = new ConcurrentHashMap<>();

    private final ClusterConfig cc = ClusterConfigImpl.builder().build();
    private final DockerServiceInfo info;
    //we need to make list of nodes
    private final NodeInfo node = NodeInfoImpl.builder().name("test-node").build();

    public DockerServiceMock(DockerServiceInfo info) {
        this.info = info;
    }

    @Override
    public String getCluster() {
        return info.getName();
    }

    @Override
    public String getNode() {
        return null;
    }

    @Override
    public boolean isOnline() {
        return true;
    }

    @Override
    public List<DockerContainer> getContainers(GetContainersArg arg) {
        synchronized (containers) {
            Stream<ContainerHolder> stream = containers.values().stream();
            if(!arg.isAll()) {
                stream = stream.filter(ContainerHolder::isStarted);
            }
            return stream.map(ContainerHolder::asDockerContainer).collect(Collectors.toList());
        }
    }

    @Override
    public ContainerDetails getContainer(String id) {
        if (id == null) {
            return null;
        }
        ContainerHolder holder = getContainerHolder(id);
        if (holder == null) {
            return null;
        }
        ContainerDetails cd = new ContainerDetails();
        DockerContainer source = holder.asDockerContainer();
        BeanUtils.copyProperties(source, cd);
        Node node = source.getNode();
        cd.setNode(toDockerNode(node));
        //TODO clone below
        cd.setHostConfig(holder.getHostConfig());
        cd.setConfig(holder.getConfig());
        return cd;
    }

    private com.codeabovelab.dm.cluman.cluster.docker.model.Node toDockerNode(Node node) {
        return new com.codeabovelab.dm.cluman.cluster.docker.model.Node(node.getAddress(),
          1,
          node.getName(),
          node.getAddress(),
          node.getName(),
          6 * 1024 * 1024,
          new HashMap<>());
    }

    private ContainerHolder getContainerHolder(String id) {
        if(id == null) {
            return null;
        }
        synchronized (containers) {
            ContainerHolder ch = null;
            if (id.length() >= ID_LEN) {
                String sid = id;
                if (id.length() > ID_LEN) {
                    sid = sid.substring(ID_LEN);
                }
                ch = containers.get(sid);
            }
            if (ch == null) {
                ch = getContainerHolderByName(id);
            }
            return ch;
        }
    }

    private ContainerHolder getContainerHolderByName(String name) {
        if(name == null) {
            return null;
        }
        synchronized (containers) {
            Object[] objects = containers.values().stream().filter((c) -> name.equals(c.getName())).toArray();
            if(objects.length == 0) {
                return null;
            }
            if(objects.length > 1) {
                throw new IllegalStateException("Multiple containers with same name: " + Arrays.toString(objects));
            }
            return (ContainerHolder) objects[0];
        }
    }

    @Override
    public ServiceCallResult getStatistics(GetStatisticsArg arg) {
        String id = arg.getId();
        synchronized (containers) {
            ContainerHolder ch = getContainerHolder(id);
            if (ch == null) {
                return null;
            }
        }
        Statistics s = new Statistics();
        s.setRead(DateTimeFormatter.ISO_DATE_TIME.format(LocalDateTime.now()));
        s.setBlkioStats(ImmutableMap.of());
        s.setCpuStats(ImmutableMap.of());
        s.setMemoryStats(ImmutableMap.of());
        s.setNetworks(ImmutableMap.of());
        arg.getWatcher().accept(s);
        return resultOk();
    }

    @Override
    public DockerServiceInfo getInfo() {
        DockerServiceInfo.Builder b = DockerServiceInfo.builder().from(info);
        b.setContainers(containers.size());
        return b.build();
    }

    @Override
    public ServiceCallResult startContainer(String id) {
        synchronized (containers) {
            ContainerHolder ch = getContainerHolder(id);
            if (ch == null) {
                return resultNotFound();
            }
            ch.start();
            return resultOk();
        }
    }

    @Override
    public ServiceCallResult stopContainer(StopContainerArg arg) {
        synchronized (containers) {
            ContainerHolder ch = getContainerHolder(arg.getId());
            if (ch == null) {
                return resultNotFound();
            }
            ch.stop();
            return resultOk();
        }
    }

    @Override
    public ServiceCallResult getContainerLog(GetLogContainerArg arg) {
        synchronized (containers) {
            ContainerHolder ch = getContainerHolder(arg.getId());
            if (ch == null) {
                return resultNotFound();
            }
            return resultOk();
        }
    }

    @Override
    public ServiceCallResult subscribeToEvents(GetEventsArg arg) {
        //TODO
        return resultOk();
    }

    @Override
    public ServiceCallResult restartContainer(StopContainerArg arg) {
        synchronized (containers) {
            ContainerHolder ch = getContainerHolder(arg.getId());
            if (ch == null) {
                return resultNotFound();
            }
            ch.restart();
            return resultOk();
        }
    }

    @Override
    public ServiceCallResult killContainer(KillContainerArg arg) {
        synchronized (containers) {
            ContainerHolder ch = getContainerHolder(arg.getId());
            if (ch == null) {
                return resultNotFound();
            }
            ch.stop();
            return resultOk();
        }
    }

    @Override
    public ServiceCallResult deleteContainer(DeleteContainerArg arg) {
        synchronized (containers) {
            ContainerHolder ch = getContainerHolder(arg.getId());
            if(ch == null) {
                return resultNotFound();
            }
            if(ch.isStarted() && !arg.isKill()) {
                return new ServiceCallResult().code(ResultCode.ERROR).message("TEST Container is started");
            }
            containers.remove(ch.getId());
            return resultOk();
        }
    }

    @Override
    public CreateContainerResponse createContainer(CreateContainerCmd cmd) {
        synchronized (containers) {
            String name = cmd.getName();
            Assert.notNull(name, "name is null");
            ContainerHolder ch = getContainerHolderByName(name);
            if(ch != null) {
                CreateContainerResponse r = new CreateContainerResponse();
                r.code(ResultCode.CONFLICT).message("Container with name '" + name + "' already exists.");
                return r;
            }
            String image = cmd.getImage();
            Assert.notNull(image, "image is null");
            DockerContainer dc = DockerContainer.builder()
              .id(makeId())
              .name(name)
              .image(image)
              .imageId(image)
              .node(getNode(cmd.getLabels()))
              .build();
            ch = new ContainerHolder(dc);
            ch.setHostConfig(cmd.getHostConfig());
            ContainerConfig.ContainerConfigBuilder cc = ContainerConfig.builder();
            PojoBeanUtils.copyToBuilder(cmd, cc);
            ch.setConfig(cc.build());
            containers.put(ch.getId(), ch);
            CreateContainerResponse r = new CreateContainerResponse();
            r.setId(dc.getId());
            r.setCode(ResultCode.OK);
            return r;
        }
    }

    /**
     * in future node must be choose by some conditions placed in labels
     * @param labels
     * @return
     */
    private Node getNode(Map<String, String> labels) {
        return node;
    }

    private String makeId() {
        synchronized (containers) {
            while(true) {
                byte[] arr = new byte[ID_LEN/2];
                ThreadLocalRandom.current().nextBytes(arr);
                char[] encode = Hex.encode(arr);
                String id = new String(encode);
                // this is unlikely, but if happened we got strange errors, because we check it
                if(!containers.containsKey(id)) {
                    return id;
                }
            }
        }
    }

    @Override
    public ServiceCallResult createTag(TagImageArg cmd) {
        return resultOk();
    }

    @Override
    public ServiceCallResult updateContainer(UpdateContainerCmd cmd) {
        synchronized (containers) {
            ContainerHolder ch = getContainerHolder(cmd.getId());
            if (ch == null) {
                return resultNotFound();
            }
            //TODO
            return resultOk();
        }
    }

    @Override
    public ServiceCallResult renameContainer(String id, String newName) {
        synchronized (containers) {
            ContainerHolder ch = getContainerHolder(id);
            if (ch == null) {
                return resultNotFound();
            }
            ch.setName(newName);
            return resultOk();
        }
    }

    @Override
    public ServiceCallResult createNetwork(CreateNetworkCmd cmd) {
        NetworkHolder value = new NetworkHolder(cmd);
        NetworkHolder old = networks.putIfAbsent(cmd.getName(), value);
        if(old != null) {
            return resultConflict();
        }
        return resultOk();
    }

    @Override
    public List<Network> getNetworks() {
        return networks.values().stream().map(NetworkHolder::asNetwork).collect(Collectors.toList());
    }

    @Override
    public List<ImageItem> getImages(GetImagesArg arg) {
        return Collections.emptyList();
    }

    @Override
    public ImageDescriptor pullImage(String name, Consumer<ProcessEvent> watcher) {
        //TODO
        return null;
    }

    @Override
    public ImageDescriptor getImage(String name) {
        return null;
    }

    @Override
    public ClusterConfig getClusterConfig() {
        return cc;
    }

    @Override
    public RemoveImageResult removeImage(RemoveImageArg arg) {
        //TODO
        return new RemoveImageResult();
    }

    private ServiceCallResult resultNotFound() {
        ServiceCallResult r = new ServiceCallResult();
        r.code(ResultCode.NOT_FOUND).message("TEST not found");
        return r;
    }

    private ServiceCallResult resultOk() {
        return new ServiceCallResult().code(ResultCode.OK);
    }

    private ServiceCallResult resultConflict() {
        return new ServiceCallResult().code(ResultCode.CONFLICT);
    }
}
