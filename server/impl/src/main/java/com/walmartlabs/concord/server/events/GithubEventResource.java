package com.walmartlabs.concord.server.events;

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
import com.walmartlabs.concord.server.cfg.ExternalEventsConfiguration;
import com.walmartlabs.concord.server.cfg.GithubConfiguration;
import com.walmartlabs.concord.server.cfg.TriggersConfiguration;
import com.walmartlabs.concord.server.metrics.WithTimer;
import com.walmartlabs.concord.server.org.project.EncryptedProjectValueManager;
import com.walmartlabs.concord.server.org.project.ProjectDao;
import com.walmartlabs.concord.server.org.project.ProjectEntry;
import com.walmartlabs.concord.server.org.project.RepositoryDao;
import com.walmartlabs.concord.server.org.triggers.TriggerEntry;
import com.walmartlabs.concord.server.org.triggers.TriggersDao;
import com.walmartlabs.concord.server.process.ProcessManager;
import com.walmartlabs.concord.server.process.ProcessSecurityContext;
import com.walmartlabs.concord.server.security.GithubAuthenticatingFilter;
import com.walmartlabs.concord.server.security.github.GithubKey;
import com.walmartlabs.concord.server.security.ldap.LdapManager;
import com.walmartlabs.concord.server.security.ldap.LdapPrincipal;
import com.walmartlabs.concord.server.user.UserEntry;
import com.walmartlabs.concord.server.user.UserManager;
import com.walmartlabs.concord.server.user.UserType;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.siesta.Resource;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.naming.NamingException;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.UriInfo;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

import static com.walmartlabs.concord.repository.GitCliRepositoryProvider.DEFAULT_BRANCH;

@Named
@Singleton
@Api(value = "GitHub Events", authorizations = {})
@Path("/events/github")
public class GithubEventResource extends AbstractEventResource implements Resource {

    private static final Logger log = LoggerFactory.getLogger(GithubEventResource.class);

    private static final RepositoryItem UNKNOWN_REPO = new RepositoryItem(null, null, null);

    private static final String EVENT_SOURCE = "github";

    private static final String AUTHOR_KEY = "author";
    private static final String COMMIT_ID_KEY = "commitId";
    private static final String ORG_NAME_KEY = "org";
    private static final String PAYLOAD_KEY = "payload";
    private static final String PROJECT_NAME_KEY = "project";
    private static final String REPO_BRANCH_KEY = "branch";
    private static final String REPO_ID_KEY = "repositoryId";
    private static final String REPO_NAME_KEY = "repository";
    private static final String UNKNOWN_REPO_KEY = "unknownRepo";
    private static final String STATUS_KEY = "status";
    private static final String TYPE_KEY = "type";
    private static final String PULL_REQUEST_EVENT = "pull_request";
    private static final String PUSH_EVENT = "push";

    private static final String DEFAULT_EVENT_TYPE = "push";

    private final ProjectDao projectDao;
    private final RepositoryDao repositoryDao;
    private final GithubConfiguration githubCfg;
    private final EncryptedProjectValueManager encryptedValueManager;
    private final LdapManager ldapManager;
    private final UserManager userManager;

    @Inject
    public GithubEventResource(ExternalEventsConfiguration cfg,
                               ProjectDao projectDao,
                               TriggersDao triggersDao,
                               RepositoryDao repositoryDao,
                               ProcessManager processManager,
                               EncryptedProjectValueManager encryptedValueManager,
                               TriggersConfiguration triggersConfiguration,
                               GithubConfiguration githubCfg,
                               LdapManager ldapManager,
                               UserManager userManager,
                               ProcessSecurityContext processSecurityContext) {

        super(cfg, processManager, triggersDao, projectDao, repositoryDao,
                new GithubTriggerDefinitionEnricher(projectDao, githubCfg),
                triggersConfiguration, userManager, processSecurityContext);

        this.projectDao = projectDao;
        this.repositoryDao = repositoryDao;
        this.githubCfg = githubCfg;
        this.encryptedValueManager = encryptedValueManager;
        this.ldapManager = ldapManager;
        this.userManager = userManager;
    }

