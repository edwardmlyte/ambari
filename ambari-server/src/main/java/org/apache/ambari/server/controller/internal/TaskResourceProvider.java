/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ambari.server.controller.internal;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.ambari.server.StaticallyInject;
import org.apache.ambari.server.actionmanager.HostRoleCommand;
import org.apache.ambari.server.actionmanager.HostRoleCommandFactory;
import org.apache.ambari.server.controller.AmbariManagementController;
import org.apache.ambari.server.controller.spi.NoSuchParentResourceException;
import org.apache.ambari.server.controller.spi.NoSuchResourceException;
import org.apache.ambari.server.controller.spi.Predicate;
import org.apache.ambari.server.controller.spi.Request;
import org.apache.ambari.server.controller.spi.RequestStatus;
import org.apache.ambari.server.controller.spi.Resource;
import org.apache.ambari.server.controller.spi.SystemException;
import org.apache.ambari.server.controller.spi.UnsupportedPropertyException;
import org.apache.ambari.server.controller.utilities.PropertyHelper;
import org.apache.ambari.server.orm.dao.HostRoleCommandDAO;
import org.apache.ambari.server.orm.entities.HostRoleCommandEntity;
import org.codehaus.jackson.map.ObjectMapper;

import com.google.inject.Inject;

/**
 * Resource provider for task resources.
 */
@StaticallyInject
public class TaskResourceProvider extends AbstractControllerResourceProvider {

  // ----- Property ID constants ---------------------------------------------

  // Tasks
  public static final String TASK_CLUSTER_NAME_PROPERTY_ID = PropertyHelper.getPropertyId("Tasks", "cluster_name");
  public static final String TASK_REQUEST_ID_PROPERTY_ID   = PropertyHelper.getPropertyId("Tasks", "request_id");
  public static final String TASK_ID_PROPERTY_ID           = PropertyHelper.getPropertyId("Tasks", "id");
  public static final String TASK_STAGE_ID_PROPERTY_ID     = PropertyHelper.getPropertyId("Tasks", "stage_id");
  public static final String TASK_HOST_NAME_PROPERTY_ID    = PropertyHelper.getPropertyId("Tasks", "host_name");
  public static final String TASK_ROLE_PROPERTY_ID         = PropertyHelper.getPropertyId("Tasks", "role");
  public static final String TASK_COMMAND_PROPERTY_ID      = PropertyHelper.getPropertyId("Tasks", "command");
  public static final String TASK_STATUS_PROPERTY_ID       = PropertyHelper.getPropertyId("Tasks", "status");
  public static final String TASK_EXIT_CODE_PROPERTY_ID    = PropertyHelper.getPropertyId("Tasks", "exit_code");
  public static final String TASK_STDERR_PROPERTY_ID       = PropertyHelper.getPropertyId("Tasks", "stderr");
  public static final String TASK_STOUT_PROPERTY_ID        = PropertyHelper.getPropertyId("Tasks", "stdout");
  public static final String TASK_OUTPUTLOG_PROPERTY_ID    = PropertyHelper.getPropertyId("Tasks", "output_log");
  public static final String TASK_ERRORLOG_PROPERTY_ID     = PropertyHelper.getPropertyId("Tasks", "error_log");
  public static final String TASK_STRUCT_OUT_PROPERTY_ID   = PropertyHelper.getPropertyId("Tasks", "structured_out");
  public static final String TASK_START_TIME_PROPERTY_ID   = PropertyHelper.getPropertyId("Tasks", "start_time");
  public static final String TASK_END_TIME_PROPERTY_ID     = PropertyHelper.getPropertyId("Tasks", "end_time");
  public static final String TASK_ATTEMPT_CNT_PROPERTY_ID  = PropertyHelper.getPropertyId("Tasks", "attempt_cnt");
  public static final String TASK_COMMAND_DET_PROPERTY_ID  = PropertyHelper.getPropertyId("Tasks", "command_detail");
  public static final String TASK_CUST_CMD_NAME_PROPERTY_ID  = PropertyHelper.getPropertyId("Tasks", "custom_command_name");

  private static Set<String> pkPropertyIds =
      new HashSet<String>(Arrays.asList(new String[]{
          TASK_ID_PROPERTY_ID}));

  /**
   * Used for querying tasks.
   */
  @Inject
  protected static HostRoleCommandDAO s_dao;

  /**
   * Used for constructing instances of {@link HostRoleCommand} from {@link HostRoleCommandEntity}.
   */
  @Inject
  private static HostRoleCommandFactory s_hostRoleCommandFactory;

  /**
   * Thread-safe Jackson JSON mapper.
   */
  private static final ObjectMapper mapper = new ObjectMapper();

  // ----- Constructors ----------------------------------------------------

  /**
   * Create a  new resource provider for the given management controller.
   *
   * @param propertyIds           the property ids
   * @param keyPropertyIds        the key property ids
   * @param managementController  the management controller
   */
  TaskResourceProvider(Set<String> propertyIds,
                       Map<Resource.Type, String> keyPropertyIds,
                       AmbariManagementController managementController) {
    super(propertyIds, keyPropertyIds, managementController);
  }

  // ----- ResourceProvider ------------------------------------------------

