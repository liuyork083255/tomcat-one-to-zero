package org.apache;

/**
 * 性能优化
 *
 *  1 最大连接数：maxConnections，默认1W，{@link org.apache.tomcat.util.net.AbstractEndpoint#maxConnections}
 *    在和 spring 结合可以通过 server.tomcat.max-connections 设置
 *  2 接收请求数：acceptCount，其实就是 tcp-socket backlog 参数，{@link org.apache.catalina.connector.Connector#replacements}
 *    比如当前连接 keep-alive 达到了 maxConnections
 *    这个时候又有连接接入，那么就是占用 acceptCount，也就是 tomcat 能处理的连接数为：acceptCount + maxConnections
 *    这个不是应用程序控制的，而是在 tcp 传输层控制的，参考:https://www.cnblogs.com/Orgliny/p/5780796.html
 *    Note：
 *      maxConnections 连接数不是请求数，一个连接可以发送N次请求(N可以配置，默认时间1分钟断开)，但是只会占用一个 connector
 *      spring 中配置 server.tomcat.accept-count
 *      tomcat 中配置在 server.xml 文件的 Connector 节点 <Connector port="8080" protocol="HTTP/1.1" acceptCount="1024"/>
 *  3 设置 tcpNoDelay 参数为 true，禁用 Nagle 算法，增加实时性
 *  4 调整 maxKeepAliveRequest 值，用于控制 http 请求 keep-alive 连接服务器关闭之前可以接收的最大数目，在 <Connector/>中设置
 *  5 将 enableLookups 设置 false，禁用 request.getRemoteHost 的 DNS 查找功能，减少时间
 *  6 还有启用 GZIP 静态文件压缩
 *  7 IO 线程模型，目前认为最高效的是 APR，但是依赖 Apache 安装库
 *  8 最大线程数，即同时处理任务的个数，默认 200
 *      spring 中配置 server.tomcat.max-threads
 *      tomcat 中配置在 server.xml 文件的 Connector 节点 <Connector port="8080" protocol="HTTP/1.1" maxThreads="200"/>
 *  9 调整 keep-alive 时间
 *    这个默认不用调整，因为会根据并发情况自动适配，如果需要设置，
 *    则在 <Connector port="8080" protocol="HTTP/1.1" keepAliveTimeout="60000"/>，单位毫秒
 *    同时可以设置一次连接中最大请求数目：<Connector port="8080" protocol="HTTP/1.1" maxKeepAliveRequest="30"/>
 *      默认 100，1表示禁用，-1表示不限制个数，建议设置成 一般设置在100~200之间
 *
 *
 */
@SuppressWarnings("all")
public class PerformanceOptimizationS {
    /*
     * TCP 三次握手和两次状态转变：
     *
     *  backlog 其实是一个连接队列，在 Linux 内核2.2之前，backlog大小包括半连接状态和全连接状态两种队列的总和
     *  但是在内核2.2之后，backlog 队列分为两类，分别是 SYN queue 和 Accept queue
     *
     *  半连接状态：三次握手中，服务端处于监听状态，client 第一次发送 SYN，server 收到后，
     *             将当前连接设置为 半连接状态，并且放在 SYN queue
     *  全连接状态：TCP 的连接状态从服务器（SYN+ACK）响应客户端后，到客户端的ACK报文到达服务器之前，
     *             则一直保留在半连接状态中；当服务器接收到客户端的ACK报文后，该条目将从半连接队列搬到全连接 Accept queue 队列
     *
     *  半连接状态队列大小默认 2048，cat /proc/sys/net/ipv4/tcp_max_syn_backlog
     *  全连接状态队列大小默认 1024，cat /proc/sys/net/core/somaxconn
     *
     *  流程图参考 resources/doc/TCP建立连接backlog队列流程图.png
     *
     *
     */
}
