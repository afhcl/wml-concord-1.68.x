package com.walmartlabs.concord.server.process.pipelines.processors;

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

import com.walmartlabs.concord.sdk.Constants;
import com.walmartlabs.concord.server.process.Payload;
import com.walmartlabs.concord.server.process.logs.ProcessLogManager;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.HashMap;
import java.util.Map;

@Named
public class EntryPointProcessor implements PayloadProcessor {

    private final ProcessLogManager logManager;

    @Inject
    public EntryPointProcessor(ProcessLogManager logManager) {
        this.logManager = logManager;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Payload process(Chain chain, Payload payload) {
        String s = payload.getHeader(Payload.ENTRY_POINT);

        Map<String, Object> cfg = payload.getHeader(Payload.CONFIGURATION);
        if (cfg == null) {
            cfg = new HashMap<>();
        }

        if (s == null) {
            s = (String) cfg.get(Constants.Request.ENTRY_POINT_KEY);
        }

        if (s == null) {
            s = Constants.Request.DEFAULT_ENTRY_POINT_NAME;
        }

        cfg.put(Constants.Request.ENTRY_POINT_KEY, s);
        payload = payload.putHeader(Payload.CONFIGURATION, cfg);

        logManager.info(payload.getProcessKey(), "Using entry point: {}", s);

        return chain.process(payload);
    }
}
