/*
 * JBoss, Home of Professional Open Source
 * Copyright 2012 Red Hat Inc. and/or its affiliates and other
 * contributors as indicated by the @author tags. All rights reserved.
 * See the copyright.txt in the distribution for a full listing of
 * individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.infinispan.statetransfer;

import org.infinispan.Cache;
import org.infinispan.commands.CommandsFactory;
import org.infinispan.commands.write.WriteCommand;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.container.DataContainer;
import org.infinispan.distribution.ch.ConsistentHash;
import org.infinispan.factories.annotations.ComponentName;
import org.infinispan.factories.annotations.Inject;
import org.infinispan.factories.annotations.Start;
import org.infinispan.factories.annotations.Stop;
import org.infinispan.loaders.CacheLoaderManager;
import org.infinispan.notifications.Listener;
import org.infinispan.notifications.cachelistener.CacheNotifier;
import org.infinispan.notifications.cachelistener.annotation.TopologyChanged;
import org.infinispan.notifications.cachelistener.event.TopologyChangedEvent;
import org.infinispan.remoting.rpc.RpcManager;
import org.infinispan.remoting.transport.Address;
import org.infinispan.topology.CacheTopology;
import org.infinispan.transaction.TransactionTable;
import org.infinispan.transaction.xa.CacheTransaction;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

import java.util.*;
import java.util.concurrent.ExecutorService;

import static org.infinispan.factories.KnownComponentNames.ASYNC_TRANSPORT_EXECUTOR;

/**
 * {@link StateProvider} implementation.
 *
 * @author anistor@redhat.com
 * @since 5.2
 */
@Listener
public class StateProviderImpl implements StateProvider {

   private static final Log log = LogFactory.getLog(StateProviderImpl.class);
   private static final boolean trace = log.isTraceEnabled();

   private String cacheName;
   private Configuration configuration;
   private RpcManager rpcManager;
   private CommandsFactory commandsFactory;
   private CacheNotifier cacheNotifier;
   private TransactionTable transactionTable;     // optional
   private DataContainer dataContainer;
   private CacheLoaderManager cacheLoaderManager; // optional
   private ExecutorService executorService;
   private StateTransferLock stateTransferLock;
   private long timeout;
   private int chunkSize;

   private StateConsumer stateConsumer;

   /**
    * A map that keeps track of current outbound state transfers by destination address. There could be multiple transfers
    * flowing to the same destination (but for different segments) so the values are lists.
    */
   private final Map<Address, List<OutboundTransferTask>> transfersByDestination = new HashMap<Address, List<OutboundTransferTask>>();

   public StateProviderImpl() {
   }

   @Inject
   public void init(Cache cache,
                    @ComponentName(ASYNC_TRANSPORT_EXECUTOR) ExecutorService executorService, //TODO Use a dedicated ExecutorService
                    Configuration configuration,
                    RpcManager rpcManager,
                    CommandsFactory commandsFactory,
                    CacheNotifier cacheNotifier,
                    CacheLoaderManager cacheLoaderManager,
                    DataContainer dataContainer,
                    TransactionTable transactionTable,
                    StateTransferLock stateTransferLock,
                    StateConsumer stateConsumer) {
      this.cacheName = cache.getName();
      this.executorService = executorService;
      this.configuration = configuration;
      this.rpcManager = rpcManager;
      this.commandsFactory = commandsFactory;
      this.cacheNotifier = cacheNotifier;
      this.cacheLoaderManager = cacheLoaderManager;
      this.dataContainer = dataContainer;
      this.transactionTable = transactionTable;
      this.stateTransferLock = stateTransferLock;
      this.stateConsumer = stateConsumer;

      timeout = configuration.clustering().stateTransfer().timeout();

      // ignore chunk sizes <= 0
      int chunkSize = configuration.clustering().stateTransfer().chunkSize();
      this.chunkSize = chunkSize > 0 ? chunkSize : Integer.MAX_VALUE;
   }

   public boolean isStateTransferInProgress() {
      synchronized (transfersByDestination) {
         return !transfersByDestination.isEmpty();
      }
   }

   @TopologyChanged
   @SuppressWarnings("unused")
   public void onTopologyChange(TopologyChangedEvent<?, ?> tce) {
      // do all the work AFTER the consistent hash has changed
      if (tce.isPre())
         return;
      //todo [anistor] move all code from onTopologyUpdate here and remove dependency StateConsumer->StateProvider
   }

