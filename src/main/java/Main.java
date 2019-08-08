import org.apache.catalina.startup.Tomcat;

import java.util.concurrent.TimeUnit;

/**
 * tomcat 架构上主要分为 connector 和 container
 *  connector 就是 socket 启动端和请求接入端
 *  container 就是 servlet 容器规范，两者之间的桥梁通过适配器模式连接 {@link org.apache.coyote.Adapter}
 *  见图 /resources/doc/Connector-Ref-Container.png
 *
 *
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
