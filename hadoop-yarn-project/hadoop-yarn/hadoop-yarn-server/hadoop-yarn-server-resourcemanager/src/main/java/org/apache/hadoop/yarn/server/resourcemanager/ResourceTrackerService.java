/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.hadoop.yarn.server.resourcemanager;

import com.google.common.annotations.VisibleForTesting;
import io.hops.ha.common.TransactionState;
import io.hops.ha.common.TransactionState.TransactionType;
import io.hops.ha.common.TransactionStateImpl;
import io.hops.metadata.util.HopYarnAPIUtilities;
import io.hops.metadata.util.RMUtilities;
import io.hops.metadata.yarn.entity.appmasterrpc.RPC;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CommonConfigurationKeysPublic;
import org.apache.hadoop.ipc.Server;
import org.apache.hadoop.net.Node;
import org.apache.hadoop.security.authorize.PolicyProvider;
import org.apache.hadoop.service.AbstractService;
import org.apache.hadoop.util.VersionUtil;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.ContainerState;
import org.apache.hadoop.yarn.api.records.ContainerStatus;
import org.apache.hadoop.yarn.api.records.NodeId;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.ResourceOption;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.exceptions.YarnRuntimeException;
import org.apache.hadoop.yarn.factories.RecordFactory;
import org.apache.hadoop.yarn.factory.providers.RecordFactoryProvider;
import org.apache.hadoop.yarn.ipc.YarnRPC;
import org.apache.hadoop.yarn.server.api.ResourceTracker;
import org.apache.hadoop.yarn.server.api.protocolrecords.NodeHeartbeatRequest;
import org.apache.hadoop.yarn.server.api.protocolrecords.NodeHeartbeatResponse;
import org.apache.hadoop.yarn.server.api.protocolrecords.RegisterNodeManagerRequest;
import org.apache.hadoop.yarn.server.api.protocolrecords.RegisterNodeManagerResponse;
import org.apache.hadoop.yarn.server.api.protocolrecords.impl.pb.NodeHeartbeatRequestPBImpl;
import org.apache.hadoop.yarn.server.api.protocolrecords.impl.pb.RegisterNodeManagerRequestPBImpl;
import org.apache.hadoop.yarn.server.api.records.MasterKey;
import org.apache.hadoop.yarn.server.api.records.NodeAction;
import org.apache.hadoop.yarn.server.api.records.NodeStatus;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMApp;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.RMAppAttempt;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.event.RMAppAttemptContainerFinishedEvent;
import org.apache.hadoop.yarn.server.resourcemanager.rmnode.RMNode;
import org.apache.hadoop.yarn.server.resourcemanager.rmnode.RMNodeEvent;
import org.apache.hadoop.yarn.server.resourcemanager.rmnode.RMNodeEventType;
import org.apache.hadoop.yarn.server.resourcemanager.rmnode.RMNodeImpl;
import org.apache.hadoop.yarn.server.resourcemanager.rmnode.RMNodeReconnectEvent;
import org.apache.hadoop.yarn.server.resourcemanager.rmnode.RMNodeStatusEvent;
import org.apache.hadoop.yarn.server.resourcemanager.security.NMTokenSecretManagerInRM;
import org.apache.hadoop.yarn.server.resourcemanager.security.RMContainerTokenSecretManager;
import org.apache.hadoop.yarn.server.resourcemanager.security.authorize.RMPolicyProvider;
import org.apache.hadoop.yarn.server.utils.YarnServerBuilderUtils;
import org.apache.hadoop.yarn.util.RackResolver;
import org.apache.hadoop.yarn.util.YarnVersionInfo;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.Map;

