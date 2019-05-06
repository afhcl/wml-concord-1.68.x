package com.walmartlabs.concord.server.process.logs;

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

import com.walmartlabs.concord.db.AbstractDao;
import com.walmartlabs.concord.db.MainDB;
import com.walmartlabs.concord.server.process.ProcessKey;
import org.jooq.Configuration;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.jooq.impl.DSL;

import javax.inject.Inject;
import javax.inject.Named;
import java.io.Serializable;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import static com.walmartlabs.concord.server.jooq.Routines.processLogLastNBytes2;
import static com.walmartlabs.concord.server.jooq.Routines.processLogNextRange2;
import static com.walmartlabs.concord.server.jooq.Tables.PROCESS_LOGS;
import static com.walmartlabs.concord.server.jooq.Tables.V_PROCESS_LOGS_SIZE;
import static org.jooq.impl.DSL.*;

@Named
public class ProcessLogsDao extends AbstractDao {

    @Inject
    public ProcessLogsDao(@MainDB Configuration cfg) {
        super(cfg);
    }

    public void append(ProcessKey processKey, byte[] data) {
        UUID instanceId = processKey.getInstanceId();
        Timestamp createdAt = processKey.getCreatedAt();

        tx(tx -> tx.insertInto(PROCESS_LOGS)
                .columns(PROCESS_LOGS.INSTANCE_ID,
                        PROCESS_LOGS.INSTANCE_CREATED_AT,
                        PROCESS_LOGS.CHUNK_RANGE,
                        PROCESS_LOGS.CHUNK_DATA)
                .values(value(instanceId),
                        value(createdAt),
                        processLogNextRange2(instanceId, createdAt, data.length),
                        value(data))
                .execute());
    }

    public ProcessLog get(ProcessKey processKey, Integer start, Integer end) {
        UUID instanceId = processKey.getInstanceId();
        Timestamp createdAt = processKey.getCreatedAt();

        try (DSLContext tx = DSL.using(cfg)) {
            List<ProcessLogChunk> chunks = getChunks(tx, processKey, start, end);

            int size = tx.select(V_PROCESS_LOGS_SIZE.SIZE)
                    .from(V_PROCESS_LOGS_SIZE)
                    .where(V_PROCESS_LOGS_SIZE.INSTANCE_ID.eq(instanceId)
                            .and(V_PROCESS_LOGS_SIZE.INSTANCE_CREATED_AT.eq(createdAt)))
                    .fetchOptional(V_PROCESS_LOGS_SIZE.SIZE)
                    .orElse(0);

            return new ProcessLog(size, chunks);
        }
    }

    private List<ProcessLogChunk> getChunks(DSLContext tx, ProcessKey processKey, Integer start, Integer end) {
        UUID instanceId = processKey.getInstanceId();
        Timestamp createdAt = processKey.getCreatedAt();

        String lowerBoundExpr = "lower(" + PROCESS_LOGS.CHUNK_RANGE + ")";

        if (start == null && end == null) {
            // entire file
            return tx.select(field(lowerBoundExpr), PROCESS_LOGS.CHUNK_DATA)
                    .from(PROCESS_LOGS)
                    .where(PROCESS_LOGS.INSTANCE_ID.eq(instanceId)
                            .and(PROCESS_LOGS.INSTANCE_CREATED_AT.eq(createdAt)))
                    .orderBy(PROCESS_LOGS.CHUNK_RANGE)
                    .fetch(ProcessLogsDao::toChunk);

        } else if (start != null) {
            // ranges && [start, end)
            String rangeExpr = PROCESS_LOGS.CHUNK_RANGE.getName() + " && int4range(?, ?)";
            return tx.select(field(lowerBoundExpr), PROCESS_LOGS.CHUNK_DATA)
                    .from(PROCESS_LOGS)
                    .where(PROCESS_LOGS.INSTANCE_ID.eq(instanceId)
                            .and(PROCESS_LOGS.INSTANCE_CREATED_AT.eq(createdAt))
                            .and(rangeExpr, start, end))
                    .orderBy(PROCESS_LOGS.CHUNK_RANGE)
                    .fetch(ProcessLogsDao::toChunk);

        } else {
            // ranges && [upper_bound - end, upper_bound)
            String rangeExpr = PROCESS_LOGS.CHUNK_RANGE.getName() + " && (select range from x)";
            return tx.with("x").as(select(processLogLastNBytes2(instanceId, createdAt, end).as("range")))
                    .select(field(lowerBoundExpr), PROCESS_LOGS.CHUNK_DATA)
                    .from(PROCESS_LOGS)
                    .where(PROCESS_LOGS.INSTANCE_ID.eq(instanceId)
                            .and(PROCESS_LOGS.INSTANCE_CREATED_AT.eq(createdAt))
                            .and(rangeExpr, instanceId, end))
                    .orderBy(PROCESS_LOGS.CHUNK_RANGE)
                    .fetch(ProcessLogsDao::toChunk);
        }
    }

    private static ProcessLogChunk toChunk(Record2<Object, byte[]> r) {
        return new ProcessLogChunk((Integer) r.value1(), r.value2());
    }

    public static final class ProcessLogChunk implements Serializable {

        private final int start;
        private final byte[] data;

        public ProcessLogChunk(int start, byte[] data) { // NOSONAR
            this.start = start;
            this.data = data;
        }

        public int getStart() {
            return start;
        }

        public byte[] getData() {
            return data;
        }
    }

    public static final class ProcessLog implements Serializable {

        private final int size;
        private final List<ProcessLogChunk> chunks;

        public ProcessLog(int size, List<ProcessLogChunk> chunks) {
            this.size = size;
            this.chunks = chunks;
        }

        public int getSize() {
            return size;
        }

        public List<ProcessLogChunk> getChunks() {
            return chunks;
        }
    }
}
