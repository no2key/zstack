package org.zstack.test.core.workflow;

import junit.framework.Assert;
import org.junit.Test;
import org.zstack.header.core.workflow.Flow;
import org.zstack.header.core.workflow.FlowTrigger;
import org.zstack.core.workflow.SimpleFlowChain;
import org.zstack.core.workflow.WorkFlowException;
import org.zstack.utils.Utils;
import org.zstack.utils.logging.CLogger;

import java.util.Map;

/**
 * Created with IntelliJ IDEA.
 * User: frank
 * Time: 3:33 PM
 * To change this template use File | Settings | File Templates.
 */
public class TestSimpleFlow2 {
    CLogger logger = Utils.getLogger(TestSimpleFlow2.class);

    @Test
    public void test() throws WorkFlowException {
        final int[] count = {0};

        new SimpleFlowChain()
                .then(new Flow() {
                    @Override
                    public void run(FlowTrigger chain, Map data) {
                        count[0] ++;
                        chain.next();
                    }

                    @Override
                    public void rollback(FlowTrigger chain, Map data) {
                        count[0] --;
                        chain.rollback();
                    }
                })
                .then(new Flow() {
                    @Override
                    public void run(FlowTrigger chain, Map data) {
                        count[0] ++;
                        chain.next();
                    }

                    @Override
                    public void rollback(FlowTrigger chain, Map data) {
                        count[0] --;
                        chain.rollback();
                    }
                })
                .then(new Flow() {
                    @Override
                    public void run(FlowTrigger chain, Map data) {
                        chain.rollback();
                    }

                    @Override
                    public void rollback(FlowTrigger chain, Map data) {
                        count[0] --;
                    }
                })
                .start();

        Assert.assertEquals(0, count[0]);
    }
}