public class ResourceTrackerService extends AbstractService
    implements ResourceTracker {

  private static final Log LOG =
      LogFactory.getLog(ResourceTrackerService.class);
  private static final RecordFactory recordFactory =
      RecordFactoryProvider.getRecordFactory(null);
  private final RMContext rmContext;
  private final NodesListManager nodesListManager;
  private final NMLivelinessMonitor nmLivelinessMonitor;
  private final RMContainerTokenSecretManager containerTokenSecretManager;
  private final NMTokenSecretManagerInRM nmTokenSecretManager;
  private long nextHeartBeatInterval;
  private Server server;
  private InetSocketAddress resourceTrackerAddress;
  private String minimumNodeManagerVersion;
  private static final NodeHeartbeatResponse resync =
      recordFactory.newRecordInstance(NodeHeartbeatResponse.class);
  private static final NodeHeartbeatResponse shutDown =
      recordFactory.newRecordInstance(NodeHeartbeatResponse.class);
  private int minAllocMb;
  private int minAllocVcores;

  Configuration conf;
  private int load = 0;

  static {
    resync.setNodeAction(NodeAction.RESYNC);

    shutDown.setNodeAction(NodeAction.SHUTDOWN);
  }

  public ResourceTrackerService(RMContext rmContext,
      NodesListManager nodesListManager,
      NMLivelinessMonitor nmLivelinessMonitor,
      RMContainerTokenSecretManager containerTokenSecretManager,
      NMTokenSecretManagerInRM nmTokenSecretManager) {
    super(ResourceTrackerService.class.getName());
    this.rmContext = rmContext;
    this.nodesListManager = nodesListManager;
    this.nmLivelinessMonitor = nmLivelinessMonitor;
    this.containerTokenSecretManager = containerTokenSecretManager;
    this.nmTokenSecretManager = nmTokenSecretManager;

  }

  @Override
  protected void serviceInit(Configuration conf) throws Exception {
    this.conf = conf;
    resourceTrackerAddress =
        conf.getSocketAddr(YarnConfiguration.RM_RESOURCE_TRACKER_ADDRESS,
            YarnConfiguration.DEFAULT_RM_RESOURCE_TRACKER_ADDRESS,
            YarnConfiguration.DEFAULT_RM_RESOURCE_TRACKER_PORT);

    RackResolver.init(conf);
    nextHeartBeatInterval =
        conf.getLong(YarnConfiguration.RM_NM_HEARTBEAT_INTERVAL_MS,
            YarnConfiguration.DEFAULT_RM_NM_HEARTBEAT_INTERVAL_MS);
    if (nextHeartBeatInterval <= 0) {
      throw new YarnRuntimeException("Invalid Configuration. " +
          YarnConfiguration.RM_NM_HEARTBEAT_INTERVAL_MS +
          " should be larger than 0.");
    }

    minAllocMb =
        conf.getInt(YarnConfiguration.RM_SCHEDULER_MINIMUM_ALLOCATION_MB,
            YarnConfiguration.DEFAULT_RM_SCHEDULER_MINIMUM_ALLOCATION_MB);
    minAllocVcores =
        conf.getInt(YarnConfiguration.RM_SCHEDULER_MINIMUM_ALLOCATION_VCORES,
            YarnConfiguration.DEFAULT_RM_SCHEDULER_MINIMUM_ALLOCATION_VCORES);

    minimumNodeManagerVersion =
        conf.get(YarnConfiguration.RM_NODEMANAGER_MINIMUM_VERSION,
            YarnConfiguration.DEFAULT_RM_NODEMANAGER_MINIMUM_VERSION);

    super.serviceInit(conf);
  }

  @Override
  protected void serviceStart() throws Exception {
    super.serviceStart();
    // ResourceTrackerServer authenticates NodeManager via Kerberos if
    // security is enabled, so no secretManager.
    Configuration conf = getConfig();
    YarnRPC rpc = YarnRPC.create(conf);
    LOG.debug(
        "starting ResourceTrackerService server on " + resourceTrackerAddress);
    this.server =
        rpc.getServer(ResourceTracker.class, this, resourceTrackerAddress, conf,
            null, conf.getInt(
                YarnConfiguration.RM_RESOURCE_TRACKER_CLIENT_THREAD_COUNT,
                YarnConfiguration.DEFAULT_RM_RESOURCE_TRACKER_CLIENT_THREAD_COUNT));

    // Enable service authorization?
    if (conf
        .getBoolean(CommonConfigurationKeysPublic.HADOOP_SECURITY_AUTHORIZATION,
            false)) {
      InputStream inputStream = this.rmContext.getConfigurationProvider()
          .getConfigurationInputStream(conf,
              YarnConfiguration.HADOOP_POLICY_CONFIGURATION_FILE);
      if (inputStream != null) {
        conf.addResource(inputStream);
      }
      refreshServiceAcls(conf, RMPolicyProvider.getInstance());
    }

    this.server.start();
    conf.updateConnectAddr(YarnConfiguration.RM_RESOURCE_TRACKER_ADDRESS,
        server.getListenerAddress());
  }

  @Override
  protected void serviceStop() throws Exception {
    if (this.server != null) {
      this.server.stop();
    }
    super.serviceStop();
  }

  /**
   * Helper method to handle received ContainerStatus. If this corresponds to
   * the completion of a master-container of a managed AM, we call the handler
   * for RMAppAttemptContainerFinishedEvent.
   */
  @SuppressWarnings("unchecked")
  @VisibleForTesting
  void handleContainerStatus(ContainerStatus containerStatus,
      TransactionState transactionState) {
    LOG.debug("HOP :: handleContainerStatus");
    ApplicationAttemptId appAttemptId =
        containerStatus.getContainerId().getApplicationAttemptId();
    RMApp rmApp = null;
    try {
      rmApp = RMUtilities.getRMApp(this.rmContext, this.conf, appAttemptId.
          getApplicationId()
          .toString());
    } catch (IOException ex) {
      LOG.error("HOP :: Error retrieving RMApp", ex);
    }
    if (rmApp == null) {
      LOG.error(
          "Received finished container : " + containerStatus.getContainerId() +
              "for unknown application " + appAttemptId.getApplicationId() +
              " Skipping.");
      return;
    }
    LOG.debug("handleContainerStatus " + rmApp.getApplicationId());
    if (rmApp.getApplicationSubmissionContext().getUnmanagedAM()) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Ignoring container completion status for unmanaged AM" +
            rmApp.getApplicationId());
      }
      return;
    }


    RMAppAttempt rmAppAttempt = rmApp.getRMAppAttempt(appAttemptId);
    Container masterContainer = rmAppAttempt.getMasterContainer();
    if (masterContainer.getId().equals(containerStatus.getContainerId()) &&
        containerStatus.getState() == ContainerState.COMPLETE) {
      LOG.debug("sending master container finished event " + rmApp.
          getApplicationId());
      //TODO: Persist event if I am not leader
      // sending master container finished event.
      RMAppAttemptContainerFinishedEvent evt =
          new RMAppAttemptContainerFinishedEvent(appAttemptId, containerStatus,
              transactionState);
      rmContext.getDispatcher().getEventHandler().handle(evt);
    }
  }


  @SuppressWarnings("unchecked")
  @Override
  public RegisterNodeManagerResponse registerNodeManager(
      RegisterNodeManagerRequest request) throws YarnException, IOException {
    return registerNodeManager(request, null,
        conf.getBoolean(YarnConfiguration.HOPS_DISTRIBUTED_RT_ENABLED,
            YarnConfiguration.DEFAULT_HOPS_DISTRIBUTED_RT_ENABLED));
  }

  public RegisterNodeManagerResponse registerNodeManager(
      RegisterNodeManagerRequest request, Integer rpcID,
      boolean isDistributedRTEnabled) throws YarnException, IOException {
    RegisterNodeManagerResponse response =
        recordFactory.newRecordInstance(RegisterNodeManagerResponse.class);
    NodeId nodeId = request.getNodeId();
    String host = nodeId.getHost();
    int cmPort = nodeId.getPort();
    int httpPort = request.getHttpPort();
    Resource capability = request.getResource();
    String nodeManagerVersion = request.getNMVersion();
    TransactionState transactionState;
    
    boolean oldNodeExists = false;
    if (rpcID == null) {
      if (isDistributedRTEnabled) {
        byte[] allNMRequestData = ((RegisterNodeManagerRequestPBImpl) request).
            getProto().toByteArray();
        //1. Get ActiveRMNodes from NDB. If registering Node is not in ActiveRMNodes
        //   put it in the map (later in this method).
        Map<Integer, Object> data = RMUtilities
            .registerNMRPCValidation(RPC.Type.RegisterNM, allNMRequestData,
                request.getNodeId().toString());
        if (data != null) {
          rpcID = (Integer) data.get(0);
          oldNodeExists = (Boolean) data.get(1);
        } else {
          //2. If RPC already exists, return error message or drop registration request
          //TODO: Set up response
          return null;
        }
      } else {
        rpcID = HopYarnAPIUtilities.setYarnVariables(HopYarnAPIUtilities.RPC);
        byte[] allNMRequestData = ((RegisterNodeManagerRequestPBImpl) request).
            getProto().toByteArray();
        RMUtilities
            .persistAppMasterRPC(rpcID, RPC.Type.RegisterNM, allNMRequestData);
      }
    }
    transactionState = new TransactionStateImpl(rpcID, TransactionType.RM);

    if (!request.getContainerStatuses().isEmpty()) {
      LOG.info("received container statuses on node manager register :" +
          request.getContainerStatuses());
      for (ContainerStatus containerStatus : request.getContainerStatuses()) {
        handleContainerStatus(containerStatus, transactionState);
      }
    }
    if (!minimumNodeManagerVersion.equals("NONE")) {
      if (minimumNodeManagerVersion.equals("EqualToRM")) {
        minimumNodeManagerVersion = YarnVersionInfo.getVersion();
      }

      if ((nodeManagerVersion == null) || (VersionUtil
          .compareVersions(nodeManagerVersion, minimumNodeManagerVersion)) <
          0) {
        String message =
            "Disallowed NodeManager Version " + nodeManagerVersion +
                ", is less than the minimum version " +
                minimumNodeManagerVersion + " sending SHUTDOWN signal to " +
                "NodeManager.";
        LOG.info(message);
        response.setDiagnosticsMessage(message);
        response.setNodeAction(NodeAction.SHUTDOWN);
        
        transactionState.decCounter("RT");
        return response;
      }
    }

    // Check if this node is a 'valid' node
    if (!this.nodesListManager.isValidNode(host)) {
      String message = "Disallowed NodeManager from  " + host +
          ", Sending SHUTDOWN signal to the NodeManager.";
      LOG.info(message);
      response.setDiagnosticsMessage(message);
      response.setNodeAction(NodeAction.SHUTDOWN);
      
      transactionState.decCounter("RT");
      return response;
    }


    // Check if this node has minimum allocations
    if (capability.getMemory() < minAllocMb ||
        capability.getVirtualCores() < minAllocVcores) {
      String message = "NodeManager from  " + host +
          " doesn't satisfy minimum allocations, Sending SHUTDOWN" +
          " signal to the NodeManager.";
      LOG.info(message);
      response.setDiagnosticsMessage(message);
      response.setNodeAction(NodeAction.SHUTDOWN);
      
      transactionState.decCounter("RT");
      return response;
    }

    response.setContainerTokenMasterKey(
        containerTokenSecretManager.getCurrentKey());
    response.setNMTokenMasterKey(nmTokenSecretManager.getCurrentKey());

    RMNode rmNode =
        new RMNodeImpl(nodeId, rmContext, host, cmPort, httpPort, resolve(host),
            ResourceOption.newInstance(capability,
                RMNode.OVER_COMMIT_TIMEOUT_MILLIS_DEFAULT), nodeManagerVersion,
            conf.getBoolean(YarnConfiguration.HOPS_DISTRIBUTED_RT_ENABLED,
                YarnConfiguration.DEFAULT_HOPS_DISTRIBUTED_RT_ENABLED));

    if (isDistributedRTEnabled) {
      if (!oldNodeExists) {
        LOG.info("HOP :: Registering new node at: " + host);
        this.rmContext.getActiveRMNodes().put(nodeId, rmNode);
        ((TransactionStateImpl) transactionState).getRMContextInfo().
            toAddActiveRMNode(nodeId, rmNode);
        ((TransactionStateImpl) transactionState)
            .getRMNodeInfo(nodeId.toString())
            .toAddNextHeartbeat(nodeId.toString(),
                ((RMNodeImpl) rmNode).getNextHeartbeat());
        this.rmContext.getDispatcher().getEventHandler().handle(
            new RMNodeEvent(nodeId, RMNodeEventType.STARTED, transactionState));
      } else {
        LOG.info("Reconnect from the node at: " + host);
        this.nmLivelinessMonitor.unregister(nodeId);
        load--;
        ((TransactionStateImpl) transactionState).getRMContextInfo().
            updateLoad(rmContext.getRMGroupMembershipService().
                getHostname(), load);
        this.rmContext.getDispatcher().getEventHandler()
            .handle(new RMNodeReconnectEvent(nodeId, rmNode, transactionState));
      }
    } else {
      RMNode oldNode =
          this.rmContext.getActiveRMNodes().putIfAbsent(nodeId, rmNode);
      if (oldNode == null) {
        LOG.info("HOP :: Registering new node at: " + host);
        ((TransactionStateImpl) transactionState).getRMContextInfo().
            toAddActiveRMNode(nodeId, rmNode);
        ((TransactionStateImpl) transactionState)
            .getRMNodeInfo(nodeId.toString())
            .toAddNextHeartbeat(nodeId.toString(),
                ((RMNodeImpl) rmNode).getNextHeartbeat());
        this.rmContext.getDispatcher().getEventHandler().handle(
            new RMNodeEvent(nodeId, RMNodeEventType.STARTED, transactionState));
      } else {
        LOG.info("Reconnect from the node at: " + host);
        this.nmLivelinessMonitor.unregister(nodeId);
        load--;
        ((TransactionStateImpl) transactionState).getRMContextInfo().
            updateLoad(rmContext.getRMGroupMembershipService().
                getHostname(), load);
        this.rmContext.getDispatcher().getEventHandler()
            .handle(new RMNodeReconnectEvent(nodeId, rmNode, transactionState));
      }
    }
    
    // On every node manager register we will be clearing NMToken keys if
    // present for any running application.
    this.nmTokenSecretManager.removeNodeKey(nodeId);
    this.nmLivelinessMonitor.register(nodeId);
    load++;
    ((TransactionStateImpl) transactionState).getRMContextInfo()
        .updateLoad(rmContext.getRMGroupMembershipService().getHostname(),
            load);
    String message =
        "NodeManager from node " + host + "(cmPort: " + cmPort + " httpPort: " +
            httpPort + ") " + "registered with capability: " + capability +
            ", assigned nodeId " + nodeId;
    LOG.info(message);
    response.setNodeAction(NodeAction.NORMAL);
    response.setRMIdentifier(ResourceManager.getClusterTimeStamp());
    response.setRMVersion(YarnVersionInfo.getVersion());

    
    transactionState.decCounter("RT");

    //Gautier: we may send a response without commiting first. Is it a problem ?
    return response;
  }

  @SuppressWarnings("unchecked")
  @Override
  public NodeHeartbeatResponse nodeHeartbeat(NodeHeartbeatRequest request)
      throws YarnException, IOException {
    return nodeHeartbeat(request, null);
  }

  public NodeHeartbeatResponse nodeHeartbeat(NodeHeartbeatRequest request,
      Integer rpcID) throws YarnException, IOException {
    //TODO HOPS: If the RMNode is unknown, fetch from NDB first
    
    NodeStatus remoteNodeStatus = request.getNodeStatus();
    NodeId nodeId = remoteNodeStatus.getNodeId();
    
    LOG.debug("HOP :: receive heartbeat node " + nodeId);

    if (rpcID == null) {
      rpcID = HopYarnAPIUtilities.getRPCID();
      byte[] allHBRequestData = ((NodeHeartbeatRequestPBImpl) request).
          getProto().toByteArray();
      RMUtilities
          .persistAppMasterRPC(rpcID, RPC.Type.NodeHeartbeat, allHBRequestData);
    }
    TransactionState transactionState =
        new TransactionStateImpl(rpcID, TransactionType.RM, "heartbeat",
            nodeId);

    
    /**
     * Here is the node heartbeat sequence... 1. Check if it's a registered
     * node 2. Check if it's a valid (i.e. not excluded) node 3. Check if
     * it's a 'fresh' heartbeat i.e. not duplicate heartbeat 4. Send
     * healthStatus to RMNode
     */
    RMNode rmNode = this.rmContext.getActiveRMNodes().get(nodeId);
    // 1. Check if it's a registered node
    if (rmNode == null) {
      /*
       * node does not exist
       */
      String message = "Node not found resyncing " + remoteNodeStatus.
          getNodeId();
      LOG.info(message);
      resync.setDiagnosticsMessage(message);
      
      transactionState.decCounter("RT");
      return resync;
    }
    
    // Send ping
    this.nmLivelinessMonitor.receivedPing(nodeId);

    // 2. Check if it's a valid (i.e. not excluded) node
    if (!this.nodesListManager.isValidNode(rmNode.getHostName())) {
      String message =
          "Disallowed NodeManager nodeId: " + nodeId + " hostname: " +
              rmNode.getNodeAddress();
      LOG.info(message);
      shutDown.setDiagnosticsMessage(message);
      this.rmContext.getDispatcher().getEventHandler().handle(
          new RMNodeEvent(nodeId, RMNodeEventType.DECOMMISSION,
              transactionState));
      
      transactionState.decCounter("RT");
      return shutDown;
    }

    // 3. Check if it's a 'fresh' heartbeat i.e. not duplicate heartbeat
    NodeHeartbeatResponse lastNodeHeartbeatResponse = rmNode.
        getLastNodeHeartBeatResponse();
    if (remoteNodeStatus.getResponseId() + 1 ==
        lastNodeHeartbeatResponse.getResponseId()) {
      LOG.info(
          "Received duplicate heartbeat from node " + rmNode.getNodeAddress());
      
      transactionState.decCounter("RT");
      return lastNodeHeartbeatResponse;
    } else if (remoteNodeStatus.getResponseId() + 1 <
        lastNodeHeartbeatResponse.getResponseId()) {
      String message = "Too far behind rm response id:" +
          lastNodeHeartbeatResponse.getResponseId() + " nm response id:" +
          remoteNodeStatus.getResponseId();
      LOG.info(message);
      resync.setDiagnosticsMessage(message);
      // TODO: Just sending reboot is not enough. Think more.
      this.rmContext.getDispatcher().getEventHandler().handle(
          new RMNodeEvent(nodeId, RMNodeEventType.REBOOTING, transactionState));
      
      transactionState.decCounter("RT");
      return resync;
    }

    // Heartbeat response
    NodeHeartbeatResponse nodeHeartBeatResponse = YarnServerBuilderUtils
        .newNodeHeartbeatResponse(lastNodeHeartbeatResponse.
                getResponseId() + 1, NodeAction.NORMAL, null, null, null, null,
            nextHeartBeatInterval);

    rmNode.updateNodeHeartbeatResponseForCleanup(nodeHeartBeatResponse,
        transactionState);

    populateKeys(request, nodeHeartBeatResponse);
    LOG.debug("HOP :: remoteNodeStatus.getContainersStatuses()" +
        remoteNodeStatus.getContainersStatuses());
    // 4. Send status to RMNode, saving the latest response.
    this.rmContext.getDispatcher().getEventHandler().handle(
        new RMNodeStatusEvent(nodeId, remoteNodeStatus.getNodeHealthStatus(),
            remoteNodeStatus.getContainersStatuses(),
            remoteNodeStatus.getKeepAliveApplications(), nodeHeartBeatResponse,
            transactionState));
    
    transactionState.decCounter("RT");
    return nodeHeartBeatResponse;
  }

  private void populateKeys(NodeHeartbeatRequest request,
      NodeHeartbeatResponse nodeHeartBeatResponse) {
    LOG.debug("HOP :: heartbeat populateKeys check 1");
    // Check if node's masterKey needs to be updated and if the currentKey has
    // roller over, send it across
    // ContainerTokenMasterKey
    MasterKey nextMasterKeyForNode =
        this.containerTokenSecretManager.getNextKey();
    LOG.debug("HOP :: heartbeat populateKeys check 2:" + nextMasterKeyForNode);
    if (nextMasterKeyForNode != null &&
        (request.getLastKnownContainerTokenMasterKey().getKeyId() !=
            nextMasterKeyForNode.getKeyId())) {
      LOG.debug("HOP :: heartbeat populateKeys check 3");
      nodeHeartBeatResponse.setContainerTokenMasterKey(nextMasterKeyForNode);
    }

    // NMTokenMasterKey
    nextMasterKeyForNode = this.nmTokenSecretManager.getNextKey();
    if (nextMasterKeyForNode != null &&
        (request.getLastKnownNMTokenMasterKey().getKeyId() !=
            nextMasterKeyForNode.getKeyId())) {
      nodeHeartBeatResponse.setNMTokenMasterKey(nextMasterKeyForNode);
    }
  }

  /**
   * resolving the network topology.
   *
   * @param hostName
   *     the hostname of this node.
   * @return the resolved {@link Node} for this nodemanager.
   */
  public static Node resolve(String hostName) {
    return RackResolver.resolve(hostName);
  }

  void refreshServiceAcls(Configuration configuration,
      PolicyProvider policyProvider) {
    this.server.refreshServiceAclWithLoadedConfiguration(configuration,
        policyProvider);
  }

  @VisibleForTesting
  public Server getServer() {
    return this.server;
  }

  public NMLivelinessMonitor getNmLivelinessMonitor() {
    return nmLivelinessMonitor;
  }

}
