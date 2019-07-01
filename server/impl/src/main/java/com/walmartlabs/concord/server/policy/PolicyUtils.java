package com.walmartlabs.concord.server.policy;

/*-
 * *****
 * Concord
 * -----
 * Copyright (C) 2017 - 2019 Walmart Inc.
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

import com.walmartlabs.concord.project.model.Trigger;
import com.walmartlabs.concord.server.org.OrganizationEntry;
import com.walmartlabs.concord.server.org.project.ProjectEntry;
import com.walmartlabs.concord.server.org.secret.SecretType;
import com.walmartlabs.concord.server.org.secret.SecretVisibility;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class PolicyUtils {

    public static Map<String, Object> toMap(OrganizationEntry entry) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", entry.getId());
        m.put("name", entry.getName());
        if (entry.getMeta() != null) {
            m.put("meta", entry.getMeta());
        }
        if (entry.getCfg() != null) {
            m.put("cfg", entry.getCfg());
        }
        return m;
    }

    public static Map<String, Object> toMap(UUID orgId, String orgName, ProjectEntry entry) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", entry.getId());
        m.put("name", entry.getName());
        m.put("orgId", orgId);
        m.put("orgName", orgName);
        if (entry.getVisibility() != null) {
            m.put("visibility", entry.getVisibility().name());
        }
        if (entry.getMeta() != null) {
            m.put("meta", entry.getMeta());
        }
        if (entry.getCfg() != null) {
            m.put("cfg", entry.getCfg());
        }
        return m;
    }

    public static Map<String, Object> toMap(UUID orgId, String secretName, SecretType type,
                                            SecretVisibility visibility, String storeType) {

        Map<String, Object> m = new HashMap<>();
        m.put("name", secretName);
        m.put("orgId", orgId);
        m.put("type", type);
        if (visibility != null) {
            m.put("visibility", visibility.name());
        }
        if (storeType != null) {
            m.put("storeType", storeType);
        }
        return m;
    }

    public static Map<String, Object> toMap(UUID orgId, UUID projectId, Trigger trigger) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", trigger.getName());
        m.put("orgId", orgId);
        m.put("projectId", projectId);
        m.put("arguments", trigger.getArguments() != null ? trigger.getArguments() : Collections.emptyList());
        m.put("params", trigger.getParams() != null ? trigger.getParams() : Collections.emptyList());
        m.put("cfg", trigger.getCfg() != null ? trigger.getCfg() : Collections.emptyList());
        return m;
    }

    private PolicyUtils() {
    }
}
