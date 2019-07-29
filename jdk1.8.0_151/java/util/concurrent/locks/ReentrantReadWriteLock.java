/*
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

/*
 *
 *
 *
 *
 *
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package java.util.concurrent.locks;
import java.util.concurrent.TimeUnit;
import java.util.Collection;

/**
 * 可重入的读写锁实现。
 * 这个类有以下属性：
 * 1. 获取锁的顺序
 *
 * 非公平锁模式：
 * 这个类不会强加332读写的优先顺序。然而，它确实支持可选择的公平模式。
 * 当构造为非公平（默认）时，读取和写入锁定的输入顺序是未指定的，受重入约束的限制。
 * 一个非公平锁会持续不断的竞争，可能无限期的延期一个或多少个读或写线程，但是通常比公平锁有更大的吞吐量。
 *
 * 公平锁模式：
 * <1> 当构造公平锁时，线程近似地根据到顺序策略来竞争。在当前拥有锁的线程释放时，或者是等待时间最长的写线程获取写锁，
 * 或者是一组读线程等待时间比所有写线程，这组读线程会获取读锁。
 *
 * <2> 当写锁被拥有或有一个正在等待的写锁时，读锁（不含重入）会阻塞。读操作的线程会等旧的写线程全部释放之后才会获取锁。
 * 当然，如果写操作放弃了等待，只剩下旧的读操作时，不再有写锁时，这些读操作线程也会获取锁。
 *
 * <3> 一个写操作如果要获取公平写锁（非重入），在所有读锁和写锁都释放时之前（意味着没有等待线程）会阻塞。
 *
 * 2. 重入
 * 这个锁允许读锁和写锁重入。在所有写锁释放前，非重入读锁不能获取。
 *
 * 另外，一个写锁可以获取读锁，但是反着不行。在其他程序中，在持有写锁的时候调用或回调需要读锁的方法时，重入会很有用。
 * 如果一个读锁线程获取写锁永远不会成功。
 *
 * 3. 锁降级
 * 重入同时也支持了从写锁降级到读锁，获取写锁之后，获取读锁，再释放写锁，就成功降级为读锁。然而从读锁升级到写锁是不可能的。
 *
 * 4. 读写锁都支持线程中断
 *
 * 5. 写锁支持条件锁，读锁不支持会抛出异常
 */
public class ReentrantReadWriteLock implements ReadWriteLock, java.io.Serializable {

    private final ReentrantReadWriteLock.ReadLock readerLock;

    private final ReentrantReadWriteLock.WriteLock writerLock;

    final Sync sync;

    // 默认非公平
    public ReentrantReadWriteLock() {
        this(false);
    }

    public ReentrantReadWriteLock(boolean fair) {
        sync = fair ? new FairSync() : new NonfairSync();
        readerLock = new ReadLock(this);
        writerLock = new WriteLock(this);
    }

    public ReentrantReadWriteLock.WriteLock writeLock() { return writerLock; }
    public ReentrantReadWriteLock.ReadLock  readLock()  { return readerLock; }

    /**
     * 同步器实现. 读锁和写锁使用了同一个AQS来实现。
     *
     * 将AQS的state的字节按位截取分成了两部分，低位的代表写锁独占的获取次数，高位代表读写锁共享的获取次数
     * 0000 0000 0000 0000 | 0000 0000 0000 0001 | 0000 0000 0000 0000 | 0000 0000 0000 0000
     * |-----------------读取state----------------|-------------------写state----------------|
     *
     *
     * 独占式方法tryRelease和tryAcquire等方法用于写锁
     * 共享式方法tryReleaseShard和tryAcquireShared方法用于读锁
     */
    abstract static class Sync extends AbstractQueuedSynchronizer {

