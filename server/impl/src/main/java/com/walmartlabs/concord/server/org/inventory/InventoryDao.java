package com.walmartlabs.concord.server.org.inventory;

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
import com.walmartlabs.concord.server.Utils;
import com.walmartlabs.concord.server.jooq.tables.Inventories;
import com.walmartlabs.concord.server.org.ResourceAccessLevel;
import org.jooq.*;
import org.jooq.impl.DSL;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.List;
import java.util.UUID;

import static com.walmartlabs.concord.server.jooq.Tables.INVENTORY_TEAM_ACCESS;
import static com.walmartlabs.concord.server.jooq.Tables.V_USER_TEAMS;
import static com.walmartlabs.concord.server.jooq.tables.Inventories.INVENTORIES;
import static com.walmartlabs.concord.server.jooq.tables.Organizations.ORGANIZATIONS;
import static com.walmartlabs.concord.server.jooq.tables.Users.USERS;
import static org.jooq.impl.DSL.*;

@Named
public class InventoryDao extends AbstractDao {

    @Inject
    public InventoryDao(@MainDB Configuration cfg) {
        super(cfg);
    }

    public UUID getId(UUID orgId, String inventoryName) {
        try (DSLContext tx = DSL.using(cfg)) {
            return tx.select(INVENTORIES.INVENTORY_ID)
                    .from(INVENTORIES)
                    .where(INVENTORIES.INVENTORY_NAME.eq(inventoryName).and(INVENTORIES.ORG_ID.eq(orgId)))
                    .fetchOne(INVENTORIES.INVENTORY_ID);
        }
    }

    public UUID insert(UUID ownerId, String name, UUID orgId, UUID parentId, InventoryVisibility visibility) {
        return txResult(tx -> insert(tx, ownerId, name, orgId, parentId, visibility));
    }

    public void update(UUID inventoryId, String inventoryName, UUID parentId, InventoryVisibility visibility) {
        tx(tx -> update(tx, inventoryId, inventoryName, parentId, visibility));
    }

    public void delete(UUID inventoryId) {
        tx(tx -> delete(tx, inventoryId));
    }

    public List<InventoryEntry> list(UUID orgId) {
        try (DSLContext tx = DSL.using(cfg)) {
            Field<String> orgNameField = select(ORGANIZATIONS.ORG_NAME)
                    .from(ORGANIZATIONS)
                    .where(ORGANIZATIONS.ORG_ID.eq(INVENTORIES.ORG_ID))
                    .asField();

            Field<String> ownerUsernameField = select(USERS.USERNAME)
                    .from(USERS)
                    .where(USERS.USER_ID.eq(INVENTORIES.OWNER_ID))
                    .asField();

            return tx.select(INVENTORIES.INVENTORY_ID,
                    INVENTORIES.INVENTORY_NAME,
                    INVENTORIES.ORG_ID,
                    orgNameField,
                    INVENTORIES.VISIBILITY,
                    INVENTORIES.OWNER_ID,
                    ownerUsernameField,
                    INVENTORIES.PARENT_INVENTORY_ID)
                    .from(INVENTORIES)
                    .where(INVENTORIES.ORG_ID.eq(orgId))
                    .orderBy(INVENTORIES.INVENTORY_NAME)
                    .fetch(InventoryDao::toEntry);
        }
    }

