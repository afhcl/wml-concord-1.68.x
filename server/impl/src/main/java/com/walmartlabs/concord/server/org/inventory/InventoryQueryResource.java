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

import com.walmartlabs.concord.common.validation.ConcordKey;
import com.walmartlabs.concord.server.OperationResult;
import com.walmartlabs.concord.server.sdk.metrics.WithTimer;
import com.walmartlabs.concord.server.org.OrganizationEntry;
import com.walmartlabs.concord.server.org.OrganizationManager;
import com.walmartlabs.concord.server.org.ResourceAccessLevel;
import com.walmartlabs.concord.server.sdk.ConcordApplicationException;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.Authorization;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import org.sonatype.siesta.Resource;
import org.sonatype.siesta.ValidationErrorsException;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.validation.Valid;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Named
@Singleton
@Api(value = "Inventory Queries", authorizations = {@Authorization("api_key"), @Authorization("session_key"), @Authorization("ldap")})
@Path("/api/v1/org")
public class InventoryQueryResource implements Resource {

    private final OrganizationManager orgManager;
    private final InventoryManager inventoryManager;
    private final InventoryQueryDao inventoryQueryDao;
    private final InventoryQueryExecDao inventoryQueryExecDao;

    @Inject
    public InventoryQueryResource(OrganizationManager orgManager,
                                  InventoryManager inventoryManager,
                                  InventoryQueryDao inventoryQueryDao,
                                  InventoryQueryExecDao inventoryQueryExecDao) {

        this.inventoryManager = inventoryManager;
        this.orgManager = orgManager;
        this.inventoryQueryDao = inventoryQueryDao;
        this.inventoryQueryExecDao = inventoryQueryExecDao;
    }

    /**
     * Returns inventory query
     *
     * @param orgName       organization's name
     * @param inventoryName inventory's name
     * @param queryName     query's name
     * @return query text
     */
    @GET
    @ApiOperation("Get inventory query")
    @Path("/{orgName}/inventory/{inventoryName}/query/{queryName}")
    @Produces(MediaType.APPLICATION_JSON)
    public InventoryQueryEntry get(@ApiParam @PathParam("orgName") @ConcordKey String orgName,
                                   @ApiParam @PathParam("inventoryName") @ConcordKey String inventoryName,
                                   @ApiParam @PathParam("queryName") @ConcordKey String queryName) {

        OrganizationEntry org = orgManager.assertAccess(orgName, true);

        InventoryEntry inventory = inventoryManager.assertInventoryAccess(org.getId(), inventoryName, ResourceAccessLevel.READER, true);

        UUID queryId = assertQuery(inventory.getId(), queryName);
        return inventoryQueryDao.get(queryId);
    }

    /**
     * Creates or updates inventory query
     *
     * @param orgName       organization's name
     * @param inventoryName inventory's name
     * @param queryName     query's name
     * @param text          query text
     * @return
     */
    @POST
    @ApiOperation("Create or update inventory query")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/{orgName}/inventory/{inventoryName}/query/{queryName}")
    public CreateInventoryQueryResponse createOrUpdate(@ApiParam @PathParam("orgName") @ConcordKey String orgName,
                                                       @ApiParam @PathParam("inventoryName") @ConcordKey String inventoryName,
                                                       @ApiParam @PathParam("queryName") @ConcordKey String queryName,
                                                       @ApiParam String text) {

        OrganizationEntry org = orgManager.assertAccess(orgName, true);
        InventoryEntry inventory = inventoryManager.assertInventoryAccess(org.getId(), inventoryName, ResourceAccessLevel.READER, true);

        validateQuery(text);

        UUID inventoryId = inventory.getId();
        UUID queryId = inventoryQueryDao.getId(inventoryId, queryName);

        if (queryId == null) {
            queryId = inventoryQueryDao.insert(inventoryId, queryName, text);
            return new CreateInventoryQueryResponse(OperationResult.CREATED, queryId);
        } else {
            inventoryQueryDao.update(queryId, inventoryId, queryName, text);
            return new CreateInventoryQueryResponse(OperationResult.UPDATED, queryId);
        }
    }