        /*
         * 用来获取读写锁次数的常量和方法。
         */
        static final int SHARED_SHIFT   = 16;
        //0000 0000 0000 0000 | 0000 0000 0000 0001 | 0000 0000 0000 0000 | 0000 0000 0000 0000
        static final int SHARED_UNIT    = (1 << SHARED_SHIFT);
        //0000 0000 0000 0000 | 0000 0000 0000 0000 | 1111 1111 1111 1111 | 1111 1111 1111 1111
        static final int MAX_COUNT      = (1 << SHARED_SHIFT) - 1;
        //0000 0000 0000 0000 | 0000 0000 0000 0000 | 1111 1111 1111 1111 | 1111 1111 1111 1111
        static final int EXCLUSIVE_MASK = (1 << SHARED_SHIFT) - 1;

        // 右移16位，将低位16位抛弃，获取读锁获取次数
        static int sharedCount(int c)    { return c >>> SHARED_SHIFT; }
        // 将高位16位置0，获取写锁获取次数
        static int exclusiveCount(int c) { return c & EXCLUSIVE_MASK; }

        /**
         * 记录每一个线程读锁获取的次数。
         * 使用ThreadLocal记录，可以查询当前线程的持有数量。
         */
        static final class HoldCounter {
            int count = 0;
            // 使用id，而不是线程引用，以避免垃圾无法回收（避免和ThreadLocal互相绑定）
            // 如果使用Thread的话  ThreadLocal -> HoldCounter -> Thread
            final long tid = getThreadId(Thread.currentThread());
        }
        static final class ThreadLocalHoldCounter  extends ThreadLocal<HoldCounter> {
            public HoldCounter initialValue() {
                return new HoldCounter();
            }
        }

        /**
         * 当前线程读锁重入次数。在构造和读取是初始化，在次数降为0是去掉。
         */
        private transient ThreadLocalHoldCounter readHolds;

        /**
         * 最后一个成功获取读锁的线程的id和锁获取数量。用于缓存。
         * 为了快捷，可以直接判断当前线程是不是最后一个读锁线程，如果是可以直接拿到数量，
         * 虽然可以通过ThreadLocal中获取，但是速度慢
         *
         */
        private transient HoldCounter cachedHoldCounter;

        /**
         * 第一个获取读锁的线程和它获取读锁的次数
         *
         * 准确的说，firstReader是最后一个将共享计数从0改为1的唯一线程，并且读锁还没有释放，没有的话为null
         *
         * 不会导致线程参数无法垃圾回收，除非线程中断没有正常释放锁，而tryReleaseShared已经将它设置为null
         */
        private transient Thread firstReader = null;
        private transient int firstReaderHoldCount;

        Sync() {
            readHolds = new ThreadLocalHoldCounter();
            setState(getState()); // ensures visibility of readHolds
        }

        /*
         * 公平模式和非公平模式的获取锁和释放锁使用相同的代码，区别在于当等待队列非空时，
         * 对于直接插入（barging）的行为是否允许。
         */

        /**
         * 读锁是否需要阻塞
         */
        abstract boolean readerShouldBlock();

        /**
         * 写锁是否需要阻塞
         */
        abstract boolean writerShouldBlock();

        /*
         * Note that tryRelease and tryAcquire can be called by
         * Conditions. So it is possible that their arguments contain
         * both read and write holds that are all released during a
         * condition wait and re-established in tryAcquire.
         * 注意tryRelease和tryAcquire可以被条件锁调用。
         *
         */

        protected final boolean tryRelease(int releases) {
            if (!isHeldExclusively()) // 判断当前线程是否拥有锁
                throw new IllegalMonitorStateException();
            int nextc = getState() - releases;
            boolean free = exclusiveCount(nextc) == 0;
            if (free) // 判断是否需要完全释放
                setExclusiveOwnerThread(null);
            setState(nextc); // 修改状态
            return free;
        }

