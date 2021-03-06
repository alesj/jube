/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.jube.replicator;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import io.fabric8.common.util.Closeables;
import io.fabric8.common.util.Objects;
import io.fabric8.groups.Group;
import io.fabric8.groups.GroupListener;
import io.fabric8.groups.internal.ZooKeeperGroup;
import io.fabric8.kubernetes.api.KubernetesHelper;
import io.fabric8.kubernetes.api.model.ControllerCurrentState;
import io.fabric8.kubernetes.api.model.ControllerDesiredState;
import io.fabric8.kubernetes.api.model.CurrentState;
import io.fabric8.kubernetes.api.model.ManifestContainer;
import io.fabric8.kubernetes.api.model.PodCurrentContainerInfo;
import io.fabric8.kubernetes.api.model.PodSchema;
import io.fabric8.kubernetes.api.model.PodTemplate;
import io.fabric8.kubernetes.api.model.PodTemplateDesiredState;
import io.fabric8.kubernetes.api.model.ReplicationControllerSchema;
import io.fabric8.zookeeper.ZkPath;
import io.hawt.util.Strings;
import org.apache.curator.framework.CuratorFramework;
import org.apache.deltaspike.core.api.config.ConfigProperty;
import org.jboss.jube.KubernetesModel;
import org.jboss.jube.apimaster.ApiMasterKubernetesModel;
import org.jboss.jube.local.NodeHelper;
import org.jboss.jube.process.ProcessManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Monitors the status of the current replication controllers and pods and chooses to start new pods if there are not enough replicas
 */
@Singleton
public class Replicator {
    private static final transient Logger LOG = LoggerFactory.getLogger(Replicator.class);

    private final CuratorFramework curator;
    private final KubernetesModel model;
    private final ProcessManager processManager;
    private final long pollTime;
    private final Timer timer = new Timer();
    private final GroupListener<ReplicatorNode> groupListener;
    private ZooKeeperGroup<ReplicatorNode> group;
    private AtomicBoolean timerEnabled = new AtomicBoolean(false);
    private AtomicBoolean master = new AtomicBoolean(false);

    @Inject
    public Replicator(CuratorFramework curator,
                      ApiMasterKubernetesModel model,
                      ProcessManager processManager,
                      @ConfigProperty(name = "REPLICATOR_POLL_TIME", defaultValue = "2000")
                      long pollTime) {
        this.curator = curator;
        this.model = model;
        this.processManager = processManager;
        this.pollTime = pollTime;

        System.out.println("Starting the replicator with poll time: " + pollTime);

        group = new ZooKeeperGroup<ReplicatorNode>(curator, ZkPath.KUBERNETES_REPLICATOR.getPath(), ReplicatorNode.class);
        groupListener = new GroupListener<ReplicatorNode>() {

            @Override
            public void groupEvent(Group<ReplicatorNode> group, GroupEvent event) {
                onGroupEvent(group, event);
            }
        };
        group.add(groupListener);
        group.update(createState());
        group.start();

        enableTimer();
    }


    @PreDestroy
    public void destroy() {
        disableTimer();
        group.remove(groupListener);
        Closeables.closeQuietly(group);
        group = null;
        disableTimer();
    }

    public boolean isMaster() {
        return group.isMaster() && master.get();
    }

    public void enableMaster() {
        if (master.compareAndSet(false, true)) {
            enableTimer();
            LOG.info("Replicator is the master");
            System.out.println("====== Replicator is the master");
            group.update(createState());
        }
    }

    protected void disableMaster() {
        if (master.compareAndSet(true, false)) {
            LOG.info("Replicator is not the master");
            System.out.println("====== Replicator is NOT the master");
            group.update(createState());
            disableTimer();
        }
    }

    protected void onGroupEvent(Group<ReplicatorNode> group, GroupListener.GroupEvent event) {
        switch (event) {
            case CONNECTED:
            case CHANGED:
                if (isValid()) {
                    try {
                        if (group.isMaster()) {
                            enableMaster();
                        } else {
                            disableMaster();
                        }
                    } catch (IllegalStateException e) {
                        // Ignore
                    }
                } else {
                    LOG.info("Not valid with master: " + group.isMaster()
                            + " curator: " + curator);
                }
                break;
            case DISCONNECTED:
        }
    }

    protected boolean isValid() {
        return true;
    }


