/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.simulator;

import freenet.crypt.RandomSource;
import freenet.io.comm.PeerParseException;
import freenet.node.FSParseException;
import freenet.node.Location;
import freenet.node.Node;
import freenet.node.NodeInitException;
import freenet.node.NodeStats;
import freenet.node.PeerNode;
import freenet.node.DarknetPeerNode.FRIEND_TRUST;
import freenet.node.DarknetPeerNode.FRIEND_VISIBILITY;
import freenet.node.PeerTooOldException;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;

/**
 * Optional base class for RealNode*Test.
 * Has some useful utilities.
 * @author toad
 * @author robert
 */
public class RealNodeTester {

	@SuppressWarnings("serial")
    public static class SimulatorOverloadedException extends Exception {

        public SimulatorOverloadedException(String msg) {
            super(msg);
        }

    }

    static final int EXIT_BASE = NodeInitException.EXIT_NODE_UPPER_LIMIT;
	static final int EXIT_CANNOT_DELETE_OLD_DATA = EXIT_BASE + 3;
	static final int EXIT_PING_TARGET_NOT_REACHED = EXIT_BASE + 4;
	static final int EXIT_INSERT_FAILED = EXIT_BASE + 5;
	static final int EXIT_REQUEST_FAILED = EXIT_BASE + 6;
	static final int EXIT_BAD_DATA = EXIT_BASE + 7;
	static final int EXIT_RESULTS_NOT_AS_EXPECTED = EXIT_BASE + 8;
	