    public InventoryEntry get(UUID inventoryId) {
        try (DSLContext tx = DSL.using(cfg)) {
            Table<Record> nodes = table("nodes");
            Inventories i1 = INVENTORIES.as("i1");
            Inventories i2 = INVENTORIES.as("i2");

            Field<String> orgNameField1 = select(ORGANIZATIONS.ORG_NAME).from(ORGANIZATIONS).where(ORGANIZATIONS.ORG_ID.eq(i1.ORG_ID)).asField();
            Field<String> orgNameField2 = select(ORGANIZATIONS.ORG_NAME).from(ORGANIZATIONS).where(ORGANIZATIONS.ORG_ID.eq(i2.ORG_ID)).asField();

            Field<String> ownerUsernameField1 = select(USERS.USERNAME)
                    .from(USERS)
                    .where(USERS.USER_ID.eq(i1.OWNER_ID))
                    .asField();

            Field<String> ownerUsernameField2 = select(USERS.USERNAME)
                    .from(USERS)
                    .where(USERS.USER_ID.eq(i2.OWNER_ID))
                    .asField();

            SelectConditionStep<Record8<UUID, String, UUID, UUID, String, String, UUID, String>> s1 =
                    select(i1.INVENTORY_ID, i1.INVENTORY_NAME, i1.PARENT_INVENTORY_ID, i1.ORG_ID, orgNameField1, i1.VISIBILITY, i1.OWNER_ID, ownerUsernameField1)
                            .from(i1)
                            .where(i1.INVENTORY_ID.eq(inventoryId));

            SelectConditionStep<Record8<UUID, String, UUID, UUID, String, String, UUID, String>> s2 =
                    select(i2.INVENTORY_ID, i2.INVENTORY_NAME, i2.PARENT_INVENTORY_ID, i2.ORG_ID, orgNameField2, i2.VISIBILITY, i2.OWNER_ID, ownerUsernameField2)
                            .from(i2, nodes)
                            .where(i2.INVENTORY_ID.eq(INVENTORIES.as("nodes").PARENT_INVENTORY_ID));

            List<InventoryEntry> items =
                    tx.withRecursive("nodes",
                            INVENTORIES.INVENTORY_ID.getName(),
                            INVENTORIES.INVENTORY_NAME.getName(),
                            INVENTORIES.PARENT_INVENTORY_ID.getName(),
                            INVENTORIES.ORG_ID.getName(),
                            ORGANIZATIONS.ORG_NAME.getName(),
                            INVENTORIES.VISIBILITY.getName(),
                            INVENTORIES.OWNER_ID.getName(),
                            USERS.USERNAME.getName())
                            .as(s1.unionAll(s2))
                            .select().from(nodes)
                            .fetch(InventoryDao::toEntity);

            if (items.isEmpty()) {
                return null;
            }

            return buildEntity(inventoryId, items);
        }
    }

    public void upsertAccessLevel(UUID inventoryId, UUID teamId, ResourceAccessLevel level) {
        tx(tx -> upsertAccessLevel(tx, inventoryId, teamId, level));
    }

    public void upsertAccessLevel(DSLContext tx, UUID inventoryId, UUID teamId, ResourceAccessLevel level) {
        tx.insertInto(INVENTORY_TEAM_ACCESS)
                .columns(INVENTORY_TEAM_ACCESS.INVENTORY_ID, INVENTORY_TEAM_ACCESS.TEAM_ID, INVENTORY_TEAM_ACCESS.ACCESS_LEVEL)
                .values(inventoryId, teamId, level.toString())
                .onDuplicateKeyUpdate()
                .set(INVENTORY_TEAM_ACCESS.ACCESS_LEVEL, level.toString())
                .execute();
    }

    public boolean hasAccessLevel(UUID inventoryId, UUID userId, ResourceAccessLevel... levels) {
        try (DSLContext tx = DSL.using(cfg)) {
            return hasAccessLevel(tx, inventoryId, userId, levels);
        }
    }

    private boolean hasAccessLevel(DSLContext tx, UUID inventoryId, UUID userId, ResourceAccessLevel... levels) {
        SelectConditionStep<Record1<UUID>> teamIds = select(V_USER_TEAMS.TEAM_ID)
                .from(V_USER_TEAMS)
                .where(V_USER_TEAMS.USER_ID.eq(userId));

        return tx.fetchExists(selectFrom(INVENTORY_TEAM_ACCESS)
                .where(INVENTORY_TEAM_ACCESS.INVENTORY_ID.eq(inventoryId)
                        .and(INVENTORY_TEAM_ACCESS.TEAM_ID.in(teamIds))
                        .and(INVENTORY_TEAM_ACCESS.ACCESS_LEVEL.in(Utils.toString(levels)))));
    }

