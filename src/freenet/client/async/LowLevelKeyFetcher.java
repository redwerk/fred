package freenet.client.async;

import java.util.ArrayList;
import java.util.HashSet;

import freenet.crypt.RandomSource;
import freenet.keys.Key;
import freenet.node.BaseSendableGet;
import freenet.node.KeysFetchingLocally;
import freenet.node.LowLevelGetException;
import freenet.node.NodeClientCore;
import freenet.node.RequestClient;
import freenet.node.RequestCompletionListener;
import freenet.node.RequestScheduler;
import freenet.node.SendableRequestItem;
import freenet.node.SendableRequestItemKey;
import freenet.node.SendableRequestSender;
import freenet.support.ListUtils;
import freenet.support.Logger;

public abstract class LowLevelKeyFetcher extends BaseSendableGet implements RequestClient {

    protected final HashSet<Key> keys;
    protected final ArrayList<Key> keysList;
    protected static volatile boolean logMINOR;
    protected static volatile boolean logDEBUG;
    protected final RandomSource random;
    protected final short priorityClass;
    protected final boolean isSSK;

    protected static class MySendableRequestItem implements SendableRequestItem, SendableRequestItemKey {
    		final Key key;
    		MySendableRequestItem(Key key) {
    			this.key = key;
    		}
    		@Override
    		public void dump() {
    			// Ignore, we will be GC'ed
    		}
    		@Override
    		public SendableRequestItemKey getKey() {
    			return this;
    		}
    	}

    /** Called when a key is found, when it no longer belongs to this list etc. */
    public synchronized void remove(Key key) {
    	assert(keysList.size() == keys.size());
    	if(keys.remove(key)) {
    		ListUtils.removeBySwapLast(keysList, key);
    		if(logMINOR) Logger.minor(this, "Found "+key+" , removing it "+" for "+this+" size now "+keysList.size());
    	}
    	assert(keysList.size() == keys.size());
    }

    public synchronized boolean isEmpty() {
    	return keys.isEmpty();
    }

    @Override
    public long countAllKeys(ClientContext context) {
    	// Not supported.
    	throw new UnsupportedOperationException();
    }

    @Override
    public long countSendableKeys(ClientContext context) {
    	// Not supported.
    	throw new UnsupportedOperationException();
    }

    public LowLevelKeyFetcher(NodeClientCore core, RandomSource random, short priorityClass, boolean isSSK, boolean realTimeFlag) {
        super(false, realTimeFlag);
        this.keys = new HashSet<Key>();
        this.keysList = new ArrayList<Key>();
        this.random = random;
        this.priorityClass = priorityClass;
        this.isSSK = isSSK;
    }

    @Override
    public synchronized SendableRequestItem chooseKey(KeysFetchingLocally fetching, ClientContext context) {
    	assert(keysList.size() == keys.size());
    	if(keys.size() == 1) {
    		// Shortcut the common case
    		Key k = keysList.get(0);
    		if(fetching.hasKey(k, null)) return null;
    		// Ignore RecentlyFailed because an offered key overrides it.
    		keys.remove(k);
    		keysList.remove(0);
    		keysList.trimToSize();
    		return new MySendableRequestItem(k);
    	}
    	for(int i=0;i<10;i++) {
    		// Pick a random key
    		if(keysList.isEmpty()) return null;
    		int ptr = random.nextInt(keysList.size());
    		// Avoid shuffling penalty by swapping the chosen element with the end.
    		Key k = keysList.get(ptr);
    		if(fetching.hasKey(k, null)) continue;
    		// Ignore RecentlyFailed because an offered key overrides it.
    		ListUtils.removeBySwapLast(keysList, ptr);
    		keys.remove(k);
    		assert(keysList.size() == keys.size());
    		return new MySendableRequestItem(k);
    	}
    	return null;
    }

    @Override
    public RequestClient getClient() {
    	return this;
    }

    @Override
    public short getPriorityClass() {
    	return priorityClass;
    }

    @Override
    public void internalError(Throwable t, RequestScheduler sched, ClientContext context, boolean persistent) {
    	Logger.error(this, "Internal error: "+t, t);
    }

    @Override
    public SendableRequestSender getSender(ClientContext context) {
    	return new SendableRequestSender() {
    
    		@Override
    		public boolean send(NodeClientCore core, final RequestScheduler sched, ClientContext context, ChosenBlock req) {
    			final Key key = ((MySendableRequestItem) req.token).key;
    			// Have to cache it in order to propagate it; FIXME
    			// Don't let a node force us to start a real request for a specific key.
    			// We check the datastore, take up offers if any (on a short timeout), and then quit if we still haven't fetched the data.
    			// Obviously this may have a marginal impact on load but it should only be marginal.
    			core.asyncGet(key, offersOnly(), new RequestCompletionListener() {
    
    				@Override
    				public void onSucceeded() {
    				    requestSucceeded(key, sched);
    				}
    
                    @Override
    				public void onFailed(LowLevelGetException e) {
                        requestFailed(key, sched, e);
    				}

    			}, true, false, realTimeFlag, false, false);
    			// FIXME reconsider canWriteClientCache=false parameter.
    			return true;
    		}
    
    		@Override
    		public boolean sendIsBlocking() {
    			return false;
    		}
    		
    	};
    }
    
    /** @return False to do normal requests, true to only query nodes which have offered the key 
     * already, as in OfferedKeyList. */
    protected abstract boolean offersOnly();

    protected abstract void requestSucceeded(Key key, RequestScheduler sched);
    
    protected abstract void requestFailed(Key key, RequestScheduler sched, LowLevelGetException e);

    @Override
    public boolean isCancelled() {
    	return false;
    }

    public synchronized void queueKey(Key key) {
    	assert(keysList.size() == keys.size());
    	if(keys.add(key)) {
    		keysList.add(key);
    		if(logMINOR) Logger.minor(this, "Queued key "+key+" on "+this);
    	}
    	assert(keysList.size() == keys.size());
    }

    @Override
    public Key getNodeKey(SendableRequestItem token) {
    	return ((MySendableRequestItem) token).key;
    }

    @Override
    public boolean isSSK() {
    	return isSSK;
    }

    @Override
    public boolean isInsert() {
    	return false;
    }

    @Override
    public ClientRequestScheduler getScheduler(ClientContext context) {
    	if(isSSK)
    		return context.getSskFetchScheduler(realTimeFlag);
    	else
    		return context.getChkFetchScheduler(realTimeFlag);
    }

    @Override
    public boolean preRegister(ClientContext context, boolean toNetwork) {
    	// Ignore
    	return false;
    }

    @Override
    public long getWakeupTime(ClientContext context, long now) {
    	if(isEmpty()) {
    	    return Long.MAX_VALUE;
    	}
    	return 0;
    }

}