	static final FRIEND_TRUST trust = FRIEND_TRUST.LOW;
	static final FRIEND_VISIBILITY visibility = FRIEND_VISIBILITY.NO;

        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}
	
	/* Because we start a whole bunch of nodes at once, we will get many "Not reusing
	 * tracker, so wiping old trackers" messages. This is normal, all the nodes start
	 * handshaking straight off, they all send JFK(1)s, and we get race conditions. */
	
	/*
	 Borrowed from mrogers simulation code (February 6, 2008)
	 --
	 FIXME: May not generate good networks. Presumably this is because the arrays are always scanned
	        [0..n], some nodes tend to have *much* higher connections than the degree (the first few),
	        starving the latter ones.
	 */
	static void makeKleinbergNetwork (Node[] nodes, boolean idealLocations, int degree, boolean forceNeighbourConnections, RandomSource random)
	{
		if(idealLocations) {
			// First set the locations up so we don't spend a long time swapping just to stabilise each network.
			double div = 1.0 / nodes.length;
			double loc = 0.0;
			for (int i=0; i<nodes.length; i++) {
				nodes[i].setLocation(loc);
				loc += div;
			}
		}
		if(forceNeighbourConnections) {
			for(int i=0;i<nodes.length;i++) {
				int next = (i+1) % nodes.length;
				connect(nodes[i], nodes[next]);
                Logger.normal(RealNodeTester.class, "Connecting node "+i+" to node "+next);
			}
		}
		for (int i=0; i<nodes.length; i++) {
		    System.err.println("Connecting node "+i+" of "+nodes.length);
			Node a = nodes[i];
			// Normalise the probabilities
			double norm = 0.0;
			for (int j=0; j<nodes.length; j++) {
				Node b = nodes[j];
				if (a.getLocation() == b.getLocation()) continue;
				norm += 1.0 / distance (a, b);
			}
			// Create degree/2 outgoing connections
			for (int k=0; k<nodes.length; k++) {
				Node b = nodes[k];
				if (a.getLocation() == b.getLocation()) continue;
				double p = 1.0 / distance (a, b) / norm;
				for (int n = 0; n < degree / 2; n++) {
					if (random.nextFloat() < p) {
						connect(a, b);
						Logger.normal(RealNodeTester.class, "Connecting node "+i+" to node "+k);
						break;
					}
				}
			}
		}
	}
	
	static void connect(Node a, Node b) {
		try {
			a.connect (b, trust, visibility);
			b.connect (a, trust, visibility);
		} catch (FSParseException e) {
			Logger.error(RealNodeTester.class, "cannot connect!!!!", e);
		} catch (PeerParseException e) {
			Logger.error(RealNodeTester.class, "cannot connect #2!!!!", e);
		} catch (freenet.io.comm.ReferenceSignatureVerificationException e) {
			Logger.error(RealNodeTester.class, "cannot connect #3!!!!", e);
		} catch (PeerTooOldException e) {
            Logger.error(RealNodeTester.class, "cannot connect #4!!!!", e);
        }
	}
	
	static double distance(Node a, Node b) {
		double aL=a.getLocation();
		double bL=b.getLocation();
		return Location.distance(aL, bL);
	}
	
	static String getPortNumber(PeerNode p) {
		if (p == null || p.getPeer() == null)
			return "null";
		return Integer.toString(p.getPeer().getPort());
	}
	
	static String getPortNumber(Node n) {
		if (n == null)
			return "null";
		return Integer.toString(n.getDarknetPortNumber());
	}
	
	static void waitForAllConnected(Node[] nodes) throws InterruptedException {
	    try {
            waitForAllConnected(nodes, false, false, false);
        } catch (SimulatorOverloadedException e) {
            // Impossible unless the booleans are true.
            throw new Error(e);
        }
	}
	
	/** Wait until all the nodes are connected.
	 * @param nodes List of nodes to wait for.
	 * @param disconnectFatal If true, all the nodes should be connected already, we are
	 * just checking for CPU overload in a simulation. Throw if any nodes are not connected 
	 * or have high ping times, which should only happen if there is severe CPU overload. 
	 * Note that ping times are generally a good proxy for CPU load.
	 * @param backoffFatal If true, we are doing a sequential simulation, i.e. one request
	 * at a time, so no requests should be rejected. So throw if any nodes are backed off.
	 * @param checkOnly If true, don't wait.
	 * @throws InterruptedException
	 */
	static void waitForAllConnected(Node[] nodes, boolean disconnectFatal, boolean backoffFatal, 
	        boolean checkOnly) throws InterruptedException, SimulatorOverloadedException {
		long tStart = System.currentTimeMillis();
		while(true) {
			int countFullyConnected = 0;
			int totalPeers = 0;
			int totalConnections = 0;
			int totalPartialConnections = 0;
			int totalCompatibleConnections = 0;
			int totalBackedOff = 0;
			double totalPingTime = 0.0;
			double maxPingTime = 0.0;
			double minPingTime = Double.MAX_VALUE;
			for(int i=0;i<nodes.length;i++) {
				int countConnected = nodes[i].peers.countConnectedDarknetPeers();
				int countAlmostConnected = nodes[i].peers.countAlmostConnectedDarknetPeers();
				int countTotal = nodes[i].peers.countValidPeers();
				int countBackedOff = nodes[i].peers.countBackedOffPeers(false);
				int countCompatible = nodes[i].peers.countCompatibleDarknetPeers();
				totalPeers += countTotal;
				totalConnections += countConnected;
				totalPartialConnections += countAlmostConnected;
				totalCompatibleConnections += countCompatible;
				totalBackedOff += countBackedOff;
				double pingTime = nodes[i].nodeStats.getNodeAveragePingTime();
				totalPingTime += pingTime;
				if(pingTime > maxPingTime) maxPingTime = pingTime;
				if(pingTime < minPingTime) minPingTime = pingTime;
				if(countConnected == countTotal) {
					countFullyConnected++;
				} else {
					if(logMINOR)
						Logger.minor(RealNodeTester.class, "Connection count for "+nodes[i]+" : "+countConnected+" partial "+countAlmostConnected);
				}
				if(countBackedOff > 0) {
					if(logMINOR)
						Logger.minor(RealNodeTester.class, "Backed off: "+nodes[i]+" : "+countBackedOff);
				}
			}
			double avgPingTime = totalPingTime / nodes.length;
			if(countFullyConnected == nodes.length && totalBackedOff == 0 &&
					minPingTime < NodeStats.DEFAULT_SUB_MAX_PING_TIME && maxPingTime < NodeStats.DEFAULT_SUB_MAX_PING_TIME && avgPingTime < NodeStats.DEFAULT_SUB_MAX_PING_TIME) {
				System.out.println("All nodes fully connected");
				Logger.normal(RealNodeTester.class, "All nodes fully connected");
				//System.err.println();
				return;
			} else {
			    if(disconnectFatal) {
			        if(countFullyConnected != nodes.length) {
			            throw new SimulatorOverloadedException("Disconnected nodes - possible CPU overload???");
			        } else if(maxPingTime >= NodeStats.DEFAULT_SUB_MAX_PING_TIME) {
			            throw new SimulatorOverloadedException("Nodes have high ping time, possible CPU overload?");
			        }
			    }
			    if(backoffFatal && totalBackedOff > 0) {
			        throw new SimulatorOverloadedException("Backed off nodes not expected in sequential simulation - CPU overload??");
			    }
			    if(checkOnly) return;
				long tDelta = (System.currentTimeMillis() - tStart)/1000;
				System.err.println("Waiting for nodes to be fully connected: "+countFullyConnected+" / "+nodes.length+" ("+totalConnections+" / "+totalPeers+" connections total partial "+totalPartialConnections+" compatible "+totalCompatibleConnections+") - backed off "+totalBackedOff+" ping min/avg/max "+(int)minPingTime+"/"+(int)avgPingTime+"/"+(int)maxPingTime+" at "+tDelta+'s');
				Logger.normal(RealNodeTester.class, "Waiting for nodes to be fully connected: "+countFullyConnected+" / "+nodes.length+" ("+totalConnections+" / "+totalPeers+" connections total partial "+totalPartialConnections+" compatible "+totalCompatibleConnections+") - backed off "+totalBackedOff+" ping min/avg/max "+(int)minPingTime+"/"+(int)avgPingTime+"/"+(int)maxPingTime+" at "+tDelta+'s');
				Thread.sleep(1000);
			}
		}
	}

}