        /**
         * 获取读锁。
         * 如果有非零读锁和非零写锁数量并且写锁非当前线程拥有，失败。
         * 如果数字溢出，失败
         * 否则，如果这个线程是重入或者队列策略允许的话，成功。
         */
        protected final boolean tryAcquire(int acquires) {
            Thread current = Thread.currentThread();
            int c = getState();
            int w = exclusiveCount(c);
            if (c != 0) {  // 读写锁至少有一种
                // 写锁为0，则说明有读锁，失败。如果不为0，判断是否是重入
                if (w == 0 || current != getExclusiveOwnerThread())
                    return false;
                // 重入
                if (w + exclusiveCount(acquires) > MAX_COUNT)
                    throw new Error("Maximum lock count exceeded");
                setState(c + acquires);
                return true;
            }
            if (writerShouldBlock() ||
                !compareAndSetState(c, c + acquires))
                return false;
            setExclusiveOwnerThread(current);
            return true;
        }

        /**
         * 读锁释放。
         * 这里利用了firstReader和firstReaderHoldCount的缓存。
         */
        protected final boolean tryReleaseShared(int unused) {
            Thread current = Thread.currentThread();
            // 找到存当前线程获取读锁次数的地方，减一
            if (firstReader == current) { // 第一个，缓存
                // assert firstReaderHoldCount > 0;
                if (firstReaderHoldCount == 1)
                    firstReader = null;
                else
                    firstReaderHoldCount--;
            } else { // 非第一个，在最后一个和ThreadLocal中找
                HoldCounter rh = cachedHoldCounter;
                if (rh == null || rh.tid != getThreadId(current))
                    rh = readHolds.get();
                int count = rh.count;
                if (count <= 1) {
                    readHolds.remove();
                    if (count <= 0)
                        throw unmatchedUnlockException();
                }
                --rh.count;
            }
            // 循环CAS替换状态
            for (;;) {
                int c = getState();
                int nextc = c - SHARED_UNIT;
                if (compareAndSetState(c, nextc))
                    // 注意这里，即使CAS成功了，但是如果没有完全释放的话返回false，方法在队列中的等待线程可以执行的时候返回true
                    return nextc == 0;
            }
        }

        private IllegalMonitorStateException unmatchedUnlockException() {
            return new IllegalMonitorStateException(
                "attempt to unlock read lock, not locked by current thread");
        }

        /**
         * 获取读锁
         * 1. 如果有其它线程持有写锁，失败
         * 2. 根据队列策略检查是否需要阻塞。
         * 3. 如果步骤2因为线程不合适或者cas失败则使用完整版循环CAS
         */
        protected final int tryAcquireShared(int unused) {
            Thread current = Thread.currentThread();
            int c = getState();
            if (exclusiveCount(c) != 0 &&
                getExclusiveOwnerThread() != current)
                return -1;
            int r = sharedCount(c);
            if (!readerShouldBlock() &&
                r < MAX_COUNT &&
                compareAndSetState(c, c + SHARED_UNIT)) {
                if (r == 0) {
                    firstReader = current;
                    firstReaderHoldCount = 1;
                } else if (firstReader == current) {
                    firstReaderHoldCount++;
                } else {
                    HoldCounter rh = cachedHoldCounter;
                    if (rh == null || rh.tid != getThreadId(current))
                        cachedHoldCounter = rh = readHolds.get();
                    else if (rh.count == 0)
                        readHolds.set(rh);
                    rh.count++;
                }
                return 1;
            }
            return fullTryAcquireShared(current);
        }

