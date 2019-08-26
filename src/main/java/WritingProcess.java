import org.apache.catalina.connector.OutputBuffer;
import org.apache.catalina.connector.CoyoteWriter;
import org.apache.catalina.connector.CoyoteOutputStream;
import org.apache.tomcat.util.net.NioEndpoint;

import java.nio.ByteBuffer;

/**
 * 写流程
 *  写出数据都是依赖 {@link org.apache.coyote.Response} 对象
 *  response 有两个响应对象，一个是 字符流，一个是 字节流
 *      获取字符流：
 *          PrintWriter out = response.getWriter(); 其实返回的是 {@link CoyoteWriter} 对象
 *      获取字节流：
 *          ServletOutputStream out = response.getOutputStream(); 其实返回的是 {@link CoyoteOutputStream} 对象
 *   Note：
 *      1 在一个请求中不能同时使用response.getWriter()和response.getOutputStream()这两个流，不然会抛出非法的状态异常
 *      2 在相应之前需要设置编码格式，否则默认是 ISO-8859-1，response.setCharacterEncoding
 *
 * 不管是哪一种响应流对象，他们底层都是依赖 {@link OutputBuffer} 对象来实现响应，因为它们都封装了该对象
 *  {@link CoyoteWriter#ob}
 *  {@link CoyoteOutputStream#ob}
 * 不管是哪一种响应流对象，他们真正写入数据都是依赖 .close 方法，而不是 flush 方法，调用的都是 {@link OutputBuffer#close()}，
 * 该方法进入和最终都是调用的 {@link NioEndpoint.NioSocketWrapper#doWrite(boolean, ByteBuffer)}
 *
 *
 *
 *
 */
@SuppressWarnings("all")
public class WritingProcess {
}
