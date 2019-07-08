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

/**
 * 锁实现提供了比使用同步方法和语句获得的更广泛的锁操作。
 * 它们允许更灵活的结构，可能具有完全不同的属性，并且可以支持多个关联的Condition对象。
 *
 * 锁是用来控制多个线程对共享资源访问的工具。通常来说，在同一时间只有一个线程可以获取锁，可以访问共享资源。
 * 然而也有一些锁支持并发访问共享资源，例如{@link ReadWriteLock}
 *
 * 使用synchronized方法或语句可以访问与每个对象关联的隐式监视器锁，但是要求必须要在synchronized方法或语句中使用。
 * 如果需要获取多个锁，那么必须要以和获取相反的顺序释放锁，并且所有锁的释放要和获取锁在相同的语句范围内。
 *
 * 虽然同步方法和公布代码块的作用域机制使得使用监视器锁进行编程变得更加容易，
 * 并且有助于避免许多涉及锁的常见编程错误，但是在某些情况下使用Lock会更加灵活。
 * 在使用Lock的同时会失去使用synchronized的自动释放锁的遍历，需要以以下类似代码手动释放：
 *   Lock l = ...;
 *   l.lock();
 *   try {
 *      // access the resource protected by this lock
 *   } finally {
 *      l.unlock();
 *  }
 * 当锁的获取和释放在不懂的范围时，需要注意要确保在获取锁之后执行的代码收try-finally或try-catch保护，来保证锁的正常释放。
 *
 * Lock实现了synchronized额外的方法，tryLock和lockInterruptibly，增加了对超时和线程中断的支持。
 * 同时Lock也可以提供隐式监视器锁没有的行为和语义，例如保证顺序，不可重入，发现死锁。如果Lock的实现有以上语义，要在文档上标明。
 *
 * 需要注意Lock的实例对象是一个正常的对象，也可以被当做监视器锁的目标。获取这个对象的监视器锁和这个对象的lock方法没有任何关系，
 * 为避免混淆，不建议这样写，除非是在这个对象内部。
 *
 * 实现建议：
 * 获取锁的三种形式，默认，支持中断，支持超时可能会有不同的特性，例如顺序保证，或者可能特定的Lock中不支持正在进行的锁中断。
 * 因此，Lock的实现不要求对三种形式做同样的保证和语义。但是每个方法需要有清晰的文档记录这些特征，同时保证对接口中对中断的定义。
 * <h3>Implementation Considerations</h3>
 *
 * @see ReentrantLock
 * @see Condition
 * @see ReadWriteLock
 *
 * @since 1.5
 * @author Doug Lea
 */
public interface Lock {

    /**
     * 尝试获取锁。
     * 如果锁不可用，当先线程将被禁用来进行线程调度，并且在获取到锁之前处于休眠状态。
     * 一个实现需要能发现错误的使用方法，如果在某种场景下调用方法会造成死锁，应该抛出一个异常。异常和场景需要在文档中说明。
     *
     */
    void lock();

    /**
     * 在{@link #lock()}的基础之上，添加了对线程中断的支持。
     *
     * @throws InterruptedException 在获取锁的过程中线程被中断时抛出
     */
    void lockInterruptibly() throws InterruptedException;

    /**
     * 尝试获取锁，成功返回true，在没有获取的锁的情况下，不会阻塞线程，而是返回false。
     * 使用方式：
     *  Lock lock = ...;
     *  if (lock.tryLock()) {
     *    try {
     *      // manipulate protected state
     *    } finally {
     *      lock.unlock();
     *    }
     *  } else {
     *    // perform alternative actions
     *  }
     * @return
     */
    boolean tryLock();

    /**
     * 在{@link #tryLock}的基础上增加了超时支持，支持线程中断。
     * @param time 如果小于等于0，并不会等待
     * @param unit
     * @return
     * @throws InterruptedException
     */
    boolean tryLock(long time, TimeUnit unit) throws InterruptedException;

    /**
     * 释放锁
     * 实现类应该加强约束，只有获取到锁的线程才可以释放锁
     */
    void unlock();

    /**
     * 返回一个绑定到当前锁上的条件锁。
     * 在获取条件之前，线程必须先获取到当前锁。锁会在{@link Condition#await()}的之前自动释放掉，在从await中醒来之前自动获取到锁。
     * @return
     */
    Condition newCondition();
}
