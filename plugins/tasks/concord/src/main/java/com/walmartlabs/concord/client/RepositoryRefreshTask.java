package com.walmartlabs.concord.client;

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

import com.walmartlabs.concord.sdk.Context;
import com.walmartlabs.concord.sdk.Task;

import javax.inject.Named;
import java.util.Map;

import static com.walmartlabs.concord.sdk.MapUtils.getString;

@Named("repositoryRefresh")
public class RepositoryRefreshTask extends AbstractConcordTask implements Task {

    private static final String ORG_KEY = "org";
    private static final String REPOSITORY_KEY = "repository";
    private static final String PROJECT_KEY = "project";

    @Override
    public void execute(Context ctx) throws Exception {
        Map<String, Object> cfg = createCfg(ctx, ORG_KEY, REPOSITORY_KEY, PROJECT_KEY);
        String orgName = getString(cfg, ORG_KEY);
        String projectName = getString(cfg, PROJECT_KEY);
        String repositoryName = getString(cfg, REPOSITORY_KEY);

        withClient(ctx, client -> {
            RepositoriesApi api = new RepositoriesApi(client);
            api.refreshRepository(orgName, projectName, repositoryName, true);
            return null;
        });
    }
}
