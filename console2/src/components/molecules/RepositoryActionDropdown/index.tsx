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

import * as React from 'react';
import { Dropdown, Icon } from 'semantic-ui-react';

import { ConcordKey } from '../../../api/common';
import { RepositoryEntry, RepositoryMeta } from '../../../api/org/project/repository';
import {
    DeleteRepositoryPopup,
    RefreshRepositoryPopup,
    StartRepositoryPopup,
    ValidateRepositoryPopup,
    RepositoryTriggersPopup
} from '../../organisms';

interface ExternalProps {
    orgName: ConcordKey;
    projectName: ConcordKey;
    repo: RepositoryEntry;
}

const getProfiles = (meta?: RepositoryMeta): string[] => {
    if (!meta) {
        return [];
    }
    return meta.profiles || [];
};

const getEntryPoints = (meta?: RepositoryMeta): string[] => {
    if (!meta) {
        return [];
    }
    return meta.entryPoints || [];
};

class RepositoryActionDropdown extends React.PureComponent<ExternalProps> {
    render() {
        const { orgName, projectName, repo } = this.props;

        const {
            name: repoName,
            url: repoURL,
            branch: repoBranch,
            commitId: repoCommitId,
            path: repoPath,
            meta: repoMeta
        } = repo;

        // show the commit ID if defined, otherwise show the branch name or fallback to 'master'
        const repoBranchOrCommitId = repoCommitId
            ? repoCommitId
            : repoBranch
            ? repoBranch
            : 'master';
        const repoPathOrDefault = repoPath ? repoPath : '/';

        return (
            <Dropdown icon="ellipsis vertical">
                <Dropdown.Menu>
                    <StartRepositoryPopup
                        orgName={orgName}
                        projectName={projectName}
                        repoName={repoName}
                        repoURL={repoURL}
                        repoBranchOrCommitId={repoBranchOrCommitId}
                        repoPath={repoPathOrDefault}
                        repoProfiles={getProfiles(repoMeta)}
                        repoEntryPoints={getEntryPoints(repoMeta)}
                        trigger={(onClick: any) => (
                            <Dropdown.Item onClick={onClick} disabled={repo.disabled}>
                                <Icon name="play" color="blue" />
                                <span className="text">Run</span>
                            </Dropdown.Item>
                        )}
                    />
                    <ValidateRepositoryPopup
                        orgName={orgName}
                        projectName={projectName}
                        repoName={repoName}
                        trigger={(onClick: any) => (
                            <Dropdown.Item onClick={onClick}>
                                <Icon name="check" />
                                <span className="text">Validate</span>
                            </Dropdown.Item>
                        )}
                    />

                    <RepositoryTriggersPopup
                        orgName={orgName}
                        projectName={projectName}
                        repoName={repoName}
                        trigger={(onClick: any) => (
                            <Dropdown.Item onClick={onClick}>
                                <Icon name="lightning" />
                                <span className="text">Triggers</span>
                            </Dropdown.Item>
                        )}
                    />

                    <RefreshRepositoryPopup
                        orgName={orgName}
                        projectName={projectName}
                        repoName={repoName}
                        trigger={(onClick: any) => (
                            <Dropdown.Item onClick={onClick}>
                                <Icon name="refresh" />
                                <span className="text">Refresh</span>
                            </Dropdown.Item>
                        )}
                    />

                    <DeleteRepositoryPopup
                        orgName={orgName}
                        projectName={projectName}
                        repoName={repoName}
                        trigger={(onClick: any) => (
                            <Dropdown.Item onClick={onClick}>
                                <Icon name="delete" color="red" />
                                <span className="text">Delete</span>
                            </Dropdown.Item>
                        )}
                    />
                </Dropdown.Menu>
            </Dropdown>
        );
    }
}

export default RepositoryActionDropdown;
