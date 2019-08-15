/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.tomcat.util.net;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.Channel;
import java.nio.channels.FileChannel;
import java.nio.channels.NetworkChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.IntrospectionUtils;
import org.apache.tomcat.util.collections.SynchronizedQueue;
import org.apache.tomcat.util.collections.SynchronizedStack;
import org.apache.tomcat.util.net.AbstractEndpoint.Handler.SocketState;
import org.apache.tomcat.util.net.jsse.JSSESupport;

/**
 * NIO tailored thread pool, providing the following services:
 * <ul>
 * <li>Socket acceptor thread</li>
 * <li>Socket poller thread</li>
 * <li>Worker threads pool</li>
 * </ul>
 *
 * When switching to Java 5, there's an opportunity to use the virtual
 * machine's thread pool.
 *
 * @author Mladen Turk
 * @author Remy Maucherat
 *
 * otz:
 *  目前可以确认，NioEndpoint 其实就是 socket 连接的入口，类似 netty 中的 NioEventLoop
 *  而在 spring-boot-web 项目中，默认也是这个实现类，就是 NIO 线程模型
 *
 *  NioEndpoint 核心组件
 *      LimitLatch      负责连接数的并发控制
 *      {@link Acceptor}        负责接收套接字 socket 连接并注册到通道队列中
 *      Poller          负责轮序检查事件列表
 *      Poller池        包含了若干个 Poller 组件
 *      SocketProcessor 任务定义器
 *      Executor        负责处理套接字 sorcket 线程池
 *
 *   组件之间的工作流程图参考 resources/doc/NioEndpoint核心组件间的工作流程.png
 *
 *
 *
 *
 */
@SuppressWarnings("all")
public class NioEndpoint extends AbstractJsseEndpoint<NioChannel> {


    // -------------------------------------------------------------- Constants


    private static final Log log = LogFactory.getLog(NioEndpoint.class);


    public static final int OP_REGISTER = 0x100; //register interest op

    // ----------------------------------------------------------------- Fields

    private NioSelectorPool selectorPool = new NioSelectorPool();

    /**
     * Server socket "pointer".
     */
    private volatile ServerSocketChannel serverSock = null;

    /**
     *
     */
    private volatile CountDownLatch stopLatch = null;

    /**
     * Cache for poller events
     */
    private SynchronizedStack<PollerEvent> eventCache;

    /**
     * Bytebuffer cache, each channel holds a set of buffers (two, except for SSL holds four)
     * otz:
     *  NioChannel 对象缓存池
     *
     *  回收方法是 {@link #close}
     */
    private SynchronizedStack<NioChannel> nioChannels;


    // ------------------------------------------------------------- Properties


    /**
     * Generic properties, introspected
     */
    @Override
    public boolean setProperty(String name, String value) {
        final String selectorPoolName = "selectorPool.";
        try {
            if (name.startsWith(selectorPoolName)) {
                return IntrospectionUtils.setProperty(selectorPool, name.substring(selectorPoolName.length()), value);
            } else {
                return super.setProperty(name, value);
            }
        }catch ( Exception x ) {
            log.error("Unable to set attribute \""+name+"\" to \""+value+"\"",x);
            return false;
        }
    }


    /**
     * Use System.inheritableChannel to obtain channel from stdin/stdout.
     */
    private boolean useInheritedChannel = false;
    public void setUseInheritedChannel(boolean useInheritedChannel) { this.useInheritedChannel = useInheritedChannel; }
    public boolean getUseInheritedChannel() { return useInheritedChannel; }

    /**
     * Priority of the poller threads.
     */
    private int pollerThreadPriority = Thread.NORM_PRIORITY;
    public void setPollerThreadPriority(int pollerThreadPriority) { this.pollerThreadPriority = pollerThreadPriority; }
    public int getPollerThreadPriority() { return pollerThreadPriority; }


    /**
     * Poller thread count.
     * 默认 2 个
     */
    private int pollerThreadCount = Math.min(2,Runtime.getRuntime().availableProcessors());
    public void setPollerThreadCount(int pollerThreadCount) { this.pollerThreadCount = pollerThreadCount; }
    public int getPollerThreadCount() { return pollerThreadCount; }

    private long selectorTimeout = 1000;
    public void setSelectorTimeout(long timeout){ this.selectorTimeout = timeout;}
    public long getSelectorTimeout(){ return this.selectorTimeout; }

    /**
     * The socket poller.
     */
    private Poller[] pollers = null;
    private AtomicInteger pollerRotater = new AtomicInteger(0);
    /**
     * Return an available poller in true round robin fashion.
     *
     * @return The next poller in sequence
     */
    public Poller getPoller0() {
        /* 采用轮序规则 */
        int idx = Math.abs(pollerRotater.incrementAndGet()) % pollers.length;
        return pollers[idx];
    }


    public void setSelectorPool(NioSelectorPool selectorPool) {
        this.selectorPool = selectorPool;
    }

    public void setSocketProperties(SocketProperties socketProperties) {
        this.socketProperties = socketProperties;
    }

    /**
     * Is deferAccept supported?
     */
    @Override
    public boolean getDeferAccept() {
        // Not supported
        return false;
    }


    // --------------------------------------------------------- Public Methods
    /**
     * Number of keep-alive sockets.
     *
     * @return The number of sockets currently in the keep-alive state waiting
     *         for the next request to be received on the socket
     */
    public int getKeepAliveCount() {
        if (pollers == null) {
            return 0;
        } else {
            int sum = 0;
            for (int i=0; i<pollers.length; i++) {
                sum += pollers[i].getKeyCount();
            }
            return sum;
        }
    }


    // ----------------------------------------------- Public Lifecycle Methods

    /**
     * Initialize the endpoint.
     */
    @Override
    public void bind() throws Exception {
        /**
         * 在和 spring-boot-web 结合的版本中，这里没有判断，而是直接新建 serverSocket
         * 就是 if{...} 里面的代码
         */
        if (!getUseInheritedChannel()) {
            serverSock = ServerSocketChannel.open();
            socketProperties.setProperties(serverSock.socket());
            InetSocketAddress addr = (getAddress()!=null?new InetSocketAddress(getAddress(),getPort()):new InetSocketAddress(getPort()));
            /*
             * 这个步骤其实就是已经开始绑定端口然后开始监听了
             * 因为 debug 中，当没有执行这一步骤，那么请求直接提示服务不存在
             * 但是一旦执行这一步骤，那么请求就会连接进来，直到当前线程执行 .accept 方法创建 socket 进行处理
             */
            serverSock.socket().bind(addr,getAcceptCount());
        } else {
            // Retrieve the channel provided by the OS
            Channel ic = System.inheritedChannel();
            if (ic instanceof ServerSocketChannel) {
                serverSock = (ServerSocketChannel) ic;
            }
            if (serverSock == null) {
                throw new IllegalArgumentException(sm.getString("endpoint.init.bind.inherited"));
            }
        }
        /**
         * 和 netty 区别开，这里是阻塞状态
         * 性能其实差不多，因为 netty 中其实一般情况下也是只有一个线程作为 acceptor
         * 两者都是在一个线程中处理进来的连接，然后交给后面的线程处理
         * 只不过 netty 的设计模式能支持一个 acceptor 线程注册N个端口，同时保持M个客户端连接
         * 因为采用 selector 选择器
         * 而 tomcat 的 acceptor 没有采用 selector
         */
        serverSock.configureBlocking(true); //mimic APR behavior


        /* 设置 acceptor 线程个数，默认 1 */
        if (acceptorThreadCount == 0) {
            // FIXME: Doesn't seem to work that well with multiple accept threads
            /* 因为只有监听一个端口，并且是阻塞模式，所以只能是一个线程 */
            acceptorThreadCount = 1;
        }

        /* 初始化 poller 线程个数，默认 2 */
        if (pollerThreadCount <= 0) {
            //minimum one poller thread
            pollerThreadCount = 1;
        }
        setStopLatch(new CountDownLatch(pollerThreadCount));

        // Initialize SSL if needed
        initialiseSsl();

        // TODO: 2019/8/11
        selectorPool.open();
    }

