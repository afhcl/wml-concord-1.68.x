package com.walmartlabs.concord.server.security.ldap;

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

import com.walmartlabs.concord.db.AbstractDao;
import com.walmartlabs.concord.db.MainDB;
import com.walmartlabs.concord.db.PgUtils;
import com.walmartlabs.concord.server.cfg.LdapGroupSyncConfiguration;
import com.walmartlabs.concord.server.sdk.ScheduledTask;
import com.walmartlabs.concord.server.user.UserDao;
import com.walmartlabs.concord.server.user.UserType;
import org.jooq.Configuration;
import org.jooq.Field;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.sql.Timestamp;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.walmartlabs.concord.server.jooq.Tables.USERS;
import static org.jooq.impl.DSL.currentTimestamp;

@Named("user-ldap-group-sync")
@Singleton
public class UserLdapGroupSynchronizer implements ScheduledTask {

    private static final Logger log = LoggerFactory.getLogger(UserLdapGroupSynchronizer.class);

    private final LdapGroupSyncConfiguration cfg;
    private final Dao dao;
    private final LdapManager ldapManager;
    private final UserDao userDao;
    private final LdapGroupDao ldapGroupsDao;

    @Inject
    public UserLdapGroupSynchronizer(LdapGroupSyncConfiguration cfg, Dao dao, LdapManager ldapManager, UserDao userDao, LdapGroupDao ldapGroupsDao) {
        this.cfg = cfg;
        this.dao = dao;
        this.ldapManager = ldapManager;
        this.userDao = userDao;
        this.ldapGroupsDao = ldapGroupsDao;
    }

    @Override
    public long getIntervalInSec() {
        return cfg.getInterval();
    }

    @Override
    public void performTask() {
        Field<Timestamp> cutoff = currentTimestamp().minus(PgUtils.interval(cfg.getMinAgeSync()));
        long usersCount = 0;
        List<UserItem> users;
        do {
            users = dao.list(cfg.getFetchLimit(), cutoff);
            users.forEach(this::processUser);
            usersCount += users.size();
        } while (users.size() >= cfg.getFetchLimit());

        log.info("performTask -> done, {} user(s) synchronized", usersCount);
    }

    private void processUser(UserItem u) {
        try {
            Set<String> groups = ldapManager.getGroups(u.username);
            if (groups == null) {
                userDao.disable(u.userId);
                log.info("processUser ['{}'] -> not found in LDAP, user is disabled", u.username);
            } else {
                ldapGroupsDao.update(u.userId, groups);
            }
        } catch (Exception e) {
            log.error("processUser ['{}'] -> error", u.username, e);
        }
    }

    @Named
    private static final class Dao extends AbstractDao {

        @Inject
        public Dao(@MainDB Configuration cfg) {
            super(cfg);
        }

        public List<UserItem> list(int limit, Field<Timestamp> cutoff) {
            return txResult(tx -> tx.select(USERS.USER_ID, USERS.USERNAME)
                    .from(USERS)
                    .where(USERS.USER_TYPE.eq(UserType.LDAP.name()))
                    .and(USERS.LAST_GROUP_SYNC_DT.isNull().or(USERS.LAST_GROUP_SYNC_DT.lessThan(cutoff)))
                    .and(USERS.IS_DISABLED.isFalse())
                    .limit(limit)
                    .fetch(r -> new UserItem(r.value1(), r.value2())));
        }
    }

    private static class UserItem {

        private final UUID userId;
        private final String username;

        private UserItem(UUID userId, String username) {
            this.userId = userId;
            this.username = username;
        }
    }
}