    /**
     * List inventory queries.
     *
     * @param orgName       organization's name
     * @param inventoryName inventory's name
     * @return
     */
    @GET
    @ApiOperation(value = "List inventory queries", responseContainer = "list", response = InventoryQueryEntry.class)
    @Path("/{orgName}/inventory/{inventoryName}/query")
    @Produces(MediaType.APPLICATION_JSON)
    public List<InventoryQueryEntry> list(@ApiParam @PathParam("orgName") @ConcordKey String orgName,
                                          @ApiParam @PathParam("inventoryName") @ConcordKey String inventoryName) {

        OrganizationEntry org = orgManager.assertAccess(orgName, true);
        InventoryEntry inventory = inventoryManager.assertInventoryAccess(org.getId(), inventoryName, ResourceAccessLevel.READER, true);
        return inventoryQueryDao.list(inventory.getId());
    }

    /**
     * Deletes inventory query
     *
     * @param orgName       organization's name
     * @param inventoryName inventory's name
     * @param queryName     query's name
     * @return
     */
    @DELETE
    @ApiOperation("Delete inventory query")
    @Path("/{orgName}/inventory/{inventoryName}/query/{queryName}")
    @Produces(MediaType.APPLICATION_JSON)
    public DeleteInventoryQueryResponse delete(@ApiParam @PathParam("orgName") @ConcordKey String orgName,
                                               @ApiParam @PathParam("inventoryName") @ConcordKey String inventoryName,
                                               @ApiParam @PathParam("queryName") @ConcordKey String queryName) {

        OrganizationEntry org = orgManager.assertAccess(orgName, true);
        InventoryEntry inventory = inventoryManager.assertInventoryAccess(org.getId(), inventoryName, ResourceAccessLevel.READER, true);

        UUID inventoryId = inventory.getId();
        UUID queryId = assertQuery(inventoryId, queryName);
        inventoryQueryDao.delete(queryId);
        return new DeleteInventoryQueryResponse();
    }

    /**
     * Executes inventory query
     *
     * @param orgName       organization's name
     * @param inventoryName inventory's name
     * @param queryName     query's name
     * @param params        query params
     * @return query result
     */
    @POST
    @ApiOperation("Execute inventory query")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/{orgName}/inventory/{inventoryName}/query/{queryName}/exec")
    @WithTimer
    public List<Object> exec(@ApiParam @PathParam("orgName") @ConcordKey String orgName,
                             @ApiParam @PathParam("inventoryName") @ConcordKey String inventoryName,
                             @ApiParam @PathParam("queryName") @ConcordKey String queryName,
                             @ApiParam @Valid Map<String, Object> params) {

        OrganizationEntry org = orgManager.assertAccess(orgName, true);
        InventoryEntry inventory = inventoryManager.assertInventoryAccess(org.getId(), inventoryName, ResourceAccessLevel.READER, true);

        UUID inventoryId = inventory.getId();
        UUID queryId = assertQuery(inventoryId, queryName);
        try {
            return inventoryQueryExecDao.exec(queryId, params);
        } catch (Exception e) {
            throw new ConcordApplicationException("Error while execution query: " + e.getMessage(), e);
        }
    }

    private UUID assertQuery(UUID inventoryId, String queryName) {
        if (queryName == null) {
            throw new ValidationErrorsException("A valid query name is required");
        }

        UUID id = inventoryQueryDao.getId(inventoryId, queryName);
        if (id == null) {
            throw new ValidationErrorsException("Query not found: " + queryName);
        }
        return id;
    }

    private static void validateQuery(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new ValidationErrorsException("Query should not be empty");
        }

        try {
            CCJSqlParserUtil.parse(text);
        } catch (JSQLParserException e) {
            String msg = e.getCause().getMessage();
            throw new ValidationErrorsException("Query parse error: " + msg);
        }
    }
}