    @POST
    @ApiOperation("Handles GitHub repository level events")
    @Path("/webhook")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    @WithTimer
    @SuppressWarnings("unchecked")
    public String onEvent(@ApiParam Map<String, Object> payload,
                          @HeaderParam("X-GitHub-Event") String eventName,
                          @Context UriInfo uriInfo) {

        if ("ping".equalsIgnoreCase(eventName)) {
            return "ok";
        }

        if (payload == null) {
            return "ok";
        }

        Map<String, Object> repo = (Map<String, Object>) payload.getOrDefault(REPO_NAME_KEY, Collections.emptyMap());
        String repoName = (String) repo.get("full_name");
        if (repoName == null) {
            return "ok";
        }

        // support for hooks restricted to a specific repository
        GithubKey githubKey = GithubKey.getCurrent();
        UUID hookProjectId = githubKey.getProjectId();
        String hookRepoHash = githubKey.getRepoToken();

        String eventBranch = getBranch(payload, eventName);
        List<RepositoryItem> repos = findRepos(repoName, eventBranch, hookProjectId, hookRepoHash);
        boolean unknownRepo = repos.isEmpty();
        if (unknownRepo) {
            repos = Collections.singletonList(UNKNOWN_REPO);
        }

        for (RepositoryItem r : repos) {
            Map<String, Object> conditions = buildConditions(payload, r.repositoryName, eventBranch, r.project, eventName);
            conditions = enrich(conditions, uriInfo);

            Map<String, Object> event = buildTriggerEvent(payload, r.id, r.project, conditions);

            String eventId = UUID.randomUUID().toString();
            int count = process(eventId, EVENT_SOURCE, conditions, event, (t, cfg) -> {
                // if `useEventCommitId` is true then the process is forced to use the specified commit ID
                String commitId = (String) event.get(COMMIT_ID_KEY);
                if (commitId != null && t.isUseEventCommitId()) {
                    cfg.put(Constants.Request.REPO_COMMIT_ID, event.get(COMMIT_ID_KEY));
                }
                return cfg;
            });

            log.info("payload ['{}'] -> {} processes started", eventId, count);
        }

        if (unknownRepo) {
            log.warn("'onEvent ['{}'] -> repository '{}' not found", eventName, repoName);
            return "ok";
        }

        return "ok";
    }

    private List<RepositoryItem> findRepos(String repoName, String branch, UUID hookProjectId, String hookRepoToken) {
        return repositoryDao.find(repoName).stream()
                .filter(r -> GithubUtils.isRepositoryUrl(repoName, r.getUrl(), githubCfg.getGithubDomain()))
                .filter(r -> isBranchEq(r.getBranch(), branch))
                .filter(r -> isRepoHashValid(hookProjectId, hookRepoToken, r.getProjectId(), r.getUrl()))
                .map(r -> {
                    ProjectEntry project = projectDao.get(r.getProjectId());
                    return new RepositoryItem(r.getId(), project, r.getName());
                })
                .filter(r -> r.project != null)
                .collect(Collectors.toList());
    }

    private static boolean isBranchEq(String repoBranch, String eventBranch) {
        if (eventBranch == null) {
            return true;
        }

        if (repoBranch == null) {
            return DEFAULT_BRANCH.equals(eventBranch);
        }

        return repoBranch.equals(eventBranch);
    }

    private boolean isRepoHashValid(UUID hookProjectId, String hookRepoToken, UUID projectId, String repoUrl) {
        if (hookProjectId == null || hookRepoToken == null) {
            // true for organization level hooks
            return true;
        }

        if (!hookProjectId.equals(projectId)) {
            return false;
        }

        try {
            byte[] decodedHash = Base64.getDecoder().decode(hookRepoToken);
            byte[] decryptedHash = encryptedValueManager.decrypt(projectId, decodedHash);
            String userHash = new String(decryptedHash, StandardCharsets.UTF_8);

            String repositoryName = GithubUtils.getRepositoryName(repoUrl);
            String repoHash = hashRepo(repositoryName);

            return repoHash.equals(userHash);
        } catch (Exception e) {
            return false;
        }
    }

