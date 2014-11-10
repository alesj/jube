/**
 *  Copyright 2005-2014 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package io.fabric8.jube.process.config;

import java.io.File;
import java.util.Map;

public class WildFlyConfigurator implements Configurator {
    private int counter;

    public void configure(ProcessConfig config, File installDir) {
        int current = getCurrentCounter();
        Map<String, String> env = config.getEnvironment();
        env.put("NODE_NAME", String.valueOf(current));
        env.put("PORT_OFFSET", String.valueOf(100 * current));
        incrementCurrentCounter();
    }

    protected int getCurrentCounter() {
        return counter;
    }

    protected void incrementCurrentCounter() {
        counter++;
    }
}
