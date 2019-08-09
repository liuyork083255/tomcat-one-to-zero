import org.apache.catalina.valves.ValveBase;

/**
 * {@link ValveBase}
 * tomcat 中的容器对象具体可以分为 engine、host、context、wrapper，层级逐次向后
 * 它们的执行方式参考：resources/各容器中Valve执行流程.png
 * tomcat 会为每一个类容器实现一个默认的类 StandardXxxValve，它们的核心功能之一就是调用下一个容器
 * wrapper是最后一个容器，它的 Valve 是 StandardWrapperValve ，调用的是 filter
 *
 *
 *
 *
 *
 */
public class ValveS {
}