    private String hashRepo(String repoName) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }

        byte[] ab = md.digest(repoName.getBytes(StandardCharsets.UTF_8));

        return Base64.getEncoder().withoutPadding().encodeToString(ab);
    }

    @Override
    protected UserEntry getOrCreateUserEntry(Map<String, Object> event) {
        if (!githubCfg.isUseSenderLdapDn()) {
            return super.getOrCreateUserEntry(event);
        }

        String ldapDn = getSenderLdapDn(event);
        if (ldapDn == null) {
            log.warn("getOrCreateUserEntry ['{}'] -> can't determine the sender's 'ldap_dn', falling back to 'login'", event);
            return super.getOrCreateUserEntry(event);
        }

        // only LDAP users are supported in GitHub triggers
        try {
            LdapPrincipal p = ldapManager.getPrincipalByDn(ldapDn);
            return userManager.getOrCreate(p.getUsername(), p.getDomain(), UserType.LDAP);
        } catch (NamingException e) {
            throw new RuntimeException(e);
        }
    }

    private static class RepositoryItem {

        private final UUID id;

        private final String repositoryName;

        private final ProjectEntry project;

        public RepositoryItem(UUID id, ProjectEntry project, String repositoryName) {
            this.id = id;
            this.repositoryName = repositoryName;
            this.project = project;
        }
    }

    private static Map<String, Object> enrich(Map<String, Object> event, UriInfo uriInfo) {
        if (uriInfo == null) {
            return event;
        }

        MultivaluedMap<String, String> qp = uriInfo.getQueryParameters();
        if (qp == null || qp.isEmpty()) {
            return event;
        }

        Map<String, Object> m = new HashMap<>(event);
        qp.keySet().forEach(k -> m.put(k, qp.getFirst(k)));

        m.remove(GithubAuthenticatingFilter.HOOK_PROJECT_ID);
        m.remove(GithubAuthenticatingFilter.HOOK_REPO_TOKEN);

        return m;
    }

    private static Map<String, Object> buildTriggerEvent(Map<String, Object> payload,
                                                         UUID repoId,
                                                         ProjectEntry project,
                                                         Map<String, Object> conditions) {

        Map<String, Object> m = new HashMap<>();
        m.put(COMMIT_ID_KEY, payload.get("after"));
        if (repoId != null) {
            m.put(REPO_ID_KEY, repoId);
        }
        m.putAll(conditions);
        if (project != null) {
            m.put(PROJECT_NAME_KEY, project.getName());
            m.put(ORG_NAME_KEY, project.getOrgName());
        } else {
            m.remove(PROJECT_NAME_KEY);
            m.remove(ORG_NAME_KEY);
        }

        m.put(PAYLOAD_KEY, payload);

        return m;
    }

    private static String getBranch(Map<String, Object> event, String eventName) {
        if (PUSH_EVENT.equalsIgnoreCase(eventName)) {
            return getBranchPush(event);
        } else if (PULL_REQUEST_EVENT.equalsIgnoreCase(eventName)) {
            return getBranchPullRequest(event);
        }

        return null;
    }

    private static String getBranchPush(Map<String, Object> event) {
        String ref = (String) event.get("ref");
        if (ref == null) {
            return null;
        }

        return GithubUtils.getRefShortName(ref);
    }

    @SuppressWarnings("unchecked")
    private static String getBranchPullRequest(Map<String, Object> event) {
        Map<String, Object> pr = (Map<String, Object>) event.get(PULL_REQUEST_EVENT);
        if (pr == null) {
            return null;
        }

        Map<String, Object> base = (Map<String, Object>) pr.get("base");
        return (String) base.get("ref");
    }

    private static Map<String, Object> buildConditions(Map<String, Object> event,
                                                       String repoName, String branch,
                                                       ProjectEntry project, String eventName) {
        Map<String, Object> result = new HashMap<>();
        if (project != null) {
            result.put(ORG_NAME_KEY, project.getOrgName());
            result.put(PROJECT_NAME_KEY, project.getName());
            result.put(REPO_NAME_KEY, repoName);
            result.put(UNKNOWN_REPO_KEY, false);
        } else {
            result.put(ORG_NAME_KEY, "n/a");
            result.put(PROJECT_NAME_KEY, "n/a");
            result.put(REPO_NAME_KEY, "n/a");
            result.put(UNKNOWN_REPO_KEY, true);
        }
        result.put(REPO_BRANCH_KEY, branch);
        result.put(AUTHOR_KEY, getSender(event));
        result.put(TYPE_KEY, eventName);
        result.put(STATUS_KEY, event.get("action"));
        result.put(PAYLOAD_KEY, event);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static String getSender(Map<String, Object> event) {
        Map<String, Object> sender = (Map<String, Object>) event.get("sender");
        if (sender == null) {
            return null;
        }

        return (String) sender.get("login");
    }

    @SuppressWarnings("unchecked")
    private static String getSenderLdapDn(Map<String, Object> event) {
        Map<String, Object> payload = (Map<String, Object>) event.get("payload");
        if (payload == null) {
            return null;
        }

        Map<String, Object> sender = (Map<String, Object>) payload.get("sender");
        if (sender == null) {
            return null;
        }

        return (String) sender.get("ldap_dn");
    }

    private static class GithubTriggerDefinitionEnricher implements TriggerDefinitionEnricher {

        private final ProjectDao projectDao;
        private final GithubConfiguration cfg;

        private GithubTriggerDefinitionEnricher(ProjectDao projectDao, GithubConfiguration cfg) {
            this.projectDao = projectDao;
            this.cfg = cfg;
        }

        @Override
        public TriggerEntry enrich(TriggerEntry entry) {
            // note that the resulting conditions must be compatible with the system trigger definitions
            // see com/walmartlabs/concord/server/org/triggers/concord.yml

            Map<String, Object> conditions = new HashMap<>();

            // add default conditions from the cfg file
            if (cfg.getDefaultFilter() != null) {
                conditions.putAll(cfg.getDefaultFilter());
            }

            // add the trigger definition's conditions
            if (entry.getConditions() != null) {
                conditions.putAll(entry.getConditions());
            }

            // compute the additional filters
            conditions.computeIfAbsent(ORG_NAME_KEY, k -> {
                ProjectEntry e = projectDao.get(entry.getProjectId());
                if (e == null) {
                    return null;
                }
                return e.getOrgName();
            });
            conditions.computeIfAbsent(PROJECT_NAME_KEY, k -> entry.getProjectName());
            conditions.computeIfAbsent(REPO_NAME_KEY, k -> entry.getRepositoryName());

            // TODO remove once the documentation and existing triggers are updated
            conditions.putIfAbsent(TYPE_KEY, DEFAULT_EVENT_TYPE);

            return new TriggerEntry(entry.getId(),
                    entry.getOrgId(),
                    entry.getOrgName(),
                    entry.getProjectId(),
                    entry.getProjectName(),
                    entry.getRepositoryId(),
                    entry.getRepositoryName(),
                    entry.getEventSource(),
                    entry.getActiveProfiles(),
                    entry.getArguments(),
                    conditions,
                    entry.getCfg());
        }
    }
}