    /**
     * 启动 endpoint 端点 socket
     * 具体是
     *  1 初始化相关属性
     *  2 设置线程池
     *  3 创建 poller 线程并启动
     *  4 创建 acceptor 线程并启动
     */
    @Override
    public void startInternal() throws Exception {

        if (!running) {
            running = true;
            paused = false;

            processorCache = new SynchronizedStack<>(SynchronizedStack.DEFAULT_SIZE, socketProperties.getProcessorCache());
            eventCache = new SynchronizedStack<>(SynchronizedStack.DEFAULT_SIZE, socketProperties.getEventCache());
            nioChannels = new SynchronizedStack<>(SynchronizedStack.DEFAULT_SIZE, socketProperties.getBufferPool());

            /**
             * 和 spring-boot-web 结合，并没有指定线程池，而是通过 createExecutor 方法新建
             */
            if ( getExecutor() == null ) {
                createExecutor();
            }

            initializeConnectionLatch();

            /* 创建 poller 线程组，然后逐一启动线程 */
            pollers = new Poller[getPollerThreadCount()];
            for (int i=0; i<pollers.length; i++) {
                pollers[i] = new Poller();
                Thread pollerThread = new Thread(pollers[i], getName() + "-ClientPoller-"+i);
                pollerThread.setPriority(threadPriority);
                pollerThread.setDaemon(true);
                pollerThread.start();
            }

            /* 调用父类方法启动 acceptor 线程 */
            startAcceptorThreads();
        }
    }


    /**
     * Stop the endpoint. This will cause all processing threads to stop.
     *
     * 用于停止 NioEndpoint
     */
    @Override
    public void stopInternal() {
        releaseConnectionLatch();
        if (!paused) {
            pause();
        }
        if (running) {
            running = false;
            unlockAccept();
            for (int i=0; pollers!=null && i<pollers.length; i++) {
                if (pollers[i]==null) continue;
                pollers[i].destroy();
                pollers[i] = null;
            }
            try {
                if (!getStopLatch().await(selectorTimeout + 100, TimeUnit.MILLISECONDS)) {
                    log.warn(sm.getString("endpoint.nio.stopLatchAwaitFail"));
                }
            } catch (InterruptedException e) {
                log.warn(sm.getString("endpoint.nio.stopLatchAwaitInterrupted"), e);
            }
            shutdownExecutor();
            eventCache.clear();
            nioChannels.clear();
            processorCache.clear();
        }
    }


