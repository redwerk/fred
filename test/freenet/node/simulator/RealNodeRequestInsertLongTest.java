package freenet.node.simulator;

import java.io.IOException;

import org.junit.Test;

import freenet.io.comm.PeerParseException;
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.keys.CHKEncodeException;
import freenet.keys.KeyDecodeException;
import freenet.keys.SSKEncodeException;
import freenet.node.FSParseException;
import freenet.node.NodeInitException;
import freenet.node.simulator.RealNodeRequestInsertTester.ExitException;
import freenet.node.simulator.RealNodeTester.SimulatorOverloadedException;
import freenet.support.LoggerHook.InvalidThresholdException;
import freenet.support.compress.InvalidCompressionCodecException;
import freenet.support.TestProperty;

/** Simulates sequential requests on a 100 node network with the fast bypass only. Again this 
 * checks that the results are exactly as expected.
 * @author toad
 */
public class RealNodeRequestInsertLongTest {
    
    protected static final String EXPECTED_RESULTS_HASH =
            "lURlxz5pns4KYNrmYfAqWzmGeIfynKBvM+a3grEyABg=";
    @Test
    public void testBigNetwork() throws CHKEncodeException, SSKEncodeException, FSParseException, PeerParseException, InvalidThresholdException, NodeInitException, ReferenceSignatureVerificationException, InterruptedException, SimulatorOverloadedException, InvalidCompressionCodecException, IOException, KeyDecodeException, ExitException {
        if(!TestProperty.EXTENSIVE) return;
        String[] args = 
                new String[] {"size=200","degree=10","htl=5","drop=0",
                "seed=123456","bypass=FAST_QUEUE_BYPASS"};
        RealNodeRequestInsertTester.TARGET_SUCCESSES = 100;
        RealNodeRequestInsertTester.LESS_LOGGING = true;
        RealNodeRequestInsertTester.EXPECTED_REPORT_CHECKSUM = EXPECTED_RESULTS_HASH;
        RealNodeRequestInsertTester.run(args);
    }
    
}
