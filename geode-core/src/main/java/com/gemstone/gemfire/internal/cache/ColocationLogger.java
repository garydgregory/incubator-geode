/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.gemstone.gemfire.internal.cache;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.Logger;

import com.gemstone.gemfire.CancelCriterion;
import com.gemstone.gemfire.SystemFailure;
import com.gemstone.gemfire.distributed.DistributedSystem;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.logging.LogService;
import com.gemstone.gemfire.internal.logging.log4j.LocalizedMessage;

/**
 * Provides logging when regions are missing from a colocation hierarchy. This logger runs in
 * it's own thread and waits for child regions to be created before logging them as missing.
 *
 */
public class ColocationLogger implements Runnable {
  private static final Logger logger = LogService.getLogger();

  private final PartitionedRegion region;
  private final List<String> missingChildren = new ArrayList<String>();
  private final Thread loggerThread;
  private final Object loggerLock = new Object();

  /**
   * Sleep period (milliseconds) between posting log entries.
   */
  private static final int DEFAULT_LOG_INTERVAL = 30000;
  private static int LOG_INTERVAL = DEFAULT_LOG_INTERVAL;

  /**
   * @param region the region that owns this logger instance
   */
  public ColocationLogger(PartitionedRegion region) {
    this.region = region;
    loggerThread = new Thread(this,"ColocationLogger for " + region.getName());
    loggerThread.start();
  }

  public void run()
  {
    CancelCriterion stopper = region
        .getGemFireCache().getDistributedSystem().getCancelCriterion();
    DistributedSystem.setThreadsSocketPolicy(true /* conserve sockets */);
    SystemFailure.checkFailure();
    if (stopper.cancelInProgress() != null) {
      return;
    }
    try {
      run2();
    } catch (VirtualMachineError err) {
      SystemFailure.initiateFailure(err);
      // If this ever returns, rethrow the error.  We're poisoned
      // now, so don't let this thread continue.
      throw err;
    } catch (Throwable t) {
      // Whenever you catch Error or Throwable, you must also
      // catch VirtualMachineError (see above).  However, there is
      // _still_ a possibility that you are dealing with a cascading
      // error condition, so you also need to check to see if the JVM
      // is still usable:
      SystemFailure.checkFailure();
      if (logger.isDebugEnabled()) {
        logger.debug("Unexpected exception in colocation", t);
      }
    }
  }

  /**
   * Writes a log entry every SLEEP_PERIOD when there are missing colocated child regions
   * for this region.
   * @throws InterruptedException
   */
  private void run2() throws InterruptedException {
    boolean firstLogIteration = true;
    synchronized(loggerLock) {
      while (true) {
        int sleepMillis = getLogInterval();
        // delay for first log message is half the time of the interval between subsequent log messages
        if (firstLogIteration) {
          firstLogIteration = false;
          sleepMillis /= 2;
        }
        loggerLock.wait(sleepMillis);
        PRHARedundancyProvider rp = region.getRedundancyProvider();
        if (rp != null && rp.isPersistentRecoveryComplete()) {
          //Terminate the logging thread, recoverycomplete is only true when there are no missing colocated regions
          break;
        }
        List<String>  existingRegions;
        Map coloHierarchy = ColocationHelper.getAllColocationRegions(region);
        missingChildren.removeAll(coloHierarchy.keySet());
        if(missingChildren.isEmpty()) {
          break;
        }
        logMissingRegions(region);
      }
    }
  }

  public void stopLogger() {
    synchronized (loggerLock) {
      missingChildren.clear();
      loggerLock.notify();
    }
  }

  public void addMissingChildRegion(String childFullPath) {
    synchronized (loggerLock) {
      if (!missingChildren.contains(childFullPath)) {
        missingChildren.add(childFullPath);
      }
    }
  }

  public void addMissingChildRegions(PartitionedRegion childRegion) {
    List<String> missingDescendants = childRegion.getMissingColocatedChildren();
    for (String name:missingDescendants) {
      addMissingChildRegion(name);
    }
  }

  public List<String> getMissingChildRegions() {
    return new ArrayList<String>(missingChildren);
  }

  /**
   * Write the a logger warning for a PR that has colocated child regions that are missing.
   * @param region the parent region that has missing child regions
   */
  private void logMissingRegions(PartitionedRegion region) {
    String namesOfMissing = "";
    if (!missingChildren.isEmpty()) {
      namesOfMissing = String.join("\n\t", missingChildren);
    }
    String multipleChildren;
    String singular = "";
    String plural = "s";
    multipleChildren = missingChildren.size() > 1 ? plural : singular;
    namesOfMissing = String.join("\n\t", multipleChildren, namesOfMissing);
    logger.warn(LocalizedMessage.create(LocalizedStrings.ColocationLogger_PERSISTENT_DATA_RECOVERY_OF_REGION_PREVENTED_BY_OFFLINE_COLOCATED_CHILDREN,
        new Object[]{region.getFullPath(), namesOfMissing}));
  }

  public static int getLogInterval() {
    return LOG_INTERVAL;
  }

  /*
   * Test hook to allow unit test tests to run faster by tweak the interval between log messages
   */
  public synchronized static int testhookSetLogInterval(int sleepMillis) {
    int currentSleep = LOG_INTERVAL;
    LOG_INTERVAL = sleepMillis;
    return currentSleep;
  }
  public synchronized static void testhookResetLogInterval() {
    LOG_INTERVAL = DEFAULT_LOG_INTERVAL;
  }
}