  @Override
  public RequestStatus createResources(Request request) {
    throw new UnsupportedOperationException("Not currently supported.");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Set<Resource> getResources(Request request, Predicate predicate)
      throws SystemException, UnsupportedPropertyException, NoSuchResourceException, NoSuchParentResourceException {

    Set<Resource> results = new LinkedHashSet<Resource>();
    Set<String> requestedIds = getRequestPropertyIds(request, predicate);

    List<HostRoleCommandEntity> entities = s_dao.findAll(request, predicate);
    LOG.debug("Retrieved {} commands for request {}", entities.size(), request);

    // !!! getting the cluster name out of the request property maps is a little
    // hacky since there could be a different request per cluster name; however
    // nobody is making task requests across clusters. Overall, this loop could
    // be called multiple times when using predicates like
    // tasks/Tasks/status.in(FAILED,ABORTED,TIMEDOUT) which would unnecessarily
    // make the same call to the API over and over
    String clusterName = null;
    for (Map<String, Object> propertyMap : getPropertyMaps(predicate)) {
      clusterName = (String) propertyMap.get(TASK_CLUSTER_NAME_PROPERTY_ID);
    }

    // convert each entity into a response
    for (HostRoleCommandEntity entity : entities) {
      Resource resource = new ResourceImpl(Resource.Type.Task);

      // !!! shocked this isn't broken.  the key can be null for non-cluster tasks
      if (null != clusterName) {
        setResourceProperty(resource, TASK_CLUSTER_NAME_PROPERTY_ID, clusterName, requestedIds);
      }

      HostRoleCommand hostRoleCommand = s_hostRoleCommandFactory.createExisting(entity);

      setResourceProperty(resource, TASK_REQUEST_ID_PROPERTY_ID, hostRoleCommand.getRequestId(), requestedIds);
      setResourceProperty(resource, TASK_ID_PROPERTY_ID, hostRoleCommand.getTaskId(), requestedIds);
      setResourceProperty(resource, TASK_STAGE_ID_PROPERTY_ID, hostRoleCommand.getStageId(), requestedIds);
      setResourceProperty(resource, TASK_HOST_NAME_PROPERTY_ID, hostRoleCommand.getHostName(), requestedIds);
      setResourceProperty(resource, TASK_ROLE_PROPERTY_ID, hostRoleCommand.getRole().toString(), requestedIds);
      setResourceProperty(resource, TASK_COMMAND_PROPERTY_ID, hostRoleCommand.getRoleCommand(), requestedIds);
      setResourceProperty(resource, TASK_STATUS_PROPERTY_ID, hostRoleCommand.getStatus(), requestedIds);
      setResourceProperty(resource, TASK_EXIT_CODE_PROPERTY_ID, hostRoleCommand.getExitCode(), requestedIds);
      setResourceProperty(resource, TASK_STDERR_PROPERTY_ID, hostRoleCommand.getStderr(), requestedIds);
      setResourceProperty(resource, TASK_STOUT_PROPERTY_ID, hostRoleCommand.getStdout(), requestedIds);
      setResourceProperty(resource, TASK_OUTPUTLOG_PROPERTY_ID, hostRoleCommand.getOutputLog(), requestedIds);
      setResourceProperty(resource, TASK_ERRORLOG_PROPERTY_ID, hostRoleCommand.getErrorLog(), requestedIds);
      setResourceProperty(resource, TASK_STRUCT_OUT_PROPERTY_ID, parseStructuredOutput(hostRoleCommand.getStructuredOut()), requestedIds);
      setResourceProperty(resource, TASK_START_TIME_PROPERTY_ID, hostRoleCommand.getStartTime(), requestedIds);
      setResourceProperty(resource, TASK_END_TIME_PROPERTY_ID, hostRoleCommand.getEndTime(), requestedIds);
      setResourceProperty(resource, TASK_ATTEMPT_CNT_PROPERTY_ID, hostRoleCommand.getAttemptCount(), requestedIds);

      if (hostRoleCommand.getCustomCommandName() != null) {
        setResourceProperty(resource, TASK_CUST_CMD_NAME_PROPERTY_ID, hostRoleCommand.getCustomCommandName(), requestedIds);
      }

      if (hostRoleCommand.getCommandDetail() == null) {
        setResourceProperty(resource, TASK_COMMAND_DET_PROPERTY_ID,
            String.format("%s %s", hostRoleCommand.getRole().toString(), hostRoleCommand.getRoleCommand()), requestedIds);
      } else {
        setResourceProperty(resource, TASK_COMMAND_DET_PROPERTY_ID, hostRoleCommand.getCommandDetail(), requestedIds);
      }

      results.add(resource);
    }

    return results;
  }

  /**
   * Converts the specified JSON string into a {@link Map}. For now, use Jackson
   * instead of gson since none of the integers will convert properly without a
   * well-defined first-class object to map to.
   *
   * @param structuredOutput
   *          the JSON string to convert.
   * @return the converted JSON as key-value pairs, or {@code null} if an
   *         exception was encountered or if the JSON string was empty.
   */
  Map<?, ?> parseStructuredOutput(String structuredOutput) {
    if (null == structuredOutput || structuredOutput.isEmpty()) {
      return null;
    }

    Map<?, ?> result = null;

    try {
      result = mapper.readValue(structuredOutput, Map.class);
    } catch (Exception excepton) {
      LOG.warn("Unable to parse task structured output: {}", structuredOutput);
    }
    return result;
  }

  @Override
  public RequestStatus updateResources(Request request, Predicate predicate)
      throws SystemException, UnsupportedPropertyException, NoSuchResourceException, NoSuchParentResourceException {
    throw new UnsupportedOperationException("Not currently supported.");
  }

  @Override
  public RequestStatus deleteResources(Predicate predicate)
      throws SystemException, UnsupportedPropertyException, NoSuchResourceException, NoSuchParentResourceException {
    throw new UnsupportedOperationException("Not currently supported.");
  }

  // ----- utility methods -------------------------------------------------

  @Override
  protected Set<String> getPKPropertyIds() {
    return pkPropertyIds;
  }
}
