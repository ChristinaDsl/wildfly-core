/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
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

/**
 *
 */
package org.jboss.as.controller;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ACTIVE_OPERATION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.COMPOSITE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.EXECUTION_STATUS;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.NAME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OUTCOME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.RESULT;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SUCCESS;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.VALUE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.descriptions.NonResolvingResourceDescriptionResolver;
import org.jboss.as.controller.operations.common.Util;
import org.jboss.as.controller.operations.global.GlobalNotifications;
import org.jboss.as.controller.operations.global.GlobalOperationHandlers;
import org.jboss.as.controller.registry.ManagementResourceRegistration;
import org.jboss.as.controller.registry.Resource;
import org.jboss.dmr.ModelNode;
import org.jboss.msc.service.Service;
import org.jboss.msc.service.ServiceContainer;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceTarget;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

/**
 * Unit tests of {@link ModelControllerImpl}.
 *
 * @author Brian Stansberry (c) 2011 Red Hat Inc.
 */
@Ignore("WFCORE-6158")
public class OperationCancellationUnitTestCase {

    private static final Executor executor = Executors.newCachedThreadPool();

    private static CountDownLatch blockObject;
    private static CountDownLatch latch;
    private ServiceContainer container;
    private ModelController controller;
    private ModelControllerClient client;
    private Resource managementControllerResource;

    public static void toggleRuntimeState(AtomicBoolean state) {
        boolean runtimeVal = false;
        while (!state.compareAndSet(runtimeVal, !runtimeVal)) {
            runtimeVal = !runtimeVal;
        }
    }

    @Before
    public void setupController() throws InterruptedException {

        // restore default
        blockObject = new CountDownLatch(1);
        latch = new CountDownLatch(1);

        System.out.println("=========  New Test \n");
        container = ServiceContainer.Factory.create("test");
        ServiceTarget target = container.subTarget();
        ModelControllerService svc = new ModelControllerService();
        target.addService(ServiceName.of("ModelController")).setInstance(svc).install();
        svc.awaitStartup(30, TimeUnit.SECONDS);
        controller = svc.getValue();
        ModelNode setup = Util.getEmptyOperation("setup", new ModelNode());
        controller.execute(setup, null, null, null);

        client = controller.createClient(executor);

        managementControllerResource = svc.managementControllerResource;
    }

