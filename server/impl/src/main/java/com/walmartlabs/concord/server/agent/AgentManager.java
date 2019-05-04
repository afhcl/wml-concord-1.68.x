package com.walmartlabs.concord.server.agent;

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

import com.walmartlabs.concord.server.process.PartialProcessKey;
import com.walmartlabs.concord.server.process.ProcessEntry;
import com.walmartlabs.concord.server.process.ProcessKey;
import com.walmartlabs.concord.server.process.queue.ProcessQueueDao;
import com.walmartlabs.concord.server.sdk.ProcessStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Named
public class AgentManager {

    private static final Logger log = LoggerFactory.getLogger(AgentManager.class);

    private final ProcessQueueDao queueDao;
    private final AgentCommandsDao commandQueue;

    @Inject
    public AgentManager(ProcessQueueDao queueDao, AgentCommandsDao commandQueue) {
        this.queueDao = queueDao;
        this.commandQueue = commandQueue;
    }

    public void killProcess(ProcessKey processKey) {
        ProcessEntry e = queueDao.get(processKey);
        if (e == null) {
            throw new IllegalArgumentException("Process not found: " + processKey);
        }

        String agentId = e.lastAgentId();
        if (agentId == null) {
            log.warn("killProcess ['{}'] -> trying to kill a process w/o an agent", processKey);
            queueDao.updateStatus(processKey, ProcessStatus.CANCELLED);
            return;
        }

        commandQueue.insert(UUID.randomUUID(), agentId, Commands.cancel(processKey.toString()));
    }

    public void killProcess(List<ProcessKey> processKeys) {
        // TODO replace with a more specific method
        List<ProcessEntry> l = queueDao.get(processKeys.stream()
                .map(k -> PartialProcessKey.from(k.getInstanceId()))
                .collect(Collectors.toList()));

        List<UUID> withoutAgent = l.stream()
                .filter(p -> p.lastAgentId() == null)
                .map(ProcessEntry::instanceId)
                .collect(Collectors.toList());

        if (!withoutAgent.isEmpty()) {
            withoutAgent.forEach(p -> log.warn("killProcess ['{}'] -> trying to kill a process w/o an agent", p));
            queueDao.updateStatus(processKeys, ProcessStatus.CANCELLED, null);
        }

        List<AgentCommand> commands = l.stream()
                .filter(p -> p.lastAgentId() != null)
                .map(p -> new AgentCommand(UUID.randomUUID(), p.lastAgentId(), AgentCommand.Status.CREATED,
                        new Date(), Commands.cancel(p.instanceId().toString())))
                .collect(Collectors.toList());

        commandQueue.insertBatch(commands);
    }
}
