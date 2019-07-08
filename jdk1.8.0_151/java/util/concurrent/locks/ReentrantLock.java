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
 * 一个可重入的Lock和监视器锁有着相同的基本表现，但是拓展了一些功能。
 *
 * 一个ReentrantLock被最后一个获取锁成功并且没有释放的的线程拥有。
 * 一个线程在自己拥有一个锁的情况下再调用同一个锁的lock方法会直接返回。
 * 这个可以通过方法{@link #isHeldByCurrentThread}和{@link #getHoldCount}检查。
 *
 * 构造函数提供了一个参数标识是否需要支持公平锁。如果支持公平的话(true)，锁的获取会有利于等待时间最长的线程（并不完全保证）。
 * 但是公平锁会影响程序的整体吞吐量。
 * 需要注意非阻塞的方法{@link #tryLock}并不保证公平。如果当前可以获取或，tryLock会直接成功，即使有其它线程在等待。
 *
 * 除了实现Lock接口中的方法，这个类还定义了一些方法检查Lock的状态。这些方法只是用来监控的。
 *
 * 反序列化的Lock会丢掉状态值，不管序列化时的状态是什么。
 *
 * 使用AQS的state表示是有线程拥有锁。0表示没有线程拥有，因为可以重入，每获取一次同步state加一，每释放一次state减一，
 * state可以用来表示线程重入次数。因此这个锁支持同一线程最多重入2147483647次，即int的最大值。
 */
public class ReentrantLock implements Lock, java.io.Serializable {
    private static final long serialVersionUID = 7373984872572414699L;
    /** Synchronizer providing all implementation mechanics */
    private final Sync sync;

    /**
     * 基于监视器锁策略的AQS同步器。子类有公平和非公平版本。使用AQS的状态值表示是否拥有锁。
     */
    abstract static class Sync extends AbstractQueuedSynchronizer {

        //子类自行实现是否需要公平。
        abstract void lock();

        /**
         * 非公平的获取锁.  tryAcquire在子类中实现
         */
        final boolean nonfairTryAcquire(int acquires) {
            final Thread current = Thread.currentThread();
            int c = getState();
            if (c == 0) {
                if (compareAndSetState(0, acquires)) {
                    setExclusiveOwnerThread(current);
                    return true;
                }
            }
            else if (current == getExclusiveOwnerThread()) {
                int nextc = c + acquires;
                if (nextc < 0) // overflow
                    throw new Error("Maximum lock count exceeded");
                setState(nextc);
                return true;
            }
            return false;
        }

        /**
         * 释放锁。因为可重入，所以需要判断状态是否归零，如果为0，则完全释放。
         */
        protected final boolean tryRelease(int releases) {
            int c = getState() - releases;
            if (Thread.currentThread() != getExclusiveOwnerThread())
                throw new IllegalMonitorStateException();
            boolean free = false;
            if (c == 0) {
                free = true;
                setExclusiveOwnerThread(null);
            }
            setState(c);
            return free;
        }

        protected final boolean isHeldExclusively() {
            // While we must in general read state before owner,
            // we don't need to do so to check if current thread is owner
            return getExclusiveOwnerThread() == Thread.currentThread();
        }

        final ConditionObject newCondition() {
            return new ConditionObject();
        }

        final Thread getOwner() {
            return getState() == 0 ? null : getExclusiveOwnerThread();
        }

        final int getHoldCount() {
            return isHeldExclusively() ? getState() : 0;
        }

        final boolean isLocked() {
            return getState() != 0;
        }

        // 反序列化
        private void readObject(java.io.ObjectInputStream s)
            throws java.io.IOException, ClassNotFoundException {
            s.defaultReadObject();
            setState(0); // reset to unlocked state
        }
    }

    /**
     * 非公平的AQS
     */
    static final class NonfairSync extends Sync {
        private static final long serialVersionUID = 7316153563782823691L;

        /**
         * 直接尝试获取锁，如果失败再调用循环阻塞的方法
         */
        final void lock() {
            if (compareAndSetState(0, 1))
                setExclusiveOwnerThread(Thread.currentThread());
            else
                acquire(1);
        }

        protected final boolean tryAcquire(int acquires) {
            return nonfairTryAcquire(acquires);
        }
    }

    /**
     * 公平的AQS
     */
    static final class FairSync extends Sync {
        private static final long serialVersionUID = -3000897897090466540L;

        // 直接调用循环阻塞的方法
        final void lock() {
            acquire(1);
        }

        /**
         * Fair version of tryAcquire.  Don't grant access unless
         * recursive call or no waiters or is first.
         * 公平版本。
         */
        protected final boolean tryAcquire(int acquires) {
            final Thread current = Thread.currentThread();
            int c = getState();
            // 状态为0，未上锁，CAS修改状态
            if (c == 0) {
                if (!hasQueuedPredecessors() &&
                    compareAndSetState(0, acquires)) {
                    setExclusiveOwnerThread(current);
                    return true;
                }
            }
            // 状态不为0，已上锁，判断是否为当前线程，如果是则state加1（如果未负说明超过int范围）
            // dyc 如果if的时候当前线程拥有锁，修改状态是已经释放，如何处理的
            else if (current == getExclusiveOwnerThread()) {
                int nextc = c + acquires;
                if (nextc < 0)
                    throw new Error("Maximum lock count exceeded");
                setState(nextc);
                return true;
            }
            return false;
        }
    }

    /**
     * 默认非公平
     */
    public ReentrantLock() {
        sync = new NonfairSync();
    }

    public ReentrantLock(boolean fair) {
        sync = fair ? new FairSync() : new NonfairSync();
    }

    /**
     * 尝试获取锁。
     *
     * 如果没有其他线程拥有锁并直接返回的或，设置锁获取次数为1。
     * 如果是当前线程已经拥有锁，则锁获取次数加1.
     * 如果锁被其他线程拥有，进入等待。
     */
    public void lock() {
        sync.lock();
    }

    /**
     * 尝试获取锁，并且支持线程中断。
     * dyc 如果一个线程重入锁多次，那么中断的状态是清零还是减一
     */
    public void lockInterruptibly() throws InterruptedException {
        sync.acquireInterruptibly(1);
    }

    /**
     * 尝试获取锁，无阻塞。
     * tryLock不保证公平，即使是AQS支持支持公平。如果想要公平，可以使用{@link #tryLock(0, TimeUnit.SECONDS)}
     */
    public boolean tryLock() {
        return sync.nonfairTryAcquire(1);
    }

    /**
     * 尝试获取锁，添加超时
     */
    public boolean tryLock(long timeout, TimeUnit unit) throws InterruptedException {
        return sync.tryAcquireNanos(1, unit.toNanos(timeout));
    }

    /**
     * 尝试释放当前锁。
     * 如果当前线程时锁的拥有这，线程的持有锁的次数会减一，如果减完之后次数为0，则这个锁释放。
     *
     * @throws IllegalMonitorStateException 当前线程不拥有这个锁的情况。
     */
    public void unlock() {
        sync.release(1);
    }

    public Condition newCondition() {
        return sync.newCondition();
    }

    /**
     * 查询当前线程获取这个锁并没有释放的次数。只用来调试和测试。
     * 例如 assert lock.getHoldCount() == 0;
     */
    public int getHoldCount() {
        return sync.getHoldCount();
    }

    /**
     * 查询锁是被当前线程拥有。
     *
     * 和监视锁中的{@link Thread#holdsLock(Object)}方法类似。这个方法只用来调试或测试。
     * 示例代码 assert lock.isHeldByCurrentThread();
     * 也可以用来保证一个重入锁不能重入，例如 assert !lock.isHeldByCurrentThread();
     */
    public boolean isHeldByCurrentThread() {
        return sync.isHeldExclusively();
    }

    /**
     * 返回当前是否有线程拥有锁。
     */
    public boolean isLocked() {
        return sync.isLocked();
    }

    /**
     * 返回是否是公平锁。
     */
    public final boolean isFair() {
        return sync instanceof FairSync;
    }

    protected Thread getOwner() {
        return sync.getOwner();
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

    public String toString() {
        Thread o = sync.getOwner();
        return super.toString() + ((o == null) ?
                                   "[Unlocked]" :
                                   "[Locked by thread " + o.getName() + "]");
    }
}