        /**
         * 完整版循环CAS获取读锁
         */
        final int fullTryAcquireShared(Thread current) {
            /*
             * This code is in part redundant with that in
             * tryAcquireShared but is simpler overall by not
             * complicating tryAcquireShared with interactions between
             * retries and lazily reading hold counts.
             */
            HoldCounter rh = null;
            for (;;) {
                int c = getState();
                if (exclusiveCount(c) != 0) {
                    if (getExclusiveOwnerThread() != current)
                        return -1;
                    // else we hold the exclusive lock; blocking here
                    // would cause deadlock.
                } else if (readerShouldBlock()) {
                    // Make sure we're not acquiring read lock reentrantly
                    if (firstReader == current) {
                        // assert firstReaderHoldCount > 0;
                    } else {
                        if (rh == null) {
                            rh = cachedHoldCounter;
                            if (rh == null || rh.tid != getThreadId(current)) {
                                rh = readHolds.get();
                                if (rh.count == 0)
                                    readHolds.remove();
                            }
                        }
                        if (rh.count == 0)
                            return -1;
                    }
                }
                if (sharedCount(c) == MAX_COUNT)
                    throw new Error("Maximum lock count exceeded");
                if (compareAndSetState(c, c + SHARED_UNIT)) {
                    if (sharedCount(c) == 0) {
                        firstReader = current;
                        firstReaderHoldCount = 1;
                    } else if (firstReader == current) {
                        firstReaderHoldCount++;
                    } else {
                        if (rh == null)
                            rh = cachedHoldCounter;
                        if (rh == null || rh.tid != getThreadId(current))
                            rh = readHolds.get();
                        else if (rh.count == 0)
                            readHolds.set(rh);
                        rh.count++;
                        cachedHoldCounter = rh; // cache for release
                    }
                    return 1;
                }
            }
        }

        /**
         * Performs tryLock for write, enabling barging in both modes.
         * This is identical in effect to tryAcquire except for lack
         * of calls to writerShouldBlock.
         */
        final boolean tryWriteLock() {
            Thread current = Thread.currentThread();
            int c = getState();
            if (c != 0) { // 有读线程或写线程
                int w = exclusiveCount(c); // 获取写状态
                if (w == 0 // 有读线程
                        || current != getExclusiveOwnerThread()) // 有写线程不是当前线程
                    return false;
                if (w == MAX_COUNT)
                    throw new Error("Maximum lock count exceeded");
            }
            if (!compareAndSetState(c, c + 1)) // CAS获取写状态
                return false;
            setExclusiveOwnerThread(current); // CAS成功设置线程拥有锁
            return true;
        }

        /**
         * Performs tryLock for read, enabling barging in both modes.
         * This is identical in effect to tryAcquireShared except for
         * lack of calls to readerShouldBlock.
         */
        final boolean tryReadLock() {
            Thread current = Thread.currentThread();
            for (;;) {
                int c = getState();
                // 判断是不是写锁线程获取读锁
                if (exclusiveCount(c) != 0 && getExclusiveOwnerThread() != current)
                    return false;
                int r = sharedCount(c);
                if (r == MAX_COUNT)
                    throw new Error("Maximum lock count exceeded");
                // CAS 读锁次数加一
                if (compareAndSetState(c, c + SHARED_UNIT)) {
                    if (r == 0) { // 当前没有读锁，从0变成1，设置第一个读线程
                        firstReader = current;
                        firstReaderHoldCount = 1;
                    } else if (firstReader == current) { //第一个读线程时当前线程
                        firstReaderHoldCount++;
                    } else { // 已有其他读线程 缓存当前线程读锁次数
                        HoldCounter rh = cachedHoldCounter;
                        if (rh == null || rh.tid != getThreadId(current))
                            cachedHoldCounter = rh = readHolds.get();
                        else if (rh.count == 0)
                            readHolds.set(rh);
                        rh.count++;
                    }
                    return true;
                }
            }
        }

        protected final boolean isHeldExclusively() {
            // While we must in general read state before owner,
            // we don't need to do so to check if current thread is owner
            return getExclusiveOwnerThread() == Thread.currentThread();
        }

        // Methods relayed to outer class

        final ConditionObject newCondition() {
            return new ConditionObject();
        }

        final Thread getOwner() {
            // dyc 必须先获取状态在调用查询owner的方法，以保证内存一致性
            return ((exclusiveCount(getState()) == 0) ? null : getExclusiveOwnerThread());
        }

        final int getReadLockCount() {
            return sharedCount(getState());
        }

        final boolean isWriteLocked() {
            return exclusiveCount(getState()) != 0;
        }