   public void onTopologyUpdate(CacheTopology cacheTopology, boolean isRebalance) {
      // cancel outbound state transfers for destinations that are no longer members in new topology
      Set<Address> members = new HashSet<Address>(cacheTopology.getWriteConsistentHash().getMembers());
      synchronized (transfersByDestination) {
         for (Iterator<Address> it = transfersByDestination.keySet().iterator(); it.hasNext(); ) {
            Address destination = it.next();
            if (!members.contains(destination)) {
               List<OutboundTransferTask> transfers = transfersByDestination.get(destination);
               it.remove();
               for (OutboundTransferTask outboundTransfer : transfers) {
                  outboundTransfer.cancel();
               }
            }
         }
      }

      //todo [anistor] must cancel transfers for all segments that we no longer own
   }

   @Start(priority = 60)
   @Override
   public void start() {
      cacheNotifier.addListener(this);
   }

   @Stop(priority = 20)
   @Override
   public void stop() {
      if (trace) {
         log.tracef("Shutting down StateProvider of cache %s on node %s", cacheName, rpcManager.getAddress());
      }
      // cancel all outbound transfers
      try {
         synchronized (transfersByDestination) {
            for (Iterator<List<OutboundTransferTask>> it = transfersByDestination.values().iterator(); it.hasNext(); ) {
               List<OutboundTransferTask> transfers = it.next();
               it.remove();
               for (OutboundTransferTask outboundTransfer : transfers) {
                  outboundTransfer.cancel();
               }
            }
         }
      } catch (Throwable t) {
         log.errorf(t, "Failed to stop StateProvider of cache %s on node %s", cacheName, rpcManager.getAddress());
      }
   }

   public List<TransactionInfo> getTransactionsForSegments(Address destination, int requestTopologyId, Set<Integer> segments) throws InterruptedException {
      if (trace) {
         log.tracef("Received request for transactions from node %s for segments %s of cache %s with topology id %d", destination, segments, cacheName, requestTopologyId);
      }

      final CacheTopology cacheTopology = stateConsumer.getCacheTopology();
      if (cacheTopology == null) {
         // no commands are processed until the join is complete so this cannot normally happen
         throw new IllegalStateException("No cache topology received yet");
      }
      final ConsistentHash readCh = cacheTopology.getReadConsistentHash();
      final int topologyId = cacheTopology.getTopologyId();

      if (requestTopologyId < topologyId) {
         log.warnf("Transactions were requested by node %s with topology %d, smaller than the local " +
               "topology (%d)", destination, requestTopologyId, topologyId);
      } else if (requestTopologyId > topologyId) {
         if (trace) {
            log.tracef("Transactions were requested by node %s with topology %d, greater than the local " +
                  "topology (%d). Waiting for topology %d to be installed locally.", destination,
                  requestTopologyId, topologyId, requestTopologyId);
         }
         stateTransferLock.waitForTopology(requestTopologyId);
      }

      Set<Integer> ownedSegments = readCh.getSegmentsForOwner(rpcManager.getAddress());
      if (!ownedSegments.containsAll(segments)) {
         segments.removeAll(ownedSegments);
         throw new IllegalArgumentException("Segments " + segments + " are not owned by " + rpcManager.getAddress());
      }

      List<TransactionInfo> transactions = new ArrayList<TransactionInfo>();
      //we migrate locks only if the cache is transactional and distributed
      if (configuration.transaction().transactionMode().isTransactional()) {
         collectTransactionsToTransfer(transactions, transactionTable.getRemoteTransactions(), segments, readCh);
         collectTransactionsToTransfer(transactions, transactionTable.getLocalTransactions(), segments, readCh);
         if (trace) {
            log.tracef("Found %d transaction(s) to transfer", transactions.size());
         }
      }
      return transactions;
   }

   private void collectTransactionsToTransfer(List<TransactionInfo> transactionsToTransfer,
                                              Collection<? extends CacheTransaction> transactions,
                                              Set<Integer> segments, ConsistentHash readCh) {
      for (CacheTransaction tx : transactions) {
         // transfer only locked keys that belong to requested segments, located on local node
         Set<Object> lockedKeys = new HashSet<Object>();
         for (Object key : tx.getLockedKeys()) {
            if (segments.contains(readCh.getSegment(key))) {
               lockedKeys.add(key);
            }
         }
         for (Object key : tx.getBackupLockedKeys()) {
            if (segments.contains(readCh.getSegment(key))) {
               lockedKeys.add(key);
            }
         }
         if (!lockedKeys.isEmpty()) {
            List<WriteCommand> txModifications = tx.getModifications();
            WriteCommand[] modifications = null;
            if (txModifications != null) {
               modifications = txModifications.toArray(new WriteCommand[txModifications.size()]);
            }
            transactionsToTransfer.add(new TransactionInfo(tx.getGlobalTransaction(), tx.getTopologyId(), modifications, lockedKeys));
         }
      }
   }

