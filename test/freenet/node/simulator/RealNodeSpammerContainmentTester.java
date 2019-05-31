package freenet.node.simulator;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;

import freenet.crypt.DummyRandomSource;
import freenet.io.comm.PeerParseException;
import freenet.io.comm.ReferenceSignatureVerificationException;
import freenet.keys.CHKEncodeException;
import freenet.keys.ClientKeyBlock;
import freenet.keys.Key;
import freenet.keys.KeyBlock;
import freenet.keys.KeyDecodeException;
import freenet.keys.SSKEncodeException;
import freenet.node.BypassMessageQueue;
import freenet.node.BypassPacketFormat;
import freenet.node.FSParseException;
import freenet.node.LowLevelGetException;
import freenet.node.LowLevelPutException;
import freenet.node.Node;
import freenet.node.NodeInitException;
import freenet.node.NodeStarter;
import freenet.node.NodeStarter.TestNodeParameters;
import freenet.node.NodeStarter.TestingVMBypass;
import freenet.node.PeerNode;
import freenet.node.RequestCompletionListener;
import freenet.node.simulator.LocalRequestUIDsCounter.NodeStatsSnapshot;
import freenet.node.simulator.SimulatorRequestTracker.Request;
import freenet.support.Executor;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.LoggerHook.InvalidThresholdException;
import freenet.support.PooledExecutor;
import freenet.support.PrioritizedTicker;
import freenet.support.compress.InvalidCompressionCodecException;
import freenet.support.io.FileUtil;

/** Lots of simultaneous request initiated by a single node. 
 * What happens? Hopefully most of them are rejected early on and path length of successful 
 * requests is not affected much. FIXME Ideally we'd compare this to a separate sequence of 
 * slower, non-malicious requests. */
public class RealNodeSpammerContainmentTester extends RealNodeRequestInsertParallelTester {

    private static volatile boolean logMINOR;
    private static volatile boolean logDEBUG;
    static {
        // This doesn't work if the class is loaded before the logger.
        Logger.registerClass(RealNodeSpammerContainmentTester.class);
    }
    