    private static InventoryEntry buildEntity(UUID inventoryId, List<InventoryEntry> items) {
        InventoryEntry i = items.stream()
                .filter(e -> e.getId().equals(inventoryId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Can't find inventory in results"));

        return new InventoryEntry(inventoryId, i.getName(), i.getOrgId(), i.getOrgName(), i.getVisibility(),
                i.getOwner(), buildParent(i.getParentId(), items));
    }

    private UUID insert(DSLContext tx, UUID ownerId, String name, UUID orgId, UUID parentId, InventoryVisibility visibility) {
        if (visibility == null) {
            visibility = InventoryVisibility.PUBLIC;
        }

        return tx.insertInto(INVENTORIES)
                .columns(INVENTORIES.OWNER_ID, INVENTORIES.INVENTORY_NAME, INVENTORIES.ORG_ID, INVENTORIES.PARENT_INVENTORY_ID, INVENTORIES.VISIBILITY)
                .values(ownerId, name, orgId, parentId, visibility.toString())
                .returning(INVENTORIES.INVENTORY_ID)
                .fetchOne()
                .getInventoryId();
    }

    private void update(DSLContext tx, UUID inventoryId, String inventoryName, UUID parentId, InventoryVisibility visibility) {
        tx.update(INVENTORIES)
                .set(INVENTORIES.INVENTORY_NAME, inventoryName)
                .set(INVENTORIES.PARENT_INVENTORY_ID, parentId)
                .set(INVENTORIES.VISIBILITY, visibility.toString())
                .where(INVENTORIES.INVENTORY_ID.eq(inventoryId))
                .execute();
    }

    private void delete(DSLContext tx, UUID inventoryId) {
        tx.deleteFrom(INVENTORIES)
                .where(INVENTORIES.INVENTORY_ID.eq(inventoryId))
                .execute();
    }

    private static InventoryOwner toOwner(UUID id, String username) {
        if (id == null) {
            return null;
        }
        return new InventoryOwner(id, username);
    }

    private static InventoryEntry buildParent(UUID parentId) {
        return new InventoryEntry(parentId, null, null, null, null, null, null);
    }

    private static InventoryEntry buildParent(UUID parentId, List<InventoryEntry> items) {
        if (parentId == null) {
            return null;
        }

        InventoryEntry entity = items.stream()
                .filter(e -> e.getId().equals(parentId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Can't find parent inventory in results"));

        return new InventoryEntry(entity.getId(), entity.getName(),
                entity.getOrgId(), entity.getOrgName(), entity.getVisibility(), entity.getOwner(),
                buildParent(entity.getParentId(), items));
    }

    // TODO create a unified method
    private static InventoryEntry toEntity(Record r) {
        return new InventoryEntry(r.getValue(INVENTORIES.INVENTORY_ID),
                r.getValue(INVENTORIES.INVENTORY_NAME),
                r.getValue(INVENTORIES.ORG_ID),
                r.getValue(ORGANIZATIONS.ORG_NAME),
                InventoryVisibility.valueOf(r.getValue(INVENTORIES.VISIBILITY)),
                toOwner(r.get(INVENTORIES.OWNER_ID), r.get(USERS.USERNAME)),
                buildParent(r.getValue(INVENTORIES.PARENT_INVENTORY_ID)));
    }

    private static InventoryEntry toEntry(Record8<UUID, String, UUID, String, String, UUID, String, UUID> r) {
        return new InventoryEntry(r.value1(),
                r.value2(),
                r.value3(),
                r.value4(),
                InventoryVisibility.valueOf(r.value5()),
                toOwner(r.value6(), r.value7()),
                buildParent(r.value8()));
    }
}
