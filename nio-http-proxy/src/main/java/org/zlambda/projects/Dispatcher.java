package org.zlambda.projects;

import org.slf4j.Logger;
import org.zlambda.projects.context.ConnectionContext;
import org.zlambda.projects.context.ProxyContext;
import org.zlambda.projects.context.SystemContext;
import org.zlambda.projects.context.WorkerContext;
import org.zlambda.projects.utils.Common;
import org.zlambda.projects.utils.SelectionKeyUtils;
import org.zlambda.projects.utils.SocketChannelUtils;

import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Dispatcher extends Thread {
  /**
   * For development debug usage
   */
  private static final Logger LOGGER = Common.getSystemLogger();
  private final SystemContext systemContext;
  // for synchronize the contextSet update when worker thread exit
  private final Object contextSetMonitor = new Object();
  private final Set<WorkerContext> workerContextSet = new HashSet<>();
  private final ExecutorService executorService;

  public Dispatcher(SystemContext systemContext) {
    super(Dispatcher.class.getSimpleName());
    this.systemContext = systemContext;
    this.executorService = Executors.newFixedThreadPool(systemContext.getNumWorkers());
  }

  private void createAndStartWorker() throws Exception {
    WorkerContext context = new WorkerContext.Builder()
        .selector(Selector.open())
        .contextSet(workerContextSet)
        .contextSetMonitor(contextSetMonitor)
        .build();
    workerContextSet.add(context);
    executorService.submit(new Worker(context));
  }

  @Override
  public void run() {
    LOGGER.info("{} thread started", getName());
    try {
      List<String> activeChannelStats = new ArrayList<>();
      while (true) {
        SocketChannel client;
        try {
          client = systemContext.getClientQueue().take();
          LOGGER.info("got client connection {}", client);
        } catch (InterruptedException e) {
          LOGGER.error("got interruptedException while taking clientQueue.", e);
          Thread.currentThread().interrupt();
          continue;
        }
        /**
         * Most of the time, contextSetMonitor is contention free. When worker thread exits due to
         * unexpected exception, the worker will try to remove its context from the context set. So
         * only at that time, there will be contention between dispatcher thread and worker thread.
         */
        synchronized (contextSetMonitor) {
          if (workerContextSet.size() < systemContext.getNumWorkers()) {
            createAndStartWorker();
          }
          int minNumChannels = Integer.MAX_VALUE;
          WorkerContext targetWorkerContext = null;
          Iterator<WorkerContext> it = workerContextSet.iterator();
          int totalActives = 0;
          while (it.hasNext()) {
            WorkerContext ct = it.next();
            int num = ct.getNumConnections();
            totalActives += num;
            if (minNumChannels > num) {
              minNumChannels = num;
              targetWorkerContext = ct;
            }
            activeChannelStats.add(String.format("[%s:%d]", ct.getName(), num));
          }
          /**
           * approximate statds
           */
          LOGGER.info("approximate total active channels <{}>, stats <{}>", totalActives,
                      activeChannelStats);
          activeChannelStats.clear();
          try {
            synchronized (targetWorkerContext.getWakeupBarrier()) {
              targetWorkerContext.getSelector().wakeup();
              ProxyContext proxyContext = new ProxyContext(systemContext);
              SelectionKey key = client.register(
                  targetWorkerContext.getSelector(),
                  SelectionKey.OP_READ,
                  new ClientSocketChannelHandler(proxyContext));
              proxyContext.setClient(new ConnectionContext(key, SelectionKeyUtils.getName(key)));
              MonitorSingleton.get().collectChannelPair(
                  proxyContext.getClient(),
                  null
              );
            }
          } catch (ClosedChannelException e) {
            LOGGER.error("Failed to register socket channel <{}>, reason {}.",
                         SocketChannelUtils.getRemoteAddress(client), e.getCause(), e);
          }
        }
      }
    } catch (Exception e) {
      LOGGER.error("got unexpected exception: {}, so terminate application.", e.getCause(), e);
      System.exit(-1);
    }
  }
}