   @Override
   public void startOutboundTransfer(Address destination, int requestTopologyId, Set<Integer> segments)
         throws InterruptedException {
      if (trace) {
         log.tracef("Starting outbound transfer of segments %s to node %s with topology id %d", segments,
               destination, requestTopologyId);
      }

      final CacheTopology cacheTopology = stateConsumer.getCacheTopology();
      if (cacheTopology == null) {
         throw new IllegalStateException("No cache topology received yet");  // no commands are processed until the join is complete, so this cannot normally happen
      }
      final ConsistentHash readCh = cacheTopology.getReadConsistentHash();
      final int topologyId = cacheTopology.getTopologyId();

      if (requestTopologyId < topologyId) {
         log.warnf("Segments were requested by node %s with topology %d, smaller than the local " +
               "topology (%d)", destination, requestTopologyId, topologyId);
      } else if (requestTopologyId > topologyId) {
         if (trace) {
            log.tracef("Segments were requested by node %s with topology %d, greater than the local " +
                  "topology (%d). Waiting for topology %d to be installed locally.", destination,
                  requestTopologyId, topologyId, requestTopologyId);
         }
         stateTransferLock.waitForTopology(requestTopologyId);
      }

      // the destination node must already have an InboundTransferTask waiting for these segments
      OutboundTransferTask outboundTransfer = new OutboundTransferTask(destination, segments, chunkSize, topologyId,
            readCh, this, dataContainer, cacheLoaderManager, rpcManager, commandsFactory, timeout, cacheName);
      addTransfer(outboundTransfer);
      outboundTransfer.execute(executorService);
   }

   private void addTransfer(OutboundTransferTask transferTask) {
      if (trace) {
         log.tracef("Adding outbound transfer of segments %s to %s", transferTask.getSegments(), transferTask.getDestination());
      }
      synchronized (transfersByDestination) {
         List<OutboundTransferTask> transfers = transfersByDestination.get(transferTask.getDestination());
         if (transfers == null) {
            transfers = new ArrayList<OutboundTransferTask>();
            transfersByDestination.put(transferTask.getDestination(), transfers);
         }
         transfers.add(transferTask);
      }
   }

   @Override
   public void cancelOutboundTransfer(Address destination, int topologyId, Set<Integer> segments) {
      if (trace) {
         log.tracef("Cancelling outbound transfer of segments %s to node %s with topology id %d", segments, destination, topologyId);
      }
      // get the outbound transfers for this address and given segments and cancel the transfers
      synchronized (transfersByDestination) {
         List<OutboundTransferTask> transferTasks = transfersByDestination.get(destination);
         if (transferTasks != null) {
            // get an array copy of the collection to avoid ConcurrentModificationException if the entire task gets cancelled and removeTransfer(transferTask) is called
            OutboundTransferTask[] tasks = transferTasks.toArray(new OutboundTransferTask[transferTasks.size()]);
            for (OutboundTransferTask transferTask : tasks) {
               transferTask.cancelSegments(segments); //this can potentially result in a call to removeTransfer(transferTask)
            }
         }
      }
   }

   private void removeTransfer(OutboundTransferTask transferTask) {
      synchronized (transfersByDestination) {
         List<OutboundTransferTask> transferTasks = transfersByDestination.get(transferTask.getDestination());
         if (transferTasks != null) {
            transferTasks.remove(transferTask);
            if (transferTasks.isEmpty()) {
               transfersByDestination.remove(transferTask.getDestination());
            }
         }
      }
   }

   void onTaskCompletion(OutboundTransferTask transferTask) {
      if (trace) {
         log.tracef("Removing %s outbound transfer of segments %s to %s",
               transferTask.isCancelled() ? "cancelled" : "completed", transferTask.getSegments(), transferTask.getDestination());
      }

      removeTransfer(transferTask);
   }
}
