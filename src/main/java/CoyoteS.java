/**
 *  Coyote 是 tomcat 链接器框架的名称，说白了就是 connector 的一个别名，本质就是 tomcat 中的 connector，后面可以将两者理解成一个概念
 *  客户端通过 Coyote 与服务器建立连接、发送请求并接受请求
 *  Coyote 底层封装了底层的网络 socket，将 socket 输入转为 request 对象，交给 catalina 容器处理，处理请求完成后，catalina
 *  通过 Coyote 提供的 response 对象将结果写入输出流
 *  参考 resources/Coyote与Catalina交互流程.png
 *
 *
 * Coyote 目前支持三种协议
 *  http1.1
 *  http2.0
 *  ajp: 主要用于和 apache http server 集成，实现动静分离和集群部署
 *
 * 这三种协议，Coyote 又按照 IO 方式提供了三种实现方案
 *  NIO: 采用 jdk nio 实现
 *  NIO2: 采用 jdk7 才有的 nio2 实现
 *  APR: apr 是一套 apache 可移植库，说白了就是直接由 c++ 实现的本地库，供 tomcat 直接调用，性能比 NIO 好，但是采用该方案，需要单独按照 apr 依赖库
 *
 */
public class CoyoteS {
}
