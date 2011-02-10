package cri.sanity;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.HashMap;
import java.util.Map;


public abstract class Task implements Runnable
{
	private static final int POOL_SIZE = 16;

	private static final Map<Integer,ScheduledFuture<?>> map = new HashMap<Integer,ScheduledFuture<?>>();
	private static ScheduledThreadPoolExecutor pool;
	private static int idCur = 0;

	//---- instance methods

	public final void exec() {
		if(pool == null) return;
		pool.execute(this);
	}
	public final void exec(long delay) {
		if(pool == null) return;
		pool.schedule(this, delay, TimeUnit.MILLISECONDS);
	}
	public final void exec(int id, long delay) {
		//synchronized(map) {
		final ScheduledFuture<?> sf = map.get(id);
		if(sf != null) sf.cancel(false);
		if(pool == null) return;
		map.put(id, pool.schedule(this, delay, TimeUnit.MILLISECONDS));
		//}
	}

	//---- static methods

	public static final void start() {
		if(pool != null) return;
		pool = new ScheduledThreadPoolExecutor(POOL_SIZE);
	}
	
	public static final int idCur() { return   idCur; }
	public static final int idNew() { return ++idCur; }

	public static final void stop() {
		if(pool == null) return;
		pool.shutdown();
		pool = null;
		map.clear();
	}
	public static final void stop(int id) {
		//synchronized(map) {
		final ScheduledFuture<?> sf = map.remove(id);
		if(sf != null) sf.cancel(false);
		//}
	}
	public static final void stop(int ... ids) {
		if(pool == null) return;
		for(int id : ids)
			stop(id);
	}

}