        final int getWriteHoldCount() {
            return isHeldExclusively() ? exclusiveCount(getState()) : 0;
        }

        /**
         * 查询当前线程获取读锁的次数
         */
        final int getReadHoldCount() {
            if (getReadLockCount() == 0)
                return 0;

            Thread current = Thread.currentThread();
            if (firstReader == current)
                return firstReaderHoldCount;

            HoldCounter rh = cachedHoldCounter;
            if (rh != null && rh.tid == getThreadId(current))
                return rh.count;

            int count = readHolds.get().count;
            if (count == 0) readHolds.remove();
            return count;
        }

        /**
         * 反序列化
         */
        private void readObject(java.io.ObjectInputStream s) throws java.io.IOException, ClassNotFoundException {
            s.defaultReadObject();
            readHolds = new ThreadLocalHoldCounter();
            setState(0); // reset to unlocked state
        }

        final int getCount() { return getState(); }
    }

    /**
     * Nonfair version of Sync
     */
    static final class NonfairSync extends Sync {
        private static final long serialVersionUID = -8159625535654395037L;
        final boolean writerShouldBlock() {
            return false; // writers can always barge
        }
        final boolean readerShouldBlock() {
            /* As a heuristic to avoid indefinite writer starvation,
             * block if the thread that momentarily appears to be head
             * of queue, if one exists, is a waiting writer.  This is
             * only a probabilistic effect since a new reader will not
             * block if there is a waiting writer behind other enabled
             * readers that have not yet drained from the queue.
             */
            return apparentlyFirstQueuedIsExclusive();
        }
    }

    /**
     * Fair version of Sync
     */
    static final class FairSync extends Sync {
        private static final long serialVersionUID = -2274990926593161451L;
        final boolean writerShouldBlock() {
            return hasQueuedPredecessors();
        }
        final boolean readerShouldBlock() {
            return hasQueuedPredecessors();
        }
    }

    /**
     * The lock returned by method {@link ReentrantReadWriteLock#readLock}.
     */
    public static class ReadLock implements Lock, java.io.Serializable {
        private final Sync sync;

        protected ReadLock(ReentrantReadWriteLock lock) {
            sync = lock.sync;
        }

        public void lock() {
            sync.acquireShared(1);
        }

        public void lockInterruptibly() throws InterruptedException {
            sync.acquireSharedInterruptibly(1);
        }

        public boolean tryLock() {
            return sync.tryReadLock();
        }

        public boolean tryLock(long timeout, TimeUnit unit)
                throws InterruptedException {
            return sync.tryAcquireSharedNanos(1, unit.toNanos(timeout));
        }

        public void unlock() {
            sync.releaseShared(1);
        }

        public Condition newCondition() {
            throw new UnsupportedOperationException();
        }

        public String toString() {
            int r = sync.getReadLockCount();
            return super.toString() +
                "[Read locks = " + r + "]";
        }
    }

    /**
     * The lock returned by method {@link ReentrantReadWriteLock#writeLock}.
     */
    public static class WriteLock implements Lock, java.io.Serializable {
        private final Sync sync;
        protected WriteLock(ReentrantReadWriteLock lock) {
            sync = lock.sync;
        }
        // 获取同步器的独占锁
        public void lock() {
            sync.acquire(1);
        }

        public void lockInterruptibly() throws InterruptedException {
            sync.acquireInterruptibly(1);
        }

        public boolean tryLock( ) {
            return sync.tryWriteLock();
        }

        public boolean tryLock(long timeout, TimeUnit unit)
                throws InterruptedException {
            return sync.tryAcquireNanos(1, unit.toNanos(timeout));
        }

        // 释放同步器的独占锁
        public void unlock() {
            sync.release(1);
        }

        // 新的条件锁
        public Condition newCondition() {
            return sync.newCondition();
        }

        public String toString() {
            Thread o = sync.getOwner();
            return super.toString() + ((o == null) ?
                                       "[Unlocked]" :
                                       "[Locked by thread " + o.getName() + "]");
        }

