package com.walmartlabs.concord.project.model;

/*-
 * *****
 * Concord
 * -----
 * Copyright (C) 2017 - 2018 Walmart Inc.
 * -----
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =====
 */

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.walmartlabs.concord.sdk.Constants;
import io.takari.bpm.model.SourceMap;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

public class Trigger implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String name;
    private final List<String> activeProfiles;
    private final Map<String, Object> arguments;
    private final Map<String, Object> params;
    private final SourceMap sourceMap;
    private final Map<String, Object> cfg;

    public Trigger(String name, List<String> activeProfiles, Map<String, Object> arguments, Map<String, Object> params, Map<String, Object> cfg, SourceMap sourceMap) {
        this.name = name;
        this.activeProfiles = activeProfiles;
        this.arguments = arguments;
        this.params = params;
        this.sourceMap = sourceMap;
        this.cfg = cfg;
    }

    public String getName() {
        return name;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public Map<String, Object> getCfg() {
        return cfg;
    }

    @JsonIgnore
    public String getEntryPoint() {
        if (cfg == null) {
            return null;
        }

        return (String) cfg.get(Constants.Request.ENTRY_POINT_KEY);
    }

    @JsonIgnore
    public boolean isUseInitiator() {
        if (cfg == null) {
            return false;
        }
        Boolean v = (Boolean) cfg.get(Constants.Trigger.USE_INITIATOR);
        if (v == null) {
            return false;
        }

        return v;
    }

    @JsonIgnore
    public String getExclusiveGroup() {
        if (cfg == null) {
            return null;
        }
        return (String)cfg.get(Constants.Trigger.EXCLUSIVE_GROUP);
    }

    public List<String> getActiveProfiles() {
        return activeProfiles;
    }

    public Map<String, Object> getArguments() {
        return arguments;
    }

    public SourceMap getSourceMap() {
        return sourceMap;
    }

    @Override
    public String toString() {
        return "Trigger{" +
                "name='" + name + '\'' +
                ", activeProfiles=" + activeProfiles +
                ", arguments=" + arguments +
                ", params=" + params +
                ", sourceMap=" + sourceMap +
                ", cfg=" + cfg +
                '}';
    }
}
