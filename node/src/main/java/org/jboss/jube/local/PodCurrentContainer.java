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
package org.jboss.jube.local;

import io.fabric8.kubernetes.api.model.PodCurrentContainerInfo;
import io.fabric8.kubernetes.api.model.PodSchema;
import org.jboss.jube.KubernetesModel;

/**
 * Represents a container in a pod in a model
 */
public class PodCurrentContainer {
    private final KubernetesModel model;
    private final String podId;
    private final PodSchema pod;
    private final String containerId;
    private final PodCurrentContainerInfo currentContainer;

    public PodCurrentContainer(KubernetesModel model, String podId, PodSchema pod, String containerId, PodCurrentContainerInfo currentContainer) {
        this.model = model;
        this.podId = podId;
        this.pod = pod;
        this.containerId = containerId;
        this.currentContainer = currentContainer;
    }

    public KubernetesModel getModel() {
        return model;
    }

    public String getPodId() {
        return podId;
    }

    public PodSchema getPod() {
        return pod;
    }

    public String getContainerId() {
        return containerId;
    }

    public PodCurrentContainerInfo getCurrentContainer() {
        return currentContainer;
    }

    public void containerAlive(String id, boolean alive) {
        NodeHelper.containerAlive(pod, id, alive);
    }
}