    /**
     * Deallocate NIO memory pools, and close server socket.
     */
    @Override
    public void unbind() throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("Destroy initiated for "+new InetSocketAddress(getAddress(),getPort()));
        }
        if (running) {
            stop();
        }
        doCloseServerSocket();
        destroySsl();
        super.unbind();
        if (getHandler() != null ) {
            getHandler().recycle();
        }
        selectorPool.close();
        if (log.isDebugEnabled()) {
            log.debug("Destroy completed for "+new InetSocketAddress(getAddress(),getPort()));
        }
    }


    @Override
    protected void doCloseServerSocket() throws IOException {
        if (!getUseInheritedChannel() && serverSock != null) {
            // Close server socket
            serverSock.socket().close();
            serverSock.close();
        }
        serverSock = null;
    }


    // ------------------------------------------------------ Protected Methods


    public int getWriteBufSize() {
        return socketProperties.getTxBufSize();
    }

    public int getReadBufSize() {
        return socketProperties.getRxBufSize();
    }

    public NioSelectorPool getSelectorPool() {
        return selectorPool;
    }


    @Override
    protected AbstractEndpoint.Acceptor createAcceptor() {
        return new Acceptor();
    }


    protected CountDownLatch getStopLatch() {
        return stopLatch;
    }


    protected void setStopLatch(CountDownLatch stopLatch) {
        this.stopLatch = stopLatch;
    }


    /**
     * Process the specified connection.
     * @param socket The socket channel
     * @return <code>true</code> if the socket was correctly configured
     *  and processing may continue, <code>false</code> if the socket needs to be
     *  close immediately
     */
    protected boolean setSocketOptions(SocketChannel socket) {
        // Process the connection
        try {
            /**
             * 对于新建立的请求连接则不会采用阻塞模式，而是采用非阻塞模式
             */
            socket.configureBlocking(false);
            Socket sock = socket.socket();
            /* 设置 nio socket 属性，tcp 层属性，是否长连接 keepAlive 也是在这里设置 */
            socketProperties.setProperties(sock);

            NioChannel channel = nioChannels.pop();
            if (channel == null) {
                SocketBufferHandler bufhandler = new SocketBufferHandler(
                        socketProperties.getAppReadBufSize(),
                        socketProperties.getAppWriteBufSize(),
                        socketProperties.getDirectBuffer());

                if (isSSLEnabled()) {
                    channel = new SecureNioChannel(socket, bufhandler, selectorPool, this);
                } else {
                    channel = new NioChannel(socket, bufhandler);
                }
            } else {
                channel.setIOChannel(socket);
                channel.reset();
            }

            /**
             * 1 从 poller 线程组中获取一个 poller 处理器
             */
            getPoller0().register(channel);
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            try {
                log.error("",t);
            } catch (Throwable tt) {
                ExceptionUtils.handleThrowable(tt);
            }
            // Tell to close the socket
            return false;
        }
        return true;
    }


    @Override
    protected Log getLog() {
        return log;
    }


    @Override
    protected NetworkChannel getServerSocket() {
        return serverSock;
    }


    // --------------------------------------------------- Acceptor Inner Class
    /**
     * The background thread that listens for incoming TCP/IP connections and
     * hands them off to an appropriate processor.
     *
     * otz:
     *  后台线程监听传入的 TCP/IP 连接并将其传递给适当的处理器
     *
     *  这个 Acceptor 就是网络 socekt 接入的最开始入口
     *  注意：
     *      Acceptor 的设计模式和 netty 不同，这个 Acceptor 采用的是阻塞模式，
     *      也就是没有使用 selector 注册，而是直接单线程调用 .accept() 方法阻塞，直到有新连接接入
     *
     *  这个 acceptor 会在父类 {@link super#startAcceptorThreads()} 方法中被创建并且新建对应的线程执行
     *
     */
    protected class Acceptor extends AbstractEndpoint.Acceptor {

        @Override
        public void run() {

            int errorDelay = 0;

            // Loop until we receive a shutdown command
            while (running) {

                /**
                 * 检查 NioEndpoint 是否被暂停了
                 * 也就是是否用户调用了关闭相关的方法
                 * 这个方法会一直循环直到 running 状态为 false
                 * 如果用户没有调用关闭相关的方法，那么这个方法永远都不会进入，也就是tomcat一直正常运行
                 */
                while (paused && running) {
                    state = AcceptorState.PAUSED;
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                }

                /* 如果 Acceptor 状态为非运行，则直接退出循环，结束掉 Acceptor 线程 */
                if (!running) {
                    break;
                }

                state = AcceptorState.RUNNING;

                try {
                    /** 检测并发计数器是否允许 */
                    countUpOrAwaitConnection();

                    SocketChannel socket = null;
                    try {
                        /**
                         * 所有 http 请求连接都是从这里接入
                         * 如果没有请求则会一直阻塞
                         */
                        socket = serverSock.accept();
                    } catch (IOException ioe) {
                        /** 出现异常说明当前请求并没有生效，直接释放 */
                        countDownConnection();
                        if (running) {
                            // Introduce delay if necessary
                            errorDelay = handleExceptionWithDelay(errorDelay);
                            // re-throw
                            throw ioe;
                        } else {
                            break;
                        }
                    }
                    // Successful accept, reset the error delay
                    errorDelay = 0;

                    /** 再次判断外界有没有主动关闭 Acceptor */
                    if (running && !paused) {
                        /**
                         * setSocketOptions 核心方法
                         * 1 设置当前连接属性
                         * 2 封装成 NioChannel
                         */
                        if (!setSocketOptions(socket)) {
                            closeSocket(socket);
                        }
                    } else {
                        closeSocket(socket);
                    }
                } catch (Throwable t) {
                    ExceptionUtils.handleThrowable(t);
                    log.error(sm.getString("endpoint.accept.fail"), t);
                }
            }
            /* 退出 while 循环，当前 Accptor 线程就结束 */
            state = AcceptorState.ENDED;
        }


        private void closeSocket(SocketChannel socket) {
            countDownConnection();
            try {
                socket.socket().close();
            } catch (IOException ioe)  {
                if (log.isDebugEnabled()) {
                    log.debug(sm.getString("endpoint.err.close"), ioe);
                }
            }
            try {
                socket.close();
            } catch (IOException ioe) {
                if (log.isDebugEnabled()) {
                    log.debug(sm.getString("endpoint.err.close"), ioe);
                }
            }
        }
    }


    @Override
    protected SocketProcessorBase<NioChannel> createSocketProcessor(
            SocketWrapperBase<NioChannel> socketWrapper, SocketEvent event) {
        return new SocketProcessor(socketWrapper, event);
    }

    /**
     * {@link #nioChannels} 是用来缓存 NioChannel 对象的
     * 第一次：一个请求过来肯定没有缓存，所以新建
     * 那么什么时候回收这个对象呢？
     *  就是在这个连接断开之后才回收
     *  也即是在 keepAlive 情况下，如果连接一直存在是不会回收的
     *
     * 比如使用 浏览器、postMan 等客户端工具，默认都是开启 keepAlive，时间默认都是 60s
     */
    private void close(NioChannel socket, SelectionKey key) {
        try {
            if (socket.getPoller().cancelledKey(key) != null) {
                // SocketWrapper (attachment) was removed from the
                // key - recycle the key. This can only happen once
                // per attempted closure so it is used to determine
                // whether or not to return the key to the cache.
                // We do NOT want to do this more than once - see BZ
                // 57340 / 57943.
                if (log.isDebugEnabled()) {
                    log.debug("Socket: [" + socket + "] closed");
                }
                if (running && !paused) {
                    if (!nioChannels.push(socket)) {
                        socket.free();
                    }
                }
            }
        } catch (Exception x) {
            log.error("",x);
        }
    }

    // ----------------------------------------------------- Poller Inner Classes

    /**
     *
     * PollerEvent, cacheable object for poller events to avoid GC
     */
    public static class PollerEvent implements Runnable {

        private NioChannel socket;
        private int interestOps;
        private NioSocketWrapper socketWrapper;

        public PollerEvent(NioChannel ch, NioSocketWrapper w, int intOps) {
            reset(ch, w, intOps);
        }

        public void reset(NioChannel ch, NioSocketWrapper w, int intOps) {
            socket = ch;
            interestOps = intOps;
            socketWrapper = w;
        }

        public void reset() {
            reset(null, null, 0);
        }

        @Override
        public void run() {
            /**
             * 如果是 OP_REGISTER，说明是新连接接入，在{@link Poller#register(NioChannel)} 方法中被设置
             */
            if (interestOps == OP_REGISTER) {
                try {
                    /* 如果是新连接接入，则将之注册到 poller 的 selector 上，感兴趣事件为 read，并且附属一个 wrapper 对象 */
                    socket.getIOChannel().register(socket.getPoller().getSelector(), SelectionKey.OP_READ, socketWrapper);
                } catch (Exception x) {
                    log.error(sm.getString("endpoint.nio.registerFail"), x);
                }
            } else {
                /* 返回这个 channel 注册感兴趣的事件 key */
                final SelectionKey key = socket.getIOChannel().keyFor(socket.getPoller().getSelector());
                try {
                    /* 如果 key 为空，则说明这个 channel 可以被关闭了 */
                    if (key == null) {
                        // The key was cancelled (e.g. due to socket closure)
                        // and removed from the selector while it was being
                        // processed. Count down the connections at this point
                        // since it won't have been counted down when the socket
                        // closed.
                        /* 并发计数器减 1 */
                        socket.socketWrapper.getEndpoint().countDownConnection();
                        /* 标记关闭状态为 true */
                        ((NioSocketWrapper) socket.socketWrapper).closed = true;
                    } else {
                        final NioSocketWrapper socketWrapper = (NioSocketWrapper) key.attachment();
                        if (socketWrapper != null) {
                            //we are registering the key to start with, reset the fairness counter.
                            /** 设置注册事件 */
                            int ops = key.interestOps() | interestOps;
                            socketWrapper.interestOps(ops);
                            key.interestOps(ops);
                        } else {
                            /* 如果附属 attachment 对象为空，则取消注册事件，后续会关闭连接 */
                            socket.getPoller().cancelledKey(key);
                        }
                    }
                } catch (CancelledKeyException ckx) {
                    try {
                        socket.getPoller().cancelledKey(key);
                    } catch (Exception ignore) {}
                }
            }
        }

        @Override
        public String toString() {
            return "Poller event: socket [" + socket + "], socketWrapper [" + socketWrapper +
                    "], interestOps [" + interestOps + "]";
        }
    }

    /**
     * Poller class.
     * otz:
     *  Poller 组件用在非阻塞 IO 模型中，轮训多个客户端连接，不断检测，处理各种事情，
     *  例如不断检测各个连接是否可读，对于可读的客户端连接尝试进行读取解析消息报文
     *  可以理解成 netty 中的 worker 线程组的 NioEventLoop
     *  上面注册了 读事件
     *
     * Poller 工作原理：
     *  所有的连接数据交互都需要经历2步骤
     *  1 客户端发起连接请求
     *  2 连接建立好后，客户端开始真正发送数据
     *
     * Acceptor 就是 netty 中的 boss-NioEventLoop，用于处理连接接入，只不过前者是采用阻塞模式
     * Poller 就是 netty 中的 worker-NioEventLoop，用于处理数据的读取
     *
     */
    public class Poller implements Runnable {
        /** 每个 Poller 所属的 selector */
        private Selector selector;

        private final SynchronizedQueue<PollerEvent> events = new SynchronizedQueue<>();

        private volatile boolean close = false;

        private long nextExpiration = 0;//optimize expiration handling

        private AtomicLong wakeupCounter = new AtomicLong(0);

        private volatile int keyCount = 0;

        public Poller() throws IOException {
            this.selector = Selector.open();
        }

        public int getKeyCount() { return keyCount; }

        public Selector getSelector() { return selector;}

        /**
         * Destroy the poller.
         */
        protected void destroy() {
            // Wait for polltime before doing anything, so that the poller threads
            // exit, otherwise parallel closure of sockets which are still
            // in the poller can cause problems
            close = true;
            selector.wakeup();
        }

        /**
         * 客户端请求连接 SocketChannel 会封装成一个 {@link NioChannel}，然后会进一步封装成 {@link PollerEvent}
         * 最后被添加到 {@link Poller#events} 同步队列中
         */
        private void addEvent(PollerEvent event) {
            events.offer(event);
            /**
             * wakeupCounter 加 1
             * 如果加 1 之前其值为 0，则设置 selector.wakeup()
             */
            if (wakeupCounter.incrementAndGet() == 0) {
                /**
                 * 1 如果 Poller 线程已经调用了 select 或者 select(long) 进入了阻塞，那么这个 wakeup 操作会唤醒 Poller 线程
                 * 2 如果没有 Poller 线程阻塞在这个 selector 上，那么这次调用会作为一个标识，下次 Poller 线程调用 select 或者 select(long) 会立马返回
                 */
                selector.wakeup();
            }
        }

        /**
         * Add specified socket and associated pool to the poller. The socket will
         * be added to a temporary array, and polled first after a maximum amount
         * of time equal to pollTime (in most cases, latency will be much lower,
         * however).
         *
         * @param socket to add to the poller
         * @param interestOps Operations for which to register this socket with
         *                    the Poller
         */
        public void add(final NioChannel socket, final int interestOps) {
            PollerEvent r = eventCache.pop();

            if ( r==null) {
                r = new PollerEvent(socket,null,interestOps);
            } else {
                r.reset(socket,null,interestOps);
            }

            addEvent(r);

            if (close) {
                NioSocketWrapper ka = (NioSocketWrapper)socket.getAttachment();
                processSocket(ka, SocketEvent.STOP, false);
            }
        }

        /**
         * Processes events in the event queue of the Poller.
         *
         * @return <code>true</code> if some events were processed,
         *   <code>false</code> if queue was empty
         *
         * otz:
         *  遍历 poller 的 events 队列
         *  如果有则执行，并且返回 true
         *  否则返回 false
         */
        public boolean events() {
            boolean result = false;

            PollerEvent pe = null;
            /* 轮训 events 中的  PollerEvent */
            for (int i = 0, size = events.size(); i < size && (pe = events.poll()) != null; i++ ) {
                result = true;
                try {
                    /* 轮训出来后执行，这里负责执行的线程是当前 poller 线程 */
                    pe.run();
                    pe.reset();
                    if (running && !paused) {
                        /* 缓存对象 */
                        eventCache.push(pe);
                    }
                } catch ( Throwable x ) {
                    log.error("",x);
                }
            }

            return result;
        }

        /**
         * Registers a newly created socket with the poller.
         *
         * otz:
         *  将 NioChannel 添加到 poller 中
         */
        public void register(final NioChannel socket) {
            socket.setPoller(this);
            NioSocketWrapper ka = new NioSocketWrapper(socket, NioEndpoint.this);
            socket.setSocketWrapper(ka);
            ka.setPoller(this);
            ka.setReadTimeout(getSocketProperties().getSoTimeout());
            ka.setWriteTimeout(getSocketProperties().getSoTimeout());
            ka.setKeepAliveLeft(NioEndpoint.this.getMaxKeepAliveRequests());
            ka.setSecure(isSSLEnabled());
            ka.setReadTimeout(getConnectionTimeout());
            ka.setWriteTimeout(getConnectionTimeout());

            /* 注册读事件 */
            ka.interestOps(SelectionKey.OP_READ);//this is what OP_REGISTER turns into.

            /* 这里注册的事件只是一个标识，tomcat 自己定义的，用于控制第一次连接接入判断 */
            PollerEvent r = eventCache.pop();
            if ( r==null) {
                r = new PollerEvent(socket,ka,OP_REGISTER);
            }
            else {
                r.reset(socket,ka,OP_REGISTER);
            }
            /* 添加到 poller 中 */
            addEvent(r);

            /**
             *  直到这里结束，也就是 Acceptor 线程每次接受请求需要处理到这里，都是在 Acceptor 线程中执行的
             *  需要注意：只有新连接才会执行上面的代码，
             *  比如 keepAlive 只有建立建立的时候才会执行
             */
        }

        public NioSocketWrapper cancelledKey(SelectionKey key) {
            NioSocketWrapper ka = null;
            try {
                if ( key == null ) return null;//nothing to do
                ka = (NioSocketWrapper) key.attach(null);
                if (ka != null) {
                    // If attachment is non-null then there may be a current
                    // connection with an associated processor.
                    getHandler().release(ka);
                }
                if (key.isValid()) key.cancel();
                // If it is available, close the NioChannel first which should
                // in turn close the underlying SocketChannel. The NioChannel
                // needs to be closed first, if available, to ensure that TLS
                // connections are shut down cleanly.
                if (ka != null) {
                    try {
                        ka.getSocket().close(true);
                    } catch (Exception e){
                        if (log.isDebugEnabled()) {
                            log.debug(sm.getString(
                                    "endpoint.debug.socketCloseFail"), e);
                        }
                    }
                }
                // The SocketChannel is also available via the SelectionKey. If
                // it hasn't been closed in the block above, close it now.
                if (key.channel().isOpen()) {
                    try {
                        key.channel().close();
                    } catch (Exception e) {
                        if (log.isDebugEnabled()) {
                            log.debug(sm.getString(
                                    "endpoint.debug.channelCloseFail"), e);
                        }
                    }
                }
                try {
                    if (ka != null && ka.getSendfileData() != null
                            && ka.getSendfileData().fchannel != null
                            && ka.getSendfileData().fchannel.isOpen()) {
                        ka.getSendfileData().fchannel.close();
                    }
                } catch (Exception ignore) {
                }
                if (ka != null) {
                    countDownConnection();
                    ka.closed = true;
                }
            } catch (Throwable e) {
                ExceptionUtils.handleThrowable(e);
                if (log.isDebugEnabled()) log.error("",e);
            }
            return ka;
        }

        /**
         * The background thread that adds sockets to the Poller, checks the
         * poller for triggered events and hands the associated socket off to an
         * appropriate processor as events occur.
         *
         * otz:
         *  该方法就是 poller 线程执行的逻辑
         */
        @Override
        public void run() {
            // Loop until destroy() is called
            while (true) {

                boolean hasEvents = false;

                try {
                    if (!close) {
                        /* 是否有 event 被执行 */
                        hasEvents = events();

                        /**
                         * 将 wakeupCounter 设置成 -1 暂时不理解为什么？ todo
                         * 但是这里设置之前先获取一次原先值，如果大于 0，说明有 event 被添加到队列中需要被处理
                         */
                        if (wakeupCounter.getAndSet(-1) > 0) {
                            /*
                             * 之所以调用 selectNow
                             * 是因为在并发情况下，有 event 被添加到了任务队列中，需要被处理
                             * 但是调用 selectNow 好处就是，如果在上一次循环结束到当前这个点之间有已经注册的 channel 发生了
                             * 读事件，那么需要将全部取出来处理
                             *
                             * 比如已经建立了一个 keepAlive 连接，同时有线程往 events 队列中添加了 event，
                             * 刚好这个 keepAlive 连接发生了读事件
                             * 那么本次轮序就不应该阻塞(因为判断出有 event 在队列中)，并且不应该忽略发生了读事件的 channel
                             */
                            keyCount = selector.selectNow();
                        } else {
                            /* 如果队列中没有 event，则默认进行阻塞选择，时间和 netty 一样，默认阻塞 1s */
                            keyCount = selector.select(selectorTimeout);
                        }
                        wakeupCounter.set(0);
                    }

                    /* 关闭逻辑 */
                    if (close) {
                        events();
                        timeout(0, false);
                        try {
                            selector.close();
                        } catch (IOException ioe) {
                            log.error(sm.getString("endpoint.nio.selectorCloseFail"), ioe);
                        }
                        break;
                    }
                } catch (Throwable x) {
                    ExceptionUtils.handleThrowable(x);
                    log.error("",x);
                    continue;
                }

                /**
                 * 每次 selector 被唤醒，要么就是任务被添加，要么就是有 IO 读事件发生，要么就是 selector 超时
                 * 如果没有 IO 读事件发生(keyCount = 0)，则先处理任务队列，当然，也有可能任务队列也没有，其实什么都没有处理
                 */
                if ( keyCount == 0 ) {
                    hasEvents = (hasEvents | events());
                }

                /**
                 * 判断是否有 IO 读事件发生
                 * 如果有则逐一遍历处理
                 * 如果没有则什么都不做
                 */
                Iterator<SelectionKey> iterator = keyCount > 0 ? selector.selectedKeys().iterator() : null;
                // Walk through the collection of ready keys and dispatch
                // any active event.
                while (iterator != null && iterator.hasNext()) {
                    SelectionKey sk = iterator.next();
                    NioSocketWrapper attachment = (NioSocketWrapper)sk.attachment();
                    // Attachment may be null if another thread has called
                    // cancelledKey()
                    if (attachment == null) {
                        iterator.remove();
                    } else {
                        iterator.remove();
                        /** 核心逻辑 */
                        processKey(sk, attachment);
                    }
                }//while

                //process timeouts
                timeout(keyCount, hasEvents);
            }//while

            getStopLatch().countDown();
        }

        protected void processKey(SelectionKey sk, NioSocketWrapper attachment) {
            try {
                /* 判断 poller 是否被关闭 */
                if (close) {
                    cancelledKey(sk);
                } else if (sk.isValid() && attachment != null) {
                    /* 判断是否可读或可写 */
                    if (sk.isReadable() || sk.isWritable()) {

                        /* 判断是否有关联的文件，如果有则调用 processSendfile */
                        if (attachment.getSendfileData() != null) {
                            processSendfile(sk, attachment, false);
                        } else {
                            /* 取消读事件 */
                            unreg(sk, attachment, sk.readyOps());

                            boolean closeSocket = false;

                            /* 如果一个 channel 同时发生了读写操作，那么先执行读操作，然后执行写操作 */

                            /* 判断是否是读事件 */
                            if (sk.isReadable()) {
                                if (!processSocket(attachment, SocketEvent.OPEN_READ, true)) {
                                    closeSocket = true;
                                }
                            }

                            /*
                             * 判断是否是写事件
                             * 需要注意:如果注册了 write 事件，那么内核会自动判断写入缓存区 buffer 是否可写，如果可先就会返回 true
                             *  所以如果注册了这个事件，那么就算用户没有调用 write 方法，只要缓冲区非满，select操作都会返回写事件
                             *  所以一般的做法是如果用户写入了数据，那么就注册一个 write 事件，处理完成后记得取消 write 事件即可
                             *  然后执行 select 阻塞
                             *  好处是:如果缓冲区满了，当前线程不会阻塞，并且当前 write 事件还在 selector 上
                             *        如果缓冲区非满，那么 isWritable 方法就会返回 true，进入用户代码分支，但是用户记得
                             *              每次处理完成后取消 write 事件即可，否则下次 select 操作还会返回 写事件
                             * */
                            if (!closeSocket && sk.isWritable()) {
                                if (!processSocket(attachment, SocketEvent.OPEN_WRITE, true)) {
                                    closeSocket = true;
                                }
                            }

                            if (closeSocket) {
                                cancelledKey(sk);
                            }
                        }
                    }
                } else {
                    //invalid key
                    cancelledKey(sk);
                }
            } catch (CancelledKeyException ckx) {
                cancelledKey(sk);
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                log.error("", t);
            }
        }

        public SendfileState processSendfile(SelectionKey sk, NioSocketWrapper socketWrapper, boolean calledByProcessor) {
            NioChannel sc = null;
            try {
                unreg(sk, socketWrapper, sk.readyOps());
                SendfileData sd = socketWrapper.getSendfileData();

                if (log.isTraceEnabled()) {
                    log.trace("Processing send file for: " + sd.fileName);
                }

                if (sd.fchannel == null) {
                    // Setup the file channel
                    File f = new File(sd.fileName);
                    @SuppressWarnings("resource") // Closed when channel is closed
                    FileInputStream fis = new FileInputStream(f);
                    sd.fchannel = fis.getChannel();
                }

                // Configure output channel
                sc = socketWrapper.getSocket();
                // TLS/SSL channel is slightly different
                WritableByteChannel wc = ((sc instanceof SecureNioChannel)?sc:sc.getIOChannel());

                // We still have data in the buffer
                if (sc.getOutboundRemaining()>0) {
                    if (sc.flushOutbound()) {
                        socketWrapper.updateLastWrite();
                    }
                } else {
                    long written = sd.fchannel.transferTo(sd.pos,sd.length,wc);
                    if (written > 0) {
                        sd.pos += written;
                        sd.length -= written;
                        socketWrapper.updateLastWrite();
                    } else {
                        // Unusual not to be able to transfer any bytes
                        // Check the length was set correctly
                        if (sd.fchannel.size() <= sd.pos) {
                            throw new IOException("Sendfile configured to " +
                                    "send more data than was available");
                        }
                    }
                }
                if (sd.length <= 0 && sc.getOutboundRemaining()<=0) {
                    if (log.isDebugEnabled()) {
                        log.debug("Send file complete for: "+sd.fileName);
                    }
                    socketWrapper.setSendfileData(null);
                    try {
                        sd.fchannel.close();
                    } catch (Exception ignore) {
                    }
                    // For calls from outside the Poller, the caller is
                    // responsible for registering the socket for the
                    // appropriate event(s) if sendfile completes.
                    if (!calledByProcessor) {
                        switch (sd.keepAliveState) {
                        case NONE: {
                            if (log.isDebugEnabled()) {
                                log.debug("Send file connection is being closed");
                            }
                            close(sc, sk);
                            break;
                        }
                        case PIPELINED: {
                            if (log.isDebugEnabled()) {
                                log.debug("Connection is keep alive, processing pipe-lined data");
                            }
                            if (!processSocket(socketWrapper, SocketEvent.OPEN_READ, true)) {
                                close(sc, sk);
                            }
                            break;
                        }
                        case OPEN: {
                            if (log.isDebugEnabled()) {
                                log.debug("Connection is keep alive, registering back for OP_READ");
                            }
                            reg(sk,socketWrapper,SelectionKey.OP_READ);
                            break;
                        }
                        }
                    }
                    return SendfileState.DONE;
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("OP_WRITE for sendfile: " + sd.fileName);
                    }
                    if (calledByProcessor) {
                        add(socketWrapper.getSocket(),SelectionKey.OP_WRITE);
                    } else {
                        reg(sk,socketWrapper,SelectionKey.OP_WRITE);
                    }
                    return SendfileState.PENDING;
                }
            } catch (IOException x) {
                if (log.isDebugEnabled()) log.debug("Unable to complete sendfile request:", x);
                if (!calledByProcessor && sc != null) {
                    close(sc, sk);
                }
                return SendfileState.ERROR;
            } catch (Throwable t) {
                log.error("", t);
                if (!calledByProcessor && sc != null) {
                    close(sc, sk);
                }
                return SendfileState.ERROR;
            }
        }

        /**
         * 在 selector 上取消 读 事件
         */
        protected void unreg(SelectionKey sk, NioSocketWrapper attachment, int readyOps) {
            //this is a must, so that we don't have multiple threads messing with the socket
            reg(sk,attachment,sk.interestOps()& (~readyOps));
        }

        /**
         * 在 selector 上注册指定的事件
         */
        protected void reg(SelectionKey sk, NioSocketWrapper attachment, int intops) {
            sk.interestOps(intops);
            attachment.interestOps(intops);
        }

        /**
         * 处理超时机制
         */
        protected void timeout(int keyCount, boolean hasEvents) {
            long now = System.currentTimeMillis();
            // This method is called on every loop of the Poller. Don't process
            // timeouts on every loop of the Poller since that would create too
            // much load and timeouts can afford to wait a few seconds.
            // However, do process timeouts if any of the following are true:
            // - the selector simply timed out (suggests there isn't much load)
            // - the nextExpiration time has passed
            // - the server socket is being closed
            if (nextExpiration > 0 && (keyCount > 0 || hasEvents) && (now < nextExpiration) && !close) {
                return;
            }
            //timeout
            int keycount = 0;
            try {
                for (SelectionKey key : selector.keys()) {
                    keycount++;
                    try {
                        NioSocketWrapper ka = (NioSocketWrapper) key.attachment();
                        if ( ka == null ) {
                            cancelledKey(key); //we don't support any keys without attachments
                        } else if (close) {
                            key.interestOps(0);
                            ka.interestOps(0); //avoid duplicate stop calls
                            processKey(key,ka);
                        } else if ((ka.interestOps()&SelectionKey.OP_READ) == SelectionKey.OP_READ ||
                                  (ka.interestOps()&SelectionKey.OP_WRITE) == SelectionKey.OP_WRITE) {
                            boolean isTimedOut = false;
                            // Check for read timeout
                            if ((ka.interestOps() & SelectionKey.OP_READ) == SelectionKey.OP_READ) {
                                long delta = now - ka.getLastRead();
                                long timeout = ka.getReadTimeout();
                                isTimedOut = timeout > 0 && delta > timeout;
                            }
                            // Check for write timeout
                            if (!isTimedOut && (ka.interestOps() & SelectionKey.OP_WRITE) == SelectionKey.OP_WRITE) {
                                long delta = now - ka.getLastWrite();
                                long timeout = ka.getWriteTimeout();
                                isTimedOut = timeout > 0 && delta > timeout;
                            }
                            if (isTimedOut) {
                                key.interestOps(0);
                                ka.interestOps(0); //avoid duplicate timeout calls
                                ka.setError(new SocketTimeoutException());
                                if (!processSocket(ka, SocketEvent.ERROR, true)) {
                                    cancelledKey(key);
                                }
                            }
                        }
                    }catch ( CancelledKeyException ckx ) {
                        cancelledKey(key);
                    }
                }//for
            } catch (ConcurrentModificationException cme) {
                // See https://bz.apache.org/bugzilla/show_bug.cgi?id=57943
                log.warn(sm.getString("endpoint.nio.timeoutCme"), cme);
            }
            long prevExp = nextExpiration; //for logging purposes only
            nextExpiration = System.currentTimeMillis() +
                    socketProperties.getTimeoutInterval();
            if (log.isTraceEnabled()) {
                log.trace("timeout completed: keys processed=" + keycount +
                        "; now=" + now + "; nextExpiration=" + prevExp +
                        "; keyCount=" + keyCount + "; hasEvents=" + hasEvents +
                        "; eval=" + ((now < prevExp) && (keyCount>0 || hasEvents) && (!close) ));
            }

        }
    }

    // ---------------------------------------------------- Key Attachment Class
    public static class NioSocketWrapper extends SocketWrapperBase<NioChannel> {

        private final NioSelectorPool pool;

        private Poller poller = null;
        private int interestOps = 0;
        private CountDownLatch readLatch = null;
        private CountDownLatch writeLatch = null;
        private volatile SendfileData sendfileData = null;
        private volatile long lastRead = System.currentTimeMillis();
        private volatile long lastWrite = lastRead;
        private volatile boolean closed = false;

        public NioSocketWrapper(NioChannel channel, NioEndpoint endpoint) {
            super(channel, endpoint);
            pool = endpoint.getSelectorPool();
            socketBufferHandler = channel.getBufHandler();
        }

        public Poller getPoller() { return poller;}
        public void setPoller(Poller poller){this.poller = poller;}
        public int interestOps() { return interestOps;}
        public int interestOps(int ops) { this.interestOps  = ops; return ops; }
        public CountDownLatch getReadLatch() { return readLatch; }
        public CountDownLatch getWriteLatch() { return writeLatch; }
        protected CountDownLatch resetLatch(CountDownLatch latch) {
            if ( latch==null || latch.getCount() == 0 ) return null;
            else throw new IllegalStateException("Latch must be at count 0");
        }
        public void resetReadLatch() { readLatch = resetLatch(readLatch); }
        public void resetWriteLatch() { writeLatch = resetLatch(writeLatch); }

        protected CountDownLatch startLatch(CountDownLatch latch, int cnt) {
            if ( latch == null || latch.getCount() == 0 ) {
                return new CountDownLatch(cnt);
            }
            else throw new IllegalStateException("Latch must be at count 0 or null.");
        }
        public void startReadLatch(int cnt) { readLatch = startLatch(readLatch,cnt);}
        public void startWriteLatch(int cnt) { writeLatch = startLatch(writeLatch,cnt);}

        protected void awaitLatch(CountDownLatch latch, long timeout, TimeUnit unit) throws InterruptedException {
            if ( latch == null ) throw new IllegalStateException("Latch cannot be null");
            // Note: While the return value is ignored if the latch does time
            //       out, logic further up the call stack will trigger a
            //       SocketTimeoutException
            latch.await(timeout,unit);
        }
        public void awaitReadLatch(long timeout, TimeUnit unit) throws InterruptedException { awaitLatch(readLatch,timeout,unit);}
        public void awaitWriteLatch(long timeout, TimeUnit unit) throws InterruptedException { awaitLatch(writeLatch,timeout,unit);}

        public void setSendfileData(SendfileData sf) { this.sendfileData = sf;}
        public SendfileData getSendfileData() { return this.sendfileData;}

        public void updateLastWrite() { lastWrite = System.currentTimeMillis(); }
        public long getLastWrite() { return lastWrite; }
        public void updateLastRead() { lastRead = System.currentTimeMillis(); }
        public long getLastRead() { return lastRead; }


        @Override
        public boolean isReadyForRead() throws IOException {
            socketBufferHandler.configureReadBufferForRead();

            if (socketBufferHandler.getReadBuffer().remaining() > 0) {
                return true;
            }

            fillReadBuffer(false);

            boolean isReady = socketBufferHandler.getReadBuffer().position() > 0;
            return isReady;
        }


        @Override
        public int read(boolean block, byte[] b, int off, int len) throws IOException {
            int nRead = populateReadBuffer(b, off, len);
            if (nRead > 0) {
                return nRead;
                /*
                 * Since more bytes may have arrived since the buffer was last
                 * filled, it is an option at this point to perform a
                 * non-blocking read. However correctly handling the case if
                 * that read returns end of stream adds complexity. Therefore,
                 * at the moment, the preference is for simplicity.
                 */
            }

            // Fill the read buffer as best we can.
            nRead = fillReadBuffer(block);
            updateLastRead();

            // Fill as much of the remaining byte array as possible with the
            // data that was just read
            if (nRead > 0) {
                socketBufferHandler.configureReadBufferForRead();
                nRead = Math.min(nRead, len);
                socketBufferHandler.getReadBuffer().get(b, off, nRead);
            }
            return nRead;
        }


        @Override
        public int read(boolean block, ByteBuffer to) throws IOException {
            int nRead = populateReadBuffer(to);
            if (nRead > 0) {
                return nRead;
                /*
                 * Since more bytes may have arrived since the buffer was last
                 * filled, it is an option at this point to perform a
                 * non-blocking read. However correctly handling the case if
                 * that read returns end of stream adds complexity. Therefore,
                 * at the moment, the preference is for simplicity.
                 */
            }

            // The socket read buffer capacity is socket.appReadBufSize
            int limit = socketBufferHandler.getReadBuffer().capacity();
            if (to.remaining() >= limit) {
                to.limit(to.position() + limit);
                nRead = fillReadBuffer(block, to);
                if (log.isDebugEnabled()) {
                    log.debug("Socket: [" + this + "], Read direct from socket: [" + nRead + "]");
                }
                updateLastRead();
            } else {
                // Fill the read buffer as best we can.
                nRead = fillReadBuffer(block);
                if (log.isDebugEnabled()) {
                    log.debug("Socket: [" + this + "], Read into buffer: [" + nRead + "]");
                }
                updateLastRead();

                // Fill as much of the remaining byte array as possible with the
                // data that was just read
                if (nRead > 0) {
                    nRead = populateReadBuffer(to);
                }
            }
            return nRead;
        }


        @Override
        public void close() throws IOException {
            getSocket().close();
        }


        @Override
        public boolean isClosed() {
            return closed;
        }


        private int fillReadBuffer(boolean block) throws IOException {
            socketBufferHandler.configureReadBufferForWrite();
            return fillReadBuffer(block, socketBufferHandler.getReadBuffer());
        }


        private int fillReadBuffer(boolean block, ByteBuffer to) throws IOException {
            int nRead;
            NioChannel channel = getSocket();
            if (block) {
                Selector selector = null;
                try {
                    selector = pool.get();
                } catch (IOException x) {
                    // Ignore
                }
                try {
                    NioSocketWrapper att = (NioSocketWrapper) channel
                            .getAttachment();
                    if (att == null) {
                        throw new IOException("Key must be cancelled.");
                    }
                    nRead = pool.read(to, channel, selector, att.getReadTimeout());
                } finally {
                    if (selector != null) {
                        pool.put(selector);
                    }
                }
            } else {
                nRead = channel.read(to);
                if (nRead == -1) {
                    throw new EOFException();
                }
            }
            return nRead;
        }


        @Override
        protected void doWrite(boolean block, ByteBuffer from) throws IOException {
            long writeTimeout = getWriteTimeout();
            Selector selector = null;
            try {
                selector = pool.get();
            } catch (IOException x) {
                // Ignore
            }
            try {
                pool.write(from, getSocket(), selector, writeTimeout, block);
                if (block) {
                    // Make sure we are flushed
                    do {
                        if (getSocket().flush(true, selector, writeTimeout)) {
                            break;
                        }
                    } while (true);
                }
                updateLastWrite();
            } finally {
                if (selector != null) {
                    pool.put(selector);
                }
            }
            // If there is data left in the buffer the socket will be registered for
            // write further up the stack. This is to ensure the socket is only
            // registered for write once as both container and user code can trigger
            // write registration.
        }


        @Override
        public void registerReadInterest() {
            getPoller().add(getSocket(), SelectionKey.OP_READ);
        }


        @Override
        public void registerWriteInterest() {
            getPoller().add(getSocket(), SelectionKey.OP_WRITE);
        }


        @Override
        public SendfileDataBase createSendfileData(String filename, long pos, long length) {
            return new SendfileData(filename, pos, length);
        }


        @Override
        public SendfileState processSendfile(SendfileDataBase sendfileData) {
            setSendfileData((SendfileData) sendfileData);
            SelectionKey key = getSocket().getIOChannel().keyFor(
                    getSocket().getPoller().getSelector());
            // Might as well do the first write on this thread
            return getSocket().getPoller().processSendfile(key, this, true);
        }


        @Override
        protected void populateRemoteAddr() {
            InetAddress inetAddr = getSocket().getIOChannel().socket().getInetAddress();
            if (inetAddr != null) {
                remoteAddr = inetAddr.getHostAddress();
            }
        }


        @Override
        protected void populateRemoteHost() {
            InetAddress inetAddr = getSocket().getIOChannel().socket().getInetAddress();
            if (inetAddr != null) {
                remoteHost = inetAddr.getHostName();
                if (remoteAddr == null) {
                    remoteAddr = inetAddr.getHostAddress();
                }
            }
        }


        @Override
        protected void populateRemotePort() {
            remotePort = getSocket().getIOChannel().socket().getPort();
        }


        @Override
        protected void populateLocalName() {
            InetAddress inetAddr = getSocket().getIOChannel().socket().getLocalAddress();
            if (inetAddr != null) {
                localName = inetAddr.getHostName();
            }
        }


        @Override
        protected void populateLocalAddr() {
            InetAddress inetAddr = getSocket().getIOChannel().socket().getLocalAddress();
            if (inetAddr != null) {
                localAddr = inetAddr.getHostAddress();
            }
        }


        @Override
        protected void populateLocalPort() {
            localPort = getSocket().getIOChannel().socket().getLocalPort();
        }


        /**
         * {@inheritDoc}
         * @param clientCertProvider Ignored for this implementation
         */
        @Override
        public SSLSupport getSslSupport(String clientCertProvider) {
            if (getSocket() instanceof SecureNioChannel) {
                SecureNioChannel ch = (SecureNioChannel) getSocket();
                SSLSession session = ch.getSslEngine().getSession();
                return ((NioEndpoint) getEndpoint()).getSslImplementation().getSSLSupport(session);
            } else {
                return null;
            }
        }


        @Override
        public void doClientAuth(SSLSupport sslSupport) throws IOException {
            SecureNioChannel sslChannel = (SecureNioChannel) getSocket();
            SSLEngine engine = sslChannel.getSslEngine();
            if (!engine.getNeedClientAuth()) {
                // Need to re-negotiate SSL connection
                engine.setNeedClientAuth(true);
                sslChannel.rehandshake(getEndpoint().getConnectionTimeout());
                ((JSSESupport) sslSupport).setSession(engine.getSession());
            }
        }


        @Override
        public void setAppReadBufHandler(ApplicationBufferHandler handler) {
            getSocket().setAppReadBufHandler(handler);
        }
    }


    // ---------------------------------------------- SocketProcessor Inner Class

    /**
     * This class is the equivalent of the Worker, but will simply use in an
     * external Executor thread pool.
     */
    protected class SocketProcessor extends SocketProcessorBase<NioChannel> {

        public SocketProcessor(SocketWrapperBase<NioChannel> socketWrapper, SocketEvent event) {
            super(socketWrapper, event);
        }

        @Override
        protected void doRun() {
            NioChannel socket = socketWrapper.getSocket();
            SelectionKey key = socket.getIOChannel().keyFor(socket.getPoller().getSelector());

            try {
                int handshake = -1;

                try {
                    if (key != null) {
                        if (socket.isHandshakeComplete()) {
                            // No TLS handshaking required. Let the handler
                            // process this socket / event combination.
                            handshake = 0;
                        } else if (event == SocketEvent.STOP || event == SocketEvent.DISCONNECT ||
                                event == SocketEvent.ERROR) {
                            // Unable to complete the TLS handshake. Treat it as
                            // if the handshake failed.
                            handshake = -1;
                        } else {
                            handshake = socket.handshake(key.isReadable(), key.isWritable());
                            // The handshake process reads/writes from/to the
                            // socket. status may therefore be OPEN_WRITE once
                            // the handshake completes. However, the handshake
                            // happens when the socket is opened so the status
                            // must always be OPEN_READ after it completes. It
                            // is OK to always set this as it is only used if
                            // the handshake completes.
                            event = SocketEvent.OPEN_READ;
                        }
                    }
                } catch (IOException x) {
                    handshake = -1;
                    if (log.isDebugEnabled()) log.debug("Error during SSL handshake",x);
                } catch (CancelledKeyException ckx) {
                    handshake = -1;
                }
                if (handshake == 0) {
                    SocketState state = SocketState.OPEN;
                    // Process the request from this socket
                    if (event == null) {
                        state = getHandler().process(socketWrapper, SocketEvent.OPEN_READ);
                    } else {
                        state = getHandler().process(socketWrapper, event);
                    }
                    if (state == SocketState.CLOSED) {
                        close(socket, key);
                    }
                } else if (handshake == -1 ) {
                    getHandler().process(socketWrapper, SocketEvent.CONNECT_FAIL);
                    close(socket, key);
                } else if (handshake == SelectionKey.OP_READ){
                    socketWrapper.registerReadInterest();
                } else if (handshake == SelectionKey.OP_WRITE){
                    socketWrapper.registerWriteInterest();
                }
            } catch (CancelledKeyException cx) {
                socket.getPoller().cancelledKey(key);
            } catch (VirtualMachineError vme) {
                ExceptionUtils.handleThrowable(vme);
            } catch (Throwable t) {
                log.error("", t);
                socket.getPoller().cancelledKey(key);
            } finally {
                socketWrapper = null;
                event = null;
                //return to cache
                if (running && !paused) {
                    processorCache.push(this);
                }
            }
        }
    }

    // ----------------------------------------------- SendfileData Inner Class
    /**
     * SendfileData class.
     */
    public static class SendfileData extends SendfileDataBase {

        public SendfileData(String filename, long pos, long length) {
            super(filename, pos, length);
        }

        protected volatile FileChannel fchannel;
    }
}
