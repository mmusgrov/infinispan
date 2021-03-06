/*
 * JBoss, Home of Professional Open Source
 * Copyright 2010 Red Hat Inc. and/or its affiliates and other
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
package org.infinispan.tx.dld;

import org.infinispan.test.PerCacheExecutorThread;
import org.infinispan.test.TestingUtil;
import org.infinispan.util.concurrent.locks.DeadlockDetectingLockManager;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;

import javax.transaction.RollbackException;
import javax.transaction.Status;
import javax.transaction.SystemException;

import static org.testng.Assert.assertEquals;

public abstract class BaseDldPessimisticLockingTest extends BaseDldTest {
   protected PerCacheExecutorThread ex0;
   protected PerCacheExecutorThread ex1;
   protected DeadlockDetectingLockManager lm0;
   protected DeadlockDetectingLockManager lm1;

   @BeforeMethod(alwaysRun=true)
   public void beforeMethod() {
      ex0 = new PerCacheExecutorThread(cache(0), 0);
      ex1 = new PerCacheExecutorThread(cache(1), 1);

      lm0 = (DeadlockDetectingLockManager) TestingUtil.extractLockManager(cache(0));
      lm0.setExposeJmxStats(true);
      lm1 = (DeadlockDetectingLockManager) TestingUtil.extractLockManager(cache(1));
      lm1.setExposeJmxStats(true);
   }

   @AfterMethod(alwaysRun=true)
   public void afterMethod() {
      ex0.stopThread();
      ex1.stopThread();
   }

   protected void testSymmetricDld(Object k0, Object k1) throws SystemException {

      long start = System.currentTimeMillis();

      log.trace("Here is where the test starts");

      ex0.execute(PerCacheExecutorThread.Operations.BEGIN_TX);
      ex1.execute(PerCacheExecutorThread.Operations.BEGIN_TX);


      ex0.setKeyValue(k0, "v0_0");
      assertEquals(ex0.execute(PerCacheExecutorThread.Operations.PUT_KEY_VALUE), PerCacheExecutorThread.OperationsResult.PUT_KEY_VALUE_OK);
      ex1.setKeyValue(k1, "v1_1");
      assertEquals(ex1.execute(PerCacheExecutorThread.Operations.PUT_KEY_VALUE), PerCacheExecutorThread.OperationsResult.PUT_KEY_VALUE_OK);

      assertKeyLockedCorrectly(k0);
      assertKeyLockedCorrectly(k1);

      log.trace("After first set of puts");

      ex0.clearResponse();
      ex1.clearResponse();

      log.info("Here is where DLD happens");

      ex1.setKeyValue(k0, "v0_1");
      ex1.executeNoResponse(PerCacheExecutorThread.Operations.PUT_KEY_VALUE);
      ex0.setKeyValue(k1, "v1_0");
      ex0.executeNoResponse(PerCacheExecutorThread.Operations.PUT_KEY_VALUE);
      ex0.waitForResponse();
      ex1.waitForResponse();

      final Object resp0 = ex0.lastResponse();
      boolean b1 = resp0 instanceof Exception;
      final Object resp1 = ex1.lastResponse();
      boolean b2 = resp1 instanceof Exception;
      assert xor(b1, b2) : "Both are " + (b1 || b2);

      assert xor(ex0.getOngoingTransaction().getStatus() == Status.STATUS_MARKED_ROLLBACK,
                 ex1.getOngoingTransaction().getStatus() == Status.STATUS_MARKED_ROLLBACK);

      log.trace("About to commit transactions");

      Object txOutcome1 = ex0.execute(PerCacheExecutorThread.Operations.COMMIT_TX);
      Object txOutcome2 = ex1.execute(PerCacheExecutorThread.Operations.COMMIT_TX);
      System.out.println("txOutcome2 = " + txOutcome1);
      System.out.println("txOutcome2 = " + txOutcome2);
      assert xor(txOutcome1 == PerCacheExecutorThread.OperationsResult.COMMIT_TX_OK, txOutcome2 == PerCacheExecutorThread.OperationsResult.COMMIT_TX_OK);
      assert xor(txOutcome1 instanceof RollbackException, txOutcome2 instanceof RollbackException);

      log.tracef("k0 is %s, \n k1 is %s", k0, k1);

      assert cache(0).get(k0) != null;
      assert cache(0).get(k1) != null;
      assert cache(1).get(k0) != null;
      assert cache(1).get(k1) != null;

      long totalDeadlocks = lm0.getTotalNumberOfDetectedDeadlocks() + lm1.getTotalNumberOfDetectedDeadlocks();
      assert totalDeadlocks == 1 : "Expected 1 but received " + totalDeadlocks;

      System.out.println("Test took " + (System.currentTimeMillis() - start) + " millis.");
   }
}