        // 查询当前线程是否拥有写锁
        public boolean isHeldByCurrentThread() {
            return sync.isHeldExclusively();
        }

        // 查询写锁占用次数
        public int getHoldCount() {
            return sync.getWriteHoldCount();
        }
    }

    // Instrumentation and status

    public final boolean isFair() {
        return sync instanceof FairSync;
    }

    protected Thread getOwner() {
        return sync.getOwner();
    }

    public int getReadLockCount() {
        return sync.getReadLockCount();
    }

    public boolean isWriteLocked() {
        return sync.isWriteLocked();
    }

    public boolean isWriteLockedByCurrentThread() {
        return sync.isHeldExclusively();
    }

    public int getWriteHoldCount() {
        return sync.getWriteHoldCount();
    }

    public int getReadHoldCount() {
        return sync.getReadHoldCount();
    }

    protected Collection<Thread> getQueuedWriterThreads() {
        return sync.getExclusiveQueuedThreads();
    }

    protected Collection<Thread> getQueuedReaderThreads() {
        return sync.getSharedQueuedThreads();
    }

    public final boolean hasQueuedThreads() {
        return sync.hasQueuedThreads();
    }

    public final boolean hasQueuedThread(Thread thread) {
        return sync.isQueued(thread);
    }

    public final int getQueueLength() {
        return sync.getQueueLength();
    }

    protected Collection<Thread> getQueuedThreads() {
        return sync.getQueuedThreads();
    }

    public boolean hasWaiters(Condition condition) {
        if (condition == null)
            throw new NullPointerException();
        if (!(condition instanceof AbstractQueuedSynchronizer.ConditionObject))
            throw new IllegalArgumentException("not owner");
        return sync.hasWaiters((AbstractQueuedSynchronizer.ConditionObject)condition);
    }

    public int getWaitQueueLength(Condition condition) {
        if (condition == null)
            throw new NullPointerException();
        if (!(condition instanceof AbstractQueuedSynchronizer.ConditionObject))
            throw new IllegalArgumentException("not owner");
        return sync.getWaitQueueLength((AbstractQueuedSynchronizer.ConditionObject)condition);
    }

    protected Collection<Thread> getWaitingThreads(Condition condition) {
        if (condition == null)
            throw new NullPointerException();
        if (!(condition instanceof AbstractQueuedSynchronizer.ConditionObject))
            throw new IllegalArgumentException("not owner");
        return sync.getWaitingThreads((AbstractQueuedSynchronizer.ConditionObject)condition);
    }

    /**
     * Returns a string identifying this lock, as well as its lock state.
     * The state, in brackets, includes the String {@code "Write locks ="}
     * followed by the number of reentrantly held write locks, and the
     * String {@code "Read locks ="} followed by the number of held
     * read locks.
     *
     * @return a string identifying this lock, as well as its lock state
     */
    public String toString() {
        int c = sync.getCount();
        int w = Sync.exclusiveCount(c);
        int r = Sync.sharedCount(c);

        return super.toString() +
            "[Write locks = " + w + ", Read locks = " + r + "]";
    }

    /**
     * dyc 获取线程id为什么需要从底层获取
     * Returns the thread id for the given thread.  We must access
     * this directly rather than via method Thread.getId() because
     * getId() is not final, and has been known to be overridden in
     * ways that do not preserve unique mappings.
     */
    static final long getThreadId(Thread thread) {
        return UNSAFE.getLongVolatile(thread, TID_OFFSET);
    }

    // Unsafe mechanics
    private static final sun.misc.Unsafe UNSAFE;
    private static final long TID_OFFSET;
    static {
        try {
            UNSAFE = sun.misc.Unsafe.getUnsafe();
            Class<?> tk = Thread.class;
            TID_OFFSET = UNSAFE.objectFieldOffset
                (tk.getDeclaredField("tid"));
        } catch (Exception e) {
            throw new Error(e);
        }
    }

}