    @After
    public void shutdownServiceContainer() {

        releaseBlockingThreads();

        if (client != null) {
            try {
                client.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        if (container != null) {
            container.shutdown();
            try {
                container.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            finally {
                container = null;
            }
        }
    }

    private static void releaseBlockingThreads() {
        if (blockObject != null) {
            blockObject.countDown();
        }
    }

    private static void block() {
        latch.countDown();
        try {
            blockObject.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static class ModelControllerService extends TestModelControllerService {

        private volatile Resource managementControllerResource;

        @Override
        protected void initModel(ManagementModel managementModel, Resource modelControllerResource) {
            ManagementResourceRegistration rootRegistration = managementModel.getRootResourceRegistration();
            rootRegistration.registerOperationHandler(getOD("setup"), new SetupHandler());
            rootRegistration.registerOperationHandler(getOD("composite"), CompositeOperationHandler.INSTANCE);
            rootRegistration.registerOperationHandler(getOD("good"), new ModelStageGoodHandler());
            rootRegistration.registerOperationHandler(getOD("block-model"), new ModelStageBlocksHandler());
            rootRegistration.registerOperationHandler(getOD("block-runtime"), new RuntimeStageBlocksHandler(getSharedState()));
            rootRegistration.registerOperationHandler(getOD("good-service"), new GoodServiceHandler());
            rootRegistration.registerOperationHandler(getOD("block-verify"), new BlockingServiceHandler());

            GlobalOperationHandlers.registerGlobalOperations(rootRegistration, processType);

            GlobalNotifications.registerGlobalNotifications(rootRegistration, processType);

            rootRegistration.registerSubModel(new SimpleResourceDefinition(PathElement.pathElement("child"), NonResolvingResourceDescriptionResolver.INSTANCE));
            this.managementControllerResource = modelControllerResource;
        }
    }

    @Test
    public void testModelStageInterruption() throws Exception {
        modelStageInterruptionTest(false);
    }

    @Test
    public void testModelStageMgmtCancellation() throws Exception {
        modelStageInterruptionTest(true);
    }

    private void modelStageInterruptionTest(boolean cancelViaResource) throws Exception {
        ModelNode step1 = getOperation("good", "attr1", 2);
        ModelNode step2 = getOperation("block-model", "attr2", 1);
        Future<ModelNode> future = client.executeAsync(getCompositeOperation(null, step1, step2), null);

        latch.await();

        if (cancelViaResource) {
            testCancelViaMmgtOpResource(COMPOSITE, OperationContext.ExecutionStatus.EXECUTING, future, false);
        } else {
            // cancel should return false becase interrupting here will result in a RuntimeException in the block-model handler
            assertFalse(future.cancel(true));
        }

        // Confirm model was unchanged
        ModelNode result = controller.execute(getOperation("good", "attr1", 1), null, null, null);
        assertEquals(SUCCESS, result.get(OUTCOME).asString());
        assertEquals(1, result.get(RESULT).asInt());

    }

    private void testCancelViaMmgtOpResource(String expectedOpName, OperationContext.ExecutionStatus expectedExecutionStatus, Future<ModelNode> future, boolean expectCancellationException) throws ExecutionException, InterruptedException {
        boolean cancelled = false;
        long timeout = System.currentTimeMillis() + 10000;
        String matchedStatus = null;
        OUT:
        while (System.currentTimeMillis() < timeout) {
            for (Resource resource : managementControllerResource.getChildren(ACTIVE_OPERATION)) {
                if (resource.getModel().get(OP).asString().equals(expectedOpName)) {
                    matchedStatus = resource.getModel().get(EXECUTION_STATUS).asString();
                    if (expectedExecutionStatus.toString().equals(matchedStatus)) {
                        cancelled = Cancellable.class.cast(resource).cancel();
                        break OUT;
                    }
                    break;
                }
            }
            if (matchedStatus != null) {
                // The op thread may have tripped the latch but still hasn't
                // gotten to the blocking code. So give it time
                Thread.sleep(100);
            }
        }
        assertEquals(expectedExecutionStatus.toString(), matchedStatus);
        assertTrue(cancelled);
        try {
            future.get();
            assertFalse(expectCancellationException);
        } catch (CancellationException e) {
            assertTrue(expectCancellationException);
        }
    }

    @Test
    public void testRuntimeStageInterruption() throws Exception {
        runtimeStageInterruptionTest(false);
    }

    @Test
    public void testRuntimeStageMgmtCancellation() throws Exception {
        runtimeStageInterruptionTest(true);
    }

    private void runtimeStageInterruptionTest(boolean cancelViaResource) throws Exception {
        ModelNode step1 = getOperation("good", "attr1", 2);
        ModelNode step2 = getOperation("block-runtime", "attr2", 1);
        Future<ModelNode> future = client.executeAsync(getCompositeOperation(null, step1, step2), null);

        latch.await();

        if (cancelViaResource) {
            testCancelViaMmgtOpResource(COMPOSITE, OperationContext.ExecutionStatus.EXECUTING, future, false);
        } else {
            // cancel should return false becase interrupting here will result in a RuntimeException in the block-model handler
            assertFalse(future.cancel(true));
        }

        // Confirm model was unchanged
        ModelNode result = controller.execute(getOperation("good", "attr1", 1), null, null, null);
        assertEquals(SUCCESS, result.get(OUTCOME).asString());
        assertEquals(1, result.get(RESULT).asInt());

    }

    @Test
    public void testVerifyStageInterruption() throws Exception {
        verifyStageInterruptionTest(false);
    }

    @Test
    public void testVerifyStageMgmtCancellation() throws Exception {
        verifyStageInterruptionTest(true);
    }

    private void verifyStageInterruptionTest(boolean cancelViaResource) throws Exception {
        ModelNode step1 = getOperation("good", "attr1", 2);
        ModelNode step2 = getOperation("good-service", "attr1", 2);
        ModelNode step3 = getOperation("block-verify", "attr2", 1);
        Future<ModelNode> future = client.executeAsync(getCompositeOperation(null, step1, step2, step3), null);

        latch.await();

        if (cancelViaResource) {
            testCancelViaMmgtOpResource(COMPOSITE, OperationContext.ExecutionStatus.AWAITING_STABILITY, future, true);
        } else {
            assertTrue(future.cancel(true));
        }

        // Confirm model was unchanged
        ModelNode result = controller.execute(getOperation("good", "attr1", 1), null, null, null);
        assertEquals(SUCCESS, result.get(OUTCOME).asString());
        assertEquals(1, result.get(RESULT).asInt());
    }

    @Test
    public void testReadResourceForUpdateInterruption() throws Exception {
        readResourceForUpdateInterruptionTest(false);
    }

    @Test
    public void testReadResourceForUpdateMgmtCancellation() throws Exception {
        readResourceForUpdateInterruptionTest(true);
    }

    private void readResourceForUpdateInterruptionTest(boolean cancelViaResource) throws Exception {

        ModelNode blocker = getOperation("block-model", "attr2", 1);
        client.executeAsync(blocker, null);

        latch.await();

        ModelNode blockee = getOperation("good", "attr1", 2);
        Future<ModelNode> future = client.executeAsync(blockee, null);
        ModelStageGoodHandler.goodHandlerLatch.await(5, TimeUnit.SECONDS);

        if (cancelViaResource) {
            testCancelViaMmgtOpResource("good", OperationContext.ExecutionStatus.AWAITING_OTHER_OPERATION, future, true);
        } else {
            // cancel should return true
            assertTrue(future.cancel(true));
        }

        releaseBlockingThreads();

        // Confirm model was unchanged
        ModelNode result = controller.execute(getOperation("good", "attr1", 1), null, null, null);
        assertEquals(SUCCESS, result.get(OUTCOME).asString());
        assertEquals(1, result.get(RESULT).asInt());

    }

    public static ModelNode getOperation(String opName, String attr, int val) {
        return getOperation(opName, attr, val, null, false);
    }
    public static ModelNode getOperation(String opName, String attr, int val, String rollbackName) {
        return getOperation(opName, attr, val, rollbackName, false);
    }

    public static ModelNode getOperation(String opName, String attr, int val, String rollbackName, boolean async) {
        ModelNode op = new ModelNode();
        op.get(OP).set(opName);
        op.get(OP_ADDR).setEmptyList();
        op.get(NAME).set(attr);
        op.get(VALUE).set(val);
        op.get("rollbackName").set(rollbackName == null ? opName : rollbackName);

        if (async) {
            op.get("async").set(true);
        }
        return op;
    }

    public static class SetupHandler implements OperationStepHandler {

        @Override
        public void execute(OperationContext context, ModelNode operation) {
            ModelNode model = new ModelNode();

            //Atttributes
            model.get("attr1").set(1);
            model.get("attr2").set(2);

            context.readResourceForUpdate(PathAddress.EMPTY_ADDRESS).getModel().set(model);

            final ModelNode child1 = new ModelNode();
            child1.get("attribute1").set(1);
            final ModelNode child2 = new ModelNode();
            child2.get("attribute2").set(2);

            context.createResource(PathAddress.EMPTY_ADDRESS.append(PathElement.pathElement("child", "one"))).getModel().set(child1);
            context.createResource(PathAddress.EMPTY_ADDRESS.append(PathElement.pathElement("child", "two"))).getModel().set(child2);
        }
    }

    public static class ModelStageGoodHandler implements OperationStepHandler {

        static CountDownLatch goodHandlerLatch;

        ModelStageGoodHandler() {
            goodHandlerLatch = new CountDownLatch(1);
        }

        @Override
        public void execute(OperationContext context, ModelNode operation) {

            String name = operation.require(NAME).asString();

            goodHandlerLatch.countDown();

            ModelNode model = context.readResourceForUpdate(PathAddress.EMPTY_ADDRESS).getModel();
            ModelNode attr = model.get(name);
            final int current = attr.asInt();
            attr.set(operation.require(VALUE));

            context.getResult().set(current);

            context.completeStep(OperationContext.RollbackHandler.NOOP_ROLLBACK_HANDLER);
        }
    }

    public static class ModelStageBlocksHandler implements OperationStepHandler {

        @Override
        public void execute(OperationContext context, ModelNode operation) {

            context.readResourceForUpdate(PathAddress.EMPTY_ADDRESS).getModel();

            block();

            context.getResult();

            context.completeStep(OperationContext.RollbackHandler.NOOP_ROLLBACK_HANDLER);
        }
    }

    public static class RuntimeStageBlocksHandler implements OperationStepHandler {

        private final AtomicBoolean state;

        public RuntimeStageBlocksHandler(AtomicBoolean state) {
            this.state = state;
        }

        @Override
        public void execute(OperationContext context, ModelNode operation) {

            String name = operation.require("name").asString();
            context.readResourceForUpdate(PathAddress.EMPTY_ADDRESS).getModel().get(name);

            context.getResult();

            context.addStep(new OperationStepHandler() {

                @Override
                public void execute(OperationContext context, ModelNode operation) {
                    toggleRuntimeState(state);
                    block();

                    context.completeStep(new OperationContext.RollbackHandler() {
                        @Override
                        public void handleRollback(OperationContext context, ModelNode operation) {
                            toggleRuntimeState(state);
                        }
                    });
                }
            }, OperationContext.Stage.RUNTIME);

            context.completeStep(OperationContext.RollbackHandler.NOOP_ROLLBACK_HANDLER);
        }
    }

    public static class GoodServiceHandler implements OperationStepHandler {
        @Override
        public void execute(OperationContext context, ModelNode operation) {

            context.readResourceForUpdate(PathAddress.EMPTY_ADDRESS);

            context.addStep(new OperationStepHandler() {

                @Override
                public void execute(final OperationContext context, ModelNode operation) {

                    final ServiceName svcName = ServiceName.JBOSS.append("good-service");
                    context.getServiceTarget().addService(svcName).install();

                    context.completeStep(new OperationContext.RollbackHandler() {
                        @Override
                        public void handleRollback(OperationContext context, ModelNode operation) {
                            context.removeService(svcName);
                        }
                    });
                }
            }, OperationContext.Stage.RUNTIME);

            context.completeStep(OperationContext.RollbackHandler.NOOP_ROLLBACK_HANDLER);
        }
    }

    public static class BlockingServiceHandler implements OperationStepHandler {
        @Override
        public void execute(OperationContext context, ModelNode operation) {

            context.readResourceForUpdate(PathAddress.EMPTY_ADDRESS);

            context.addStep(new OperationStepHandler() {

                @Override
                public void execute(final OperationContext context, ModelNode operation) {

                    Service<Void> bad = new Service<Void>() {

                        @Override
                        public Void getValue() throws IllegalStateException, IllegalArgumentException {
                            return null;
                        }

                        @Override
                        public void start(StartContext context) throws StartException {
                            block();
                        }

                        @Override
                        public void stop(StopContext context) {
                            releaseBlockingThreads();
                        }

                    };
                    final ServiceName svcName = ServiceName.JBOSS.append("bad-service");
                    context.getServiceTarget().addService(svcName).setInstance(bad).install();

                    context.completeStep(new OperationContext.RollbackHandler() {
                        @Override
                        public void handleRollback(OperationContext context, ModelNode operation) {
                            context.removeService(svcName);
                        }
                    });
                }
            }, OperationContext.Stage.RUNTIME);

            context.completeStep(OperationContext.RollbackHandler.NOOP_ROLLBACK_HANDLER);
        }
    }

    static class RollbackTransactionControl implements ModelController.OperationTransactionControl {

        static final RollbackTransactionControl INSTANCE = new RollbackTransactionControl();

        @Override
        public void operationPrepared(ModelController.OperationTransaction transaction, ModelNode result) {
            transaction.rollback();
        }
    }

    public static ModelNode getCompositeOperation(Boolean rollback, ModelNode... steps) {

        ModelNode op = new ModelNode();
        op.get(OP).set("composite");
        op.get(OP_ADDR).setEmptyList();
        for (ModelNode step : steps) {
            op.get("steps").add(step);
        }
        if (rollback != null) {
            op.get("rollback-on-runtime-failure").set(rollback);
        }
        return op;
    }
}