    public static void main(String[] args) throws FSParseException, PeerParseException, CHKEncodeException, InvalidThresholdException, NodeInitException, ReferenceSignatureVerificationException, InterruptedException, SimulatorOverloadedException, SSKEncodeException, InvalidCompressionCodecException, IOException, KeyDecodeException {
        try {
        parseOptions(args);
        String name = "realNodeRequestInsertParallelTest";
        File wd = new File(name);
        if(!FileUtil.removeAll(wd)) {
            System.err.println("Mass delete failed, test may not be accurate.");
            System.exit(EXIT_CANNOT_DELETE_OLD_DATA);
        }
        wd.mkdir();
        //NOTE: globalTestInit returns in ignored random source
        //String logDetails = "freenet.node.Request:MINOR,freenet.node.CHK:MINOR,freenet.node.SSK:MINOR," +
        //      "freenet.io.comm.MessageCore:MINOR,freenet.node.Peer:MINOR,freenet.node.Node:MINOR";
        //String logDetails = "freenet.node.Bypass:MINOR";
        String logDetails = "";
        NodeStarter.globalTestInit(new File(name), false, LogLevel.ERROR, logDetails, true, 
                BYPASS_TRANSPORT_LAYER, null);
        // Need to call it explicitly because the class is loaded before we clobbered the logger.
        Logger.registerClass(RealNodeSpammerContainmentTester.class);
        System.out.println("Parallel insert/retrieve test (single node originator)");
        System.out.println();
        System.err.println("Seed is "+SEED);
        System.err.println("Parallel requests: "+PARALLEL_REQUESTS);
        System.err.println("Bypass: "+BYPASS_TRANSPORT_LAYER);
        DummyRandomSource random = new DummyRandomSource(SEED);
        DummyRandomSource nodesRandom = new DummyRandomSource(SEED+1);
        DummyRandomSource topologyRandom = new DummyRandomSource(SEED+2);
        SimulatorRequestTracker tracker = new SimulatorRequestTracker(MAX_HTL);
        //DiffieHellman.init(random);
        Node[] nodes = new Node[NUMBER_OF_NODES];
        Logger.normal(RealNodeRoutingTester.class, "Creating nodes...");
        int tickerThreads = Runtime.getRuntime().availableProcessors();
        Executor[] executors = new Executor[tickerThreads];
        PrioritizedTicker[] tickers = new PrioritizedTicker[tickerThreads];
        for(int i=0;i<tickerThreads;i++) {
            executors[i] = new PooledExecutor();
            tickers[i] = new PrioritizedTicker(executors[i]);
        }
        final LocalRequestUIDsCounter overallUIDTagCounter =
                new LocalRequestUIDsCounter();
        for(int i=0;i<NUMBER_OF_NODES;i++) {
            TestNodeParameters params = getNodeParameters(i, name, nodesRandom, 
                    executors[i % tickerThreads], tickers[i % tickerThreads], overallUIDTagCounter);
            nodes[i] = NodeStarter.createTestNode(params);
            tracker.add(nodes[i]);
            Logger.normal(RealNodeRoutingTester.class, "Created node "+i);
        }
        
        // Now link them up
        makeKleinbergNetwork(nodes, START_WITH_IDEAL_LOCATIONS, DEGREE, FORCE_NEIGHBOUR_CONNECTIONS, topologyRandom);

        Logger.normal(RealNodeRoutingTester.class, "Added random links");
        
        for(int i=0;i<NUMBER_OF_NODES;i++) {
            nodes[i].start(false);
            System.err.println("Started node "+i+"/"+nodes.length);
        }
        
        if(NodeStarter.isMessageQueueBypassEnabled()) {
            System.err.println("Starting fake connections (message bypass)...");
            for(int i=0;i<NUMBER_OF_NODES;i++) {
                Node n = nodes[i];
                for(PeerNode pnSource : n.getPeerNodes()) {
                    BypassMessageQueue queue = 
                        (BypassMessageQueue) pnSource.getMessageQueue();
                    queue.fakeConnect();
                }
                System.err.println("Started fake connections for node "+i+"/"+nodes.length);
            }
        } else if(NodeStarter.isPacketBypassEnabled()) {
            System.err.println("Starting fake connections (packet bypass)...");
            for(int i=0;i<NUMBER_OF_NODES;i++) {
                Node n = nodes[i];
                for(PeerNode pnSource : n.getPeerNodes()) {
                    BypassPacketFormat bypass =
                        (BypassPacketFormat) pnSource.getPacketFormat();
                    bypass.fakeConnect();
                }
                System.err.println("Started fake connections for node "+i+"/"+nodes.length);
            }
        }
        
        // Wait for all connected *and* average ping is acceptable i.e. CPU load is settled.
        // For NONE, this means wait for connection setup to finish.
        waitForAllConnected(nodes);
        
        if(BYPASS_TRANSPORT_LAYER == TestingVMBypass.NONE) {
            // Wait until we are sure it stabilises.
            waitForPingAverage(0.5, nodes, new DummyRandomSource(SEED+4), MAX_PINGS, 1000);
        }
        
        random = new DummyRandomSource(SEED+3);
        
        System.out.println();
        System.out.println("Ping average > 95%, lets do some inserts/requests");
        System.out.println();
        
        RealNodeRequestInsertParallelTester tester = 
            new RealNodeSpammerContainmentTester(nodes, random, TARGET_SUCCESSES, tracker, overallUIDTagCounter);
        
        waitForAllConnected(nodes, true, true, false);
        while(true) {
            waitForAllConnected(nodes, true, false, true);
            int status = tester.insertRequestTest();
            if(status == -1) continue;
            System.exit(status);
        }
        } catch (Throwable t) {
            // Need to explicitly exit because the wrapper thread may prevent shutdown.
            // FIXME WTF? Shouldn't be using the wrapper???
            Logger.error(RealNodeRequestInsertParallelTester.class, "Caught "+t, t);
            System.err.println(t);
            t.printStackTrace();
            System.exit(1);
        }
    }

    public RealNodeSpammerContainmentTester(Node[] nodes, DummyRandomSource random,
            int targetSuccesses, SimulatorRequestTracker tracker,
            LocalRequestUIDsCounter overallUIDTagCounter) {
        super(nodes, random, targetSuccesses, tracker, overallUIDTagCounter);
        spammer1 = nodes[random.nextInt(nodes.length)];
        Node spam2;
        do {
            spam2 = nodes[random.nextInt(nodes.length)];
        } while(spammer1 == spam2);
        spammer2 = spam2;
    }

    public class MyFetchListener implements RequestCompletionListener {
        
        private final int req;
        private final Key key;
        private final boolean log;
        private final long startTime;

