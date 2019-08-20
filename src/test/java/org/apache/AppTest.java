package org.apache;

import static org.junit.Assert.assertTrue;

import org.apache.tomcat.jni.Time;
import org.junit.Test;

import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Unit test for simple App.
 */
public class AppTest 
{
    /**
     * Rigorous Test :-)
     */
    @Test
    public void shouldAnswerWithTrue()
    {
        assertTrue( true );
    }

    private  ReentrantLock lock = new ReentrantLock();
    /** Condition to wait on until tripped */
    private Condition condition = lock.newCondition();

    @Test
    public void fun1() throws InterruptedException {

        lock.lock();

        for(int i=0;i<100;i++) {
            final int num = i;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    lock.lock();
                    System.out.println(num);
                    lock.unlock();
                }
            }).start();
            TimeUnit.MILLISECONDS.sleep(20);
        }

        TimeUnit.SECONDS.sleep(5);
        System.out.println("start ...");
        lock.unlock();

        TimeUnit.DAYS.sleep(5);
    }
}
