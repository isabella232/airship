/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.proofpoint.galaxy.coordinator;

import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.inject.Inject;
import com.proofpoint.galaxy.shared.AgentStatus;
import com.proofpoint.galaxy.shared.Assignment;
import com.proofpoint.galaxy.shared.AssignmentRepresentation;
import com.proofpoint.galaxy.shared.SlotStatus;
import com.proofpoint.galaxy.shared.SlotStatusWithExpectedState;
import com.proofpoint.galaxy.shared.VersionsUtil;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.util.List;
import java.util.UUID;

import static com.google.common.collect.Collections2.transform;
import static com.proofpoint.galaxy.coordinator.StringFunctions.toStringFunction;
import static com.proofpoint.galaxy.shared.VersionsUtil.GALAXY_SLOTS_VERSION_HEADER;
import static com.proofpoint.galaxy.shared.VersionsUtil.checkSlotsVersion;
import static com.proofpoint.galaxy.shared.SlotStatusRepresentation.fromSlotStatusWithShortIdPrefixSize;
import static com.proofpoint.galaxy.shared.SlotStatusRepresentation.toSlotRepresentation;
import static java.lang.Math.max;

@Path("/v1/slot")
public class CoordinatorSlotResource
{
    private final Coordinator coordinator;

    public static final int MIN_PREFIX_SIZE = 4;

    @Inject
    public CoordinatorSlotResource(Coordinator coordinator)
    {
        Preconditions.checkNotNull(coordinator, "coordinator must not be null");

        this.coordinator = coordinator;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAllSlots(@Context UriInfo uriInfo)
    {
        List<SlotStatus> allSlotStatus = coordinator.getAllSlotStatus();
        List<UUID> uuids = Lists.transform(allSlotStatus, SlotStatus.uuidGetter());

        int prefixSize = MIN_PREFIX_SIZE;
        if (!uuids.isEmpty()) {
            prefixSize = max(prefixSize, Strings.shortestUniquePrefix(transform(uuids, toStringFunction())));
        }

        Predicate<SlotStatus> slotFilter = SlotFilterBuilder.build(uriInfo, false, uuids);

        Iterable<SlotStatusWithExpectedState> result = coordinator.getAllSlotStatusWithExpectedState(slotFilter);

        return Response.ok(Iterables.transform(result, toSlotRepresentation(prefixSize)))
                .header(GALAXY_SLOTS_VERSION_HEADER, VersionsUtil.createSlotsVersion(allSlotStatus))
                .build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response install(
            AssignmentRepresentation assignmentRepresentation,
            @DefaultValue("1") @QueryParam("limit") int limit,
            @Context UriInfo uriInfo)
    {
        Preconditions.checkNotNull(assignmentRepresentation, "assignmentRepresentation must not be null");
        Preconditions.checkArgument(limit > 0, "limit must be at least 1");

        Assignment assignment = assignmentRepresentation.toAssignment();

        List<SlotStatus> allSlotStatus = coordinator.getAllSlotStatus();

        List<UUID> uuids = Lists.transform(allSlotStatus, SlotStatus.uuidGetter());
        Predicate<AgentStatus> agentFilter = AgentFilterBuilder.build(uriInfo, uuids);
        List<SlotStatus> results = coordinator.install(agentFilter, limit, assignment);

        int uniquePrefixSize = max(Strings.shortestUniquePrefix(transform(uuids, toStringFunction())), MIN_PREFIX_SIZE);

        return Response.ok(transform(results, fromSlotStatusWithShortIdPrefixSize(uniquePrefixSize)))
                .header(GALAXY_SLOTS_VERSION_HEADER, VersionsUtil.createSlotsVersion(allSlotStatus))
                .build();
    }

    @DELETE
    @Produces(MediaType.APPLICATION_JSON)
    public Response terminateSlots(@Context UriInfo uriInfo,
            @HeaderParam(GALAXY_SLOTS_VERSION_HEADER) String expectedSlotsVersion)
    {
        List<SlotStatus> allSlotStatus = coordinator.getAllSlotStatus();
        checkSlotsVersion(expectedSlotsVersion, allSlotStatus);

        List<UUID> uuids = Lists.transform(allSlotStatus, SlotStatus.uuidGetter());
        int prefixSize = MIN_PREFIX_SIZE;
        if (!uuids.isEmpty()) {
            prefixSize = max(prefixSize, Strings.shortestUniquePrefix(transform(uuids, toStringFunction())));
        }

        Predicate<SlotStatus> slotFilter = SlotFilterBuilder.build(uriInfo, true, uuids);

        List<SlotStatus> result = coordinator.terminate(slotFilter);

        return Response.ok(transform(result, fromSlotStatusWithShortIdPrefixSize(prefixSize)))
                .header(GALAXY_SLOTS_VERSION_HEADER, VersionsUtil.createSlotsVersion(allSlotStatus))
                .build();
    }
}