        public MyFetchListener(Key key, int req, boolean log) {
            this.key = key;
            this.req = req;
            this.log = log;
            startTime = System.currentTimeMillis();
        }

        @Override
        public void onSucceeded() {
            long timeTaken = System.currentTimeMillis()-startTime;
            Logger.normal(this, "Succeeded request for "+req+" in "+timeTaken);
            Request request = getRequest();
            int hopCount = request == null ? 0 : request.count();
            Logger.normal(this, "Success: "+req+" ("+hopCount+" hops)");
            if(request != null) {
                Logger.normal(this, request.dump(false, "Request "+req+" : "));
            }
            reportSuccess(hopCount, log, timeTaken);
        }
        
        private Request getRequest() {
            Request[] dump = tracker.dumpKey(key, false);
            if(dump.length == 0) return null;
            assert(dump.length == 1);
            return dump[0];
        }

        @Override
        public void onFailed(LowLevelGetException e) {
            long timeTaken = System.currentTimeMillis()-startTime;
            Logger.normal(this, "Failed request for "+req+" : "+e+" in "+timeTaken);
            Logger.normal(this, "Failure: "+req+" : "+e);
            Logger.normal(this, getRequest().dump(false, "Request "+req+" : "));
            reportFailure(log, timeTaken);
        }

    }

    private final Node spammer1;
    private final Node spammer2;
    
    @Override
    protected void startFetch(int req, Key k, boolean log) {
        System.out.println("Starting request for "+req);
        if(logMINOR) Logger.minor(this, "Fetching "+k+" for "+req);
        MyFetchListener listener = new MyFetchListener(k, req, log);
        spammer2.clientCore.asyncGet(k, false, listener, true, true, false, false, false);
    }

    private Node randomNode() {
        return nodes[1+random.nextInt(nodes.length-1)];
    }

    @Override
    protected ClientKeyBlock generateBlock(int req) throws UnsupportedEncodingException, CHKEncodeException, InvalidCompressionCodecException {
        return generateCHKBlock(req);
    }
    
    @Override
    protected void startInsert(ClientKeyBlock block, InsertWrapper insertWrapper) {
        Node insertNode = getInsertNode(insertWrapper.req);
        if(logMINOR) Logger.minor(this, "Inserting "+block.getKey()+" for "+insertWrapper.req);
        Runnable insertJob = new MyInsertJob(block.getBlock(), insertWrapper, insertNode);
        insertNode.executor.execute(insertJob);
    }
    
    public class MyInsertJob implements Runnable {
        
        private final KeyBlock block;
        private final InsertWrapper insertWrapper;
        private final Node insertNode;

        public MyInsertJob(KeyBlock block, InsertWrapper insertWrapper, Node insertNode) {
            this.block = block;
            this.insertWrapper = insertWrapper;
            this.insertNode = insertNode;
        }

        @Override
        public void run() {
            if(logMINOR)
                Logger.minor(this, "Starting insert for "+insertWrapper.req);
            try {
                insertNode.clientCore.realPut(block, true, FORK_ON_CACHEABLE, false, 
                        false, false);
                if(logMINOR)
                    Logger.minor(this, "Succeeded insert for "+insertWrapper.req);
                insertWrapper.succeeded(block.getKey());
            } catch (LowLevelPutException e) {
                if(logMINOR)
                    Logger.minor(this, "Failed insert for "+insertWrapper.req+" : "+e);
                insertWrapper.failed(e.toString());
            }
        }

    }
    
    protected synchronized void dumpStats() {
        super.dumpStats();
        NodeStatsSnapshot stats = this.overallUIDTagCounter.getStats(spammer2);
        System.err.println("Running requests overall "+overallUIDTagCounter.getCount());
        System.err.println("Running local requests on originator "+stats.runningLocalRequests+" average "+stats.averageRunningLocalRequests);
        System.err.println("Running requests on originator "+stats.runningRequests+" average "+stats.averageRunningRequests);
        stats = this.overallUIDTagCounter.getStats(spammer1);
        System.err.println("Running local requests on inserter "+stats.runningLocalRequests+" average "+stats.averageRunningLocalRequests);
        System.err.println("Running requests on inserter "+stats.runningRequests+" average "+stats.averageRunningRequests);
        System.err.println("Running local inserts overall: "+
                overallUIDTagCounter.getTotalRunningLocalInserts()+" average "+
                overallUIDTagCounter.getAverageTotalRunningLocalInserts());
    }
    
}
