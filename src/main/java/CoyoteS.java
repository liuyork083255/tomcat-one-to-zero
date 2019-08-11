/**
 *  Coyote 是 tomcat 链接器框架的名称，是 tomcat 服务器提供的供客户端访问的外部接口
 *  客户端通过 Coyote 与服务器建立连接、发送请求并接受请求
 *  Coyote 底层封装了底层的网络 socket，将 socket 输入转为 request 对象，交给 catalina 容器处理，处理请求完成后，catalina
 *  通过 Coyote 提供的 response 对象将结果写入输出流
 *  参考 resources/Coyote与Catalina交互流程.png
 *
 *
 *
 */
public class CoyoteS {
}