    protected void autoScale() throws Exception {
        if (!isMaster()) {
            return;
        }
        ImmutableSet<Map.Entry<String, ReplicationControllerSchema>> entries = model.getReplicationControllerMap().entrySet();
        for (Map.Entry<String, ReplicationControllerSchema> entry : entries) {
            String rcID = entry.getKey();
            ReplicationControllerSchema replicationController = entry.getValue();
            PodTemplateDesiredState podTemplateDesiredState = NodeHelper.getPodTemplateDesiredState(replicationController);
            if (podTemplateDesiredState == null) {
                LOG.warn("Cannot instantiate replication controller: " + replicationController.getId() + " due to missing PodTemplate.DesiredState!");
                continue;
            }
            int replicaCount = 0;
            ControllerDesiredState desiredState = replicationController.getDesiredState();
            if (desiredState != null) {
                Integer replicas = desiredState.getReplicas();
                if (replicas != null && replicas.intValue() > 0) {
                    replicaCount = replicas.intValue();
                }
            }
            ControllerCurrentState currentState = NodeHelper.getOrCreateCurrentState(replicationController);
            Map<String, String> replicaSelector = desiredState.getReplicaSelector();
            ImmutableList<PodSchema> pods = model.getPods(replicaSelector);

            int currentSize = pods.size();
            int createCount = replicaCount - currentSize;
            if (createCount > 0) {
                pods = createMissingContainers(replicationController, podTemplateDesiredState, desiredState, createCount, pods);
            } else if (createCount < 0) {
                int deleteCount = Math.abs(createCount);
                pods = deleteContainers(pods, deleteCount);
            }

            // TODO only show the running count?
            currentState.setReplicas(replicaCount);
        }
    }


    private ImmutableList<PodSchema> deleteContainers(ImmutableList<PodSchema> pods, int deleteCount) throws Exception {
        List<PodSchema> list = Lists.newArrayList(pods);
        for (int i = 1, size = list.size(); i <= deleteCount && i <= size; i++) {
            PodSchema removePod = list.remove(size - 1);
            NodeHelper.deletePod(processManager, model, removePod.getId());
        }
        return ImmutableList.copyOf(list);
    }


    protected ImmutableList<PodSchema> createMissingContainers(ReplicationControllerSchema replicationController, PodTemplateDesiredState podTemplateDesiredState, ControllerDesiredState desiredState, int createCount, ImmutableList<PodSchema> pods) throws Exception {
        // TODO this is a hack ;) needs replacing with the real host we're creating on
        String host = "localhost";
        List<PodSchema> list = Lists.newArrayList(pods);
        for (int i = 0; i < createCount; i++) {
            PodSchema pod = new PodSchema();
            pod.setKind(NodeHelper.KIND_POD);

            createNewId(replicationController, pod);
            list.add(pod);

            List<ManifestContainer> containers = KubernetesHelper.getContainers(podTemplateDesiredState);
            for (ManifestContainer container : containers) {
                String containerName = pod.getId() + "-" + container.getName();

                PodCurrentContainerInfo containerInfo = NodeHelper.getOrCreateContainerInfo(pod, containerName);
                CurrentState currentState = pod.getCurrentState();
                Objects.notNull(currentState, "currentState");
                currentState.setHost(host);

                String image = container.getImage();
                if (Strings.isBlank(image)) {
                    LOG.warn("Missing image for " + containerName + " so cannot create it!");
                    continue;
                }
                NodeHelper.addOrUpdateDesiredContainer(pod, containerName, container);
            }
            PodTemplate podTemplate = desiredState.getPodTemplate();
            if (podTemplate != null) {
                pod.setLabels(podTemplate.getLabels());
            }
            // TODO should we update the pod now we've updated it?
            List<ManifestContainer> desiredContainers = NodeHelper.getOrCreatePodDesiredContainers(pod);
            NodeHelper.createMissingContainers(processManager, model, pod, NodeHelper.getOrCreateCurrentState(pod), desiredContainers);
            model.updatePod(pod.getId(), pod);
        }
        return ImmutableList.copyOf(list);
    }

    protected String createNewId(ReplicationControllerSchema replicationController, PodSchema pod) {
        String id = replicationController.getId();
        if (Strings.isNotBlank(id)) {
            id += "-";
            int idx = 1;
            while (true) {
                String anId = id + (idx++);
                if (model.updatePodIfNotExist(anId, pod)) {
                    pod.setId(anId);
                    return null;
                }
            }
        }
        id = model.createID(NodeHelper.KIND_POD);
        pod.setId(id);
        return null;
    }

    protected void enableTimer() {
        if (timerEnabled.compareAndSet(false, true)) {
            TimerTask timerTask = new TimerTask() {
                @Override
                public void run() {
                    LOG.debug("Replicator Timer");
                    try {
                        autoScale();
                    } catch (Exception e) {
                        System.out.println("Caught: " + e);
                        e.printStackTrace();
                        LOG.warn("Caught: " + e, e);
                    }
                }
            };
            timer.schedule(timerTask, this.pollTime, this.pollTime);
        }
    }

    protected void disableTimer() {
        System.out.println("disabling the Replicator timer!");
        if (timer != null) {
            timer.cancel();
        }
        timerEnabled.set(false);
    }

    private ReplicatorNode createState() {
        ReplicatorNode state = new ReplicatorNode();
        return state;
    }


    public long getPollTime() {
        return pollTime;
    }

    public KubernetesModel getModel() {
        return model;
    }

}
