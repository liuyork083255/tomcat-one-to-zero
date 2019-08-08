import org.apache.catalina.startup.Tomcat;

import java.util.concurrent.TimeUnit;
import org.apache.catalina.*;
import org.apache.catalina.connector.Connector;

/**
 * tomcat 架构上主要分为 connector 和 container
 *  connector 就是 socket 启动端和请求接入端
 *  container 就是 servlet 容器规范，两者之间的桥梁通过适配器模式连接 {@link org.apache.coyote.Adapter}
 *  见图 /resources/doc/Connector-Ref-Container.png
 *
 *
 * 各组件解释:
 *  {@link Server}
 *      表示整个 servlet 容器，因此 tomcat 运行环境中只有唯一一个 server 实例
 *  {@link Service}
 *      表示一个或者多个 connector 的集合，这些 connector 共享同一个 container 来处理其请求。
 *      在同一个 tomcat 实例内可以包含任意多个 service 实例，他们彼此独立
 *  {@link Connector}
 *      tomcat 链接器，用于监听并转化 socket 请求，同时将读取的 socket 请求交由 container 处理，支持不同协议以及不同的IO模型
 *  {@link Container}
 *      表示能够执行客户端请求并返回响应的一类对象。在 tomcat 中存在不同级别的容器：engine host context wrapper
 *  {@link Engine}
 *      表示整个 servlet 引擎，在 tomcat 中，engine 为最高层级的容器对象，
 *      尽管 engine 不是直接处理请求的容器，却是获取目标容器的入口
 *  {@link Host}
 *      表示 servlet 引擎（engine）中的虚拟机，与一个服务器的网络名有关，如域名等。
 *      客户端可以使用这个网络名连接服务器，这个名称必须在 DNS 服务器上注册
 *  {@link Context}
 *      主要表示 ServletContext，在 servlet 规范中，一个 ServletContext 即表示一个独立的 web 应用
 *  {@link Wrapper}
 *      用于表示 web 应用中的定义的 servlet
 *  {@link Executor}
 *      用于表示 tomcat 组件间可以共享的线程池
 *
 *
 */
public class Main {
    public static void main(String[] args) throws Exception {
        Tomcat tomcat = new Tomcat();
        tomcat.start();
        TimeUnit.DAYS.sleep(1);
    }
